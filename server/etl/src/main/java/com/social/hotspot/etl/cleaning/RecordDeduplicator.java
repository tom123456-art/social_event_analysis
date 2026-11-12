package com.social.hotspot.etl.cleaning;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;

import static org.apache.spark.sql.functions.*;

/**
 * 按联合内容键对有效记录去重，并统计被淘汰的重复版本数量。
 * 批内去重与历史增量合并使用不同的排序规则，输入须已通过质量过滤。
 */
public final class RecordDeduplicator {
    // 禁止实例化仅提供静态方法的记录去重工具类。
    private RecordDeduplicator() {}

    // valid 是每组保留一条后的数据集；duplicateCount 是移除记录数，而不是重复分组数。
    public record Result(Dataset<Row> valid, long duplicateCount) {}

    // 对当前批次已通过质量过滤的记录去重，每个内容键只保留优先级最高的版本。
    public static Result deduplicate(Dataset<Row> candidates) {
        // 同一内容键内先保留人工审核通过版本，再依次选择发布时间、采集时间较新的版本。
        // desc_nulls_last 将缺失时间放到最后；所有排序字段相同时没有额外的固定胜出规则。
        WindowSpec window = contentWindow().orderBy(col("_review_approved").desc(),
                col("publish_time").desc_nulls_last(), col("crawl_time").desc_nulls_last());
        return keepFirst(candidates, window);
    }

    // 对历史明细与本批新增明细的合并结果去重，使最近采集版本覆盖旧版本。
    public static Result deduplicateIncremental(Dataset<Row> combined) {
        // 历史与新增数据合并时，先选最近采集版本，采集时间相同时再比较发布时间。
        // 此处不使用人工通过标记排序，与当前批次去重的优先级有意区分。
        WindowSpec window = contentWindow().orderBy(col("crawl_time").desc_nulls_last(),
                col("publish_time").desc_nulls_last());
        return keepFirst(combined, window);
    }

    // 按指定窗口排序并编号，统计非首条记录数，再移除临时序号列。
    private static Result keepFirst(Dataset<Row> records, WindowSpec window) {
        // 在每个分组内按窗口顺序生成从 1 开始的连续序号，不改动业务字段。
        Dataset<Row> ranked = records.withColumn("_dedupe_rank", row_number().over(window));
        // 序号大于 1 的都是待移除版本；count 会触发 Spark 实际执行计算。
        long duplicateCount = ranked.filter(col("_dedupe_rank").gt(1)).count();
        // 仅保留组内第一条并去掉内部排名列，返回数据集可继续参与后续转换。
        return new Result(ranked.filter(col("_dedupe_rank").equalTo(1)).drop("_dedupe_rank"), duplicateCount);
    }

    // 以事件、平台、内容编号和内容类型构成联合内容键，避免跨事件或跨平台误去重。
    private static WindowSpec contentWindow() {
        // 四个字段均相同才属于同一组；不同内容类型（如帖子与评论）不会互相覆盖。
        return Window.partitionBy("event_id", "platform", "content_id", "content_type");
    }
}
