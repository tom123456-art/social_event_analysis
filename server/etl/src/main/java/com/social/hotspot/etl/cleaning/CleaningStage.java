package com.social.hotspot.etl.cleaning;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import static com.social.hotspot.etl.cleaning.DataFrameColumns.*;
import static com.social.hotspot.etl.cleaning.RecordQualityExpressions.textQualityReason;
import static org.apache.spark.sql.functions.*;

// 清洗入口不依赖数据库连接、文件输出或任务状态存储。
public final class CleaningStage {
    // 所有操作通过静态入口完成，不保存批次状态。
    private CleaningStage() {}

    // 同时保留原始损坏证据和规范化视图，供隔离输出及审核核验使用。
    public record Prepared(Dataset<Row> raw, Dataset<Row> normalized) {}

    // 将去重后的明细与同口径计数一并返回给任务日志。
    public record Result(Dataset<Row> valid, long candidateCount, long duplicateCount) {}

    // 统一执行补字段、原始损坏检测和字段规范化。
    public static Prepared prepare(Dataset<Row> parsed, String eventId, String eventName, String batchId) {
        // 补齐可选字段，避免表达式依赖上传文件必须包含全部字段。
        Dataset<Row> raw = ensureColumns(parsed, "event_id", "event_name", "url", "source_url", "parent_content_id")
                // 仅在来源事件标识为空时使用作业事件；以作业事件名称为准并限制数据库字段长度。
                .withColumn("event_id", coalesce(nullIfBlank(col("event_id")), lit(eventId)))
                .withColumn("event_name", lit(eventName).substr(1, 200));
        // 在删除替换字符前记录损坏原因，防止清理掩盖损坏证据。
        raw = raw.withColumn("text_quality_reason", textQualityReason(raw));
        // 返回两个惰性视图，不改写原始 CSV 或生成输出文件。
        return new Prepared(raw, ContentNormalizer.normalize(raw, batchId));
    }

    // 完整执行有效性过滤和批内去重，输出可合并情感结果的业务明细。
    public static Result clean(Dataset<Row> normalized) {
        // 无效及暂缓记录先退出，不能参与保留版本的竞争；记录通过质量校验、尚未去重的条数；同内容键内优先人工审核，再按发布时间和采集时间倒序排名。
        Dataset<Row> candidates = retainUsable(normalized);
        long candidateCount = candidates.count();
        Dataset<Row> ranked = rankCandidates(candidates);
        // 只将有效候选中排名大于一的记录计入重复数；保留第一名并移除内部质量标记，审核字段继续留给后续核验。
        long duplicateCount = ranked.filter(col("_dedupe_rank").gt(1)).count();
        Dataset<Row> valid = ranked.filter(col("_dedupe_rank").equalTo(1))
                // 内部序号和字段异常标记不进入下游业务明细。
                .drop("_dedupe_rank", "has_invalid_numeric", "is_structurally_invalid", "has_invalid_content_id",
                        // 文本损坏原因已保存在原始视图，去重结果不重复携带。
                        "is_potentially_truncated", "text_normalized", "text_quality_reason");
        // 明细与计数来自同一套规则，调用方不再重复拼装过滤条件。
        return new Result(valid, candidateCount, duplicateCount);
    }

    // 从规范化数据中筛选可进入有效明细的记录。
    private static Dataset<Row> retainUsable(Dataset<Row> normalized) {
        // 计算正文中的中英文及数字字符，用于有效长度校验；去掉正文空白，作为有效字符比例的分母。
        Column meaningfulChars = regexp_replace(col("clean_text"), "[^\\p{IsHan}A-Za-z0-9]", "");
        Column compactChars = regexp_replace(col("clean_text"), "\\s+", "");
        // 可修复的互动字段和关键词标点异常不作为正文淘汰条件。
        return normalized
                // 剔除存在不可逆编码损坏的记录；暂缓审核记录在通过审核前不进入分析；所有有效明细必须归属某个事件。
                .filter(col("text_quality_reason").isNull())
                .filter(not(col("_review_hold")))
                .filter(col("event_id").isNotNull())
                // 只接受六个标准来源平台；微博必须有原正文有效字符，不能只靠标题补足；剔除 CSV 字段结构解析失败的记录。
                .filter(col("platform").isin("TENCENT_NEWS", "NETEASE_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER", "WEIBO"))
                .filter(not(col("platform").equalTo("WEIBO")).or(length(regexp_replace(col("content_text"), "[^\\p{IsHan}A-Za-z0-9]", "")).geq(1)))
                .filter(not(col("is_structurally_invalid")))
                // 发布时间解析失败的记录不参与时间聚合；采集时间解析失败的记录不能作为可靠来源快照；分析正文至少包含四个有效字符。
                .filter(col("publish_time").isNotNull())
                .filter(col("crawl_time").isNotNull())
                .filter(length(meaningfulChars).geq(4))
                // 有效字符必须占非空白正文的至少五分之一。
                .filter(length(meaningfulChars).multiply(5).geq(length(compactChars)));
    }

    // 对全量候选记录排名，优先保留人工审核通过的版本。
    private static Dataset<Row> rankCandidates(Dataset<Row> qualityCandidates) {
        // 相同事件、平台、内容 ID 和类型视为同一条内容。
        WindowSpec dedupeWindow = Window.partitionBy("event_id", "platform", "content_id", "content_type")
                // 人工通过版本优先，其次取发布时间和采集时间最新的版本。
                .orderBy(col("_review_approved").desc(), col("publish_time").desc_nulls_last(), col("crawl_time").desc_nulls_last());
        // 为后续保留排名第一的记录生成去重序号。
        return qualityCandidates.withColumn("_dedupe_rank", row_number().over(dedupeWindow));
    }
    // 对增量与历史合并记录排名，优先保留最近采集版本。
    public static Dataset<Row> rankIncremental(Dataset<Row> combined) {
        // 以统一内容键划分增量合并窗口。
        WindowSpec mergeWindow = Window.partitionBy("event_id", "platform", "content_id", "content_type")
                    // 先比较采集时间，再比较发布时间，空时间排到最后。
                    .orderBy(col("crawl_time").desc_nulls_last(), col("publish_time").desc_nulls_last());
        // 写入合并序号，由调用方选择最新版本。
        return combined.withColumn("_merge_rank", row_number().over(mergeWindow));
    }
}
