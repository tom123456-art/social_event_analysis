package com.social.hotspot.etl.job;
import com.social.hotspot.etl.warehouse.DwsSnapshotMaterializer;

import org.apache.spark.sql.SparkSession;
import java.util.Arrays;
import static org.apache.spark.sql.functions.*;

/** 基于已有 DWD 批次补建缺失的 DWS 层，不修改 ADS 和 MySQL 数据。 */
public class DwsBackfillJob {
    /** 读取 DWD、补齐热度指标并物化 DWS 批次快照。 */
    public static void main(String[] args) throws Exception {
        // 四个参数依次为数仓根目录、批次号、重复计数和 DWD 路径。
        if (args.length != 4) throw new IllegalArgumentException("Usage: <warehouse-root> <batch-id> <duplicate-count> <dwd-path>");
        // 固定上海时区和八个 shuffle 分区，创建回填专用 Spark 会话。
        try (SparkSession spark = SparkSession.builder().appName("dws-backfill-" + args[1])
                .config("spark.sql.session.timeZone", "Asia/Shanghai")
                .config("spark.sql.shuffle.partitions", "8").getOrCreate()) {
            // 从指定 Parquet 目录读取历史明细。
            var detail = spark.read().parquet(args[3]);
            if (!Arrays.asList(detail.columns()).contains("sentiment_source")) {
                // 旧批次缺少来源字段时，以 MODEL 补齐聚合所需列。
                detail = detail.withColumn("sentiment_source", lit("MODEL"));
            }
            // 重算平台相对热度并缓存，供多个汇总任务复用；将重复记录计数与明细一起用于构建 DWS 汇总；将各类汇总写入指定批次快照目录。
            detail = com.social.hotspot.etl.metric.InteractionHeatCalculator.buildPlatformRelativeHeat(detail).cache();
            var summaries = com.social.hotspot.etl.aggregate.DwsSummaryAggregator.buildDwsSummaries(detail, Long.parseLong(args[2]));
            var stored = DwsSnapshotMaterializer.materialize(spark, args[0], args[1], summaries);
            for (var entry : stored.entrySet()) {
                // 触发行数统计并输出每张 DWS 表的回填结果。
                System.out.println("DWS_BACKFILL " + entry.getKey() + " rows=" + entry.getValue().count());
            }
            System.out.println("DWS_BACKFILL_COMPLETE batch=" + args[1] + " detail_rows=" + detail.count());
        }
    }
}
