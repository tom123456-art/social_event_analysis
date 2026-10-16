package com.social.hotspot.etl;
import com.social.hotspot.etl.warehouse.DwsSnapshotMaterializer;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import java.util.Map;
import static org.apache.spark.sql.functions.*;

public class WarehouseLayersRegression {
    public static void main(String[] args) throws Exception {
        try (SparkSession spark = SparkSession.builder().appName("warehouse-layers-regression")
                .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "2")
                .config("spark.sql.adaptive.enabled", "false")
                .config("spark.sql.session.timeZone", "Asia/Shanghai").getOrCreate()) {
            spark.sparkContext().setLogLevel("WARN");
            String root = args[0];
            Dataset<Row> detail = spark.range(3).withColumn("event_id", lit("test-event"))
                    .withColumn("platform", when(col("id").equalTo(2), "WEIBO").otherwise("SOHU_NEWS"))
                    .withColumn("publish_time", to_timestamp(lit("2026-10-01 10:00:00")))
                    .withColumn("time_bucket", col("publish_time"))
                    .withColumn("content_id", col("id").cast("string"))
                    .withColumn("interaction_count", lit(21L)).withColumn("hot_score", lit(10D))
                    .withColumn("platform_heat_index", lit(10D)).withColumn("signal_mode", lit("TEST"))
                    .withColumn("sentiment_label", when(col("id").equalTo(1), lit(null).cast("string")).otherwise(lit("positive")))
                    .withColumn("is_noise", lit(false))
                    .withColumn("category", lit("news")).withColumn("keywords", lit("test,topic"));
            for (String field : new String[]{"parent_content_id", "content_type", "title", "clean_text", "author_name", "sentiment_source", "source_url"}) {
                detail = detail.withColumn(field, lit("test"));
            }
            for (String field : new String[]{"like_count", "favorite_count", "comment_count", "repost_count"}) {
                detail = detail.withColumn(field, lit(1L));
            }
            for (String field : new String[]{"sentiment_positive_score", "sentiment_neutral_score", "sentiment_negative_score"}) {
                detail = detail.withColumn(field, lit(0D).cast(DataTypes.DoubleType));
            }
            Map<String, Dataset<Row>> expected = com.social.hotspot.etl.aggregate.DwsSummaryAggregator.buildDwsSummaries(detail, 4);
            Map<String, Dataset<Row>> stored = DwsSnapshotMaterializer.materialize(spark, root, "202610130001", expected);
            if (stored.size() != 12) throw new AssertionError("Expected 12 DWS tables");
            for (String table : stored.keySet()) {
                var actualRows = stored.get(table).collectAsList().stream().map(Row::toString).sorted().toList();
                var expectedRows = expected.get(table).collectAsList().stream().map(Row::toString).sorted().toList();
                if (!actualRows.equals(expectedRows)) {
                    throw new AssertionError("DWS changed values: " + table);
                }
            }
            long total = ((Number) stored.get("dws_event_heat_summary").agg(sum("content_count")).first().get(0)).longValue();
            if (total != 3) throw new AssertionError("Content count mismatch");
            long interactions = ((Number) stored.get("dws_event_heat_summary").agg(sum("interaction_count")).first().get(0)).longValue();
            if (interactions != 63) throw new AssertionError("Interaction count mismatch");
            long sentiments = ((Number) stored.get("dws_sentiment_summary").agg(sum("sentiment_count")).first().get(0)).longValue();
            if (sentiments != 2) throw new AssertionError("Uncertain content entered sentiment summary");
            if (stored.get("dws_content_rank").count() != 3) throw new AssertionError("Uncertain content lost from ranking");
            long propagated = ((Number) stored.get("dws_platform_spread_summary").agg(sum("content_count")).first().get(0)).longValue();
            if (propagated != 3) throw new AssertionError("Uncertain content lost from propagation");
            long keywordCount = ((Number) stored.get("dws_keyword_summary").agg(sum("word_count")).first().get(0)).longValue();
            if (keywordCount != 6) throw new AssertionError("Uncertain content lost from keywords");
            var ads = com.social.hotspot.etl.warehouse.WarehouseDatasetWriter.class.getDeclaredMethod("writeWarehouseAds", String.class, Map.class);
            ads.setAccessible(true);
            ads.invoke(null, root, stored);
            for (String name : new String[]{"event_heat_trend", "platform_daily_heat", "platform_category_heat", "topic_trend", "topic_summary", "topic_key_content"}) {
                if (spark.read().parquet(root + "/ads/ads_" + name).count() == 0) throw new AssertionError("Empty ADS " + name);
            }
            if (!spark.read().parquet(root + "/ads/ads_topic_summary").schema().apply("peak_time").dataType().equals(DataTypes.TimestampType)) {
                throw new AssertionError("ADS peak_time must match the Hive TIMESTAMP contract");
            }
            Map<String, Dataset<Row>> second = DwsSnapshotMaterializer.materialize(spark, root, "202610130002", expected);
            if (second.size() != 12 || stored.get("dws_event_summary").count() != 1) throw new AssertionError("Batch isolation failed");
            var manifest = new org.apache.hadoop.fs.Path(root + "/manifests/batch_id=202610130001/dws.sql");
            String sql;
            try (var input = manifest.getFileSystem(spark.sparkContext().hadoopConfiguration()).open(manifest)) {
                sql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            for (String table : stored.keySet()) {
                if (!sql.contains("CREATE EXTERNAL TABLE IF NOT EXISTS social_dws." + table)
                        || !sql.contains("ALTER TABLE social_dws." + table)) throw new AssertionError("Missing Hive DDL " + table);
            }
            System.out.println("PASS: 12 DWS tables preserve values, 6 ADS outputs read DWS, batch isolation and Hive manifest");
        }
    }
}
