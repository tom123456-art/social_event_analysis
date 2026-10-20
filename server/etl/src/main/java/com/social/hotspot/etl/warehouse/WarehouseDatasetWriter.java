package com.social.hotspot.etl.warehouse;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import java.util.Map;
import static org.apache.spark.sql.functions.*;

public final class WarehouseDatasetWriter {
    // 禁止实例化仓库写入工具类。
    private WarehouseDatasetWriter() {}
    // 将 DWS 汇总转换为应用层 ADS 快照并覆盖对应 Parquet 目录。
    public static void writeWarehouseAds(String warehouseOutput, Map<String, Dataset<Row>> dws) {
        // 计算应用层目录前缀；事件热度汇总结构可直接作为应用层热度趋势；将平台日汇总的日期字段统一为应用层时间桶。
        String adsRoot = warehouseOutput + "/ads/";
        dws.get("dws_event_heat_summary").write().mode(SaveMode.Overwrite).parquet(adsRoot + "ads_event_heat_trend");
        dws.get("dws_platform_daily_summary").select(
                        col("event_id"), col("platform"), col("heat_day").alias("time_bucket"), col("content_count"),
                        col("average_heat_index"), col("peak_content_heat_index"), col("high_heat_content_count"), col("heat_algorithm"))
                .write().mode(SaveMode.Overwrite).parquet(adsRoot + "ads_platform_daily_heat");
        // 将类别日汇总字段映射为前端分析约定字段。
        dws.get("dws_platform_category_summary").select(
                        col("event_id"), col("platform"), col("category_day").alias("time_bucket"), col("category_label").alias("category"),
                        col("content_count"), col("platform_content_count"), col("coverage_share"), col("average_relative_heat_index"),
                        col("composite_attention_index"), col("signal_mode"))
                .write().mode(SaveMode.Overwrite).parquet(adsRoot + "ads_platform_category_heat");
        // 发布主题趋势、主题摘要和主题关键内容三类结果。
        for (String name : new String[]{"topic_trend", "topic_summary", "topic_key_content"}) {
            // 读取对应主题汇总数据集。
            Dataset<Row> output = dws.get("dws_" + name);
            // 确保主题摘要峰值时间具有仓库时间戳类型。
            if ("topic_summary".equals(name)) output = output.withColumn("peak_time", col("peak_time").cast("timestamp"));
            // 每次发布完整快照，替换旧版主题结果。
            output.write().mode(SaveMode.Overwrite).parquet(adsRoot + "ads_" + name);
        }
    }

    // 将有效明细以批次分区写入 DWD 层。
    public static void writeWarehouseDwd(String warehouseOutput, String batchId, Dataset<Row> detail) {
        // 显式选择仓库契约字段，避免临时质量检测列泄露到明细层。
        detail.select(
                        "event_id", "event_name", "platform", "content_id", "parent_content_id", "content_type", "title", "clean_text",
                        "author_id", "author_name", "publish_time", "crawl_time", "like_count", "comment_count", "repost_count",
                        "favorite_count", "view_count", "keywords", "time_bucket", "interaction_count", "sentiment_label",
                        "is_noise", "hot_score", "share_count", "forward_count", "location", "user_age_group", "user_gender",
                        "source_url", "image_url", "category", "batch_id", "sentiment_source", "_review_id", "_review_sentiment", "_review_approved", "_review_hold", "sentiment_positive_score",
                        "sentiment_neutral_score", "sentiment_negative_score", "content_text", "platform_heat_index",
                        "signal_mode", "has_heat_signal", "interaction_signal_count")
                // 只覆盖当前批次目录，不影响其他批次历史明细。
                .write().mode(SaveMode.Overwrite).parquet(warehouseOutput + "/dwd/dwd_social_content_detail/batch_id=" + batchId);
    }
}
