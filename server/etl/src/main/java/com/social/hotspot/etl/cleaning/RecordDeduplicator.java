package com.social.hotspot.etl.cleaning;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;

import static org.apache.spark.sql.functions.*;

/** 统一维护内容键、保留版本优先级和重复数量，输入须已通过质量过滤。 */
public final class RecordDeduplicator {
    // 禁止实例化仅提供静态方法的记录去重工具类。
    private RecordDeduplicator() {}

    // 同时返回去重后的有效记录与被移除的重复记录数，供作业输出及质量统计使用。
    public record Result(Dataset<Row> valid, long duplicateCount) {}

    // 对当前批次已通过质量过滤的记录去重，每个内容键只保留优先级最高的版本。
    public static Result deduplicate(Dataset<Row> candidates) {
        // 批内优先人工通过版本，其次发布时间和采集时间。
        WindowSpec window = contentWindow().orderBy(col("_review_approved").desc(),
                col("publish_time").desc_nulls_last(), col("crawl_time").desc_nulls_last());
        return keepFirst(candidates, window);
    }

    // 对历史明细与本批新增明细的合并结果去重，使最近采集版本覆盖旧版本。
    public static Result deduplicateIncremental(Dataset<Row> combined) {
        // 增量合并优先最近采集版本，再比较发布时间。
        WindowSpec window = contentWindow().orderBy(col("crawl_time").desc_nulls_last(),
                col("publish_time").desc_nulls_last());
        return keepFirst(combined, window);
    }

    // 按指定窗口排序并编号，统计非首条记录数，再移除临时序号列。
    private static Result keepFirst(Dataset<Row> records, WindowSpec window) {
        Dataset<Row> ranked = records.withColumn("_dedupe_rank", row_number().over(window));
        long duplicateCount = ranked.filter(col("_dedupe_rank").gt(1)).count();
        return new Result(ranked.filter(col("_dedupe_rank").equalTo(1)).drop("_dedupe_rank"), duplicateCount);
    }

    // 以事件、平台、内容编号和内容类型构成联合内容键，避免跨事件或跨平台误去重。
    private static WindowSpec contentWindow() {
        return Window.partitionBy("event_id", "platform", "content_id", "content_type");
    }
}
