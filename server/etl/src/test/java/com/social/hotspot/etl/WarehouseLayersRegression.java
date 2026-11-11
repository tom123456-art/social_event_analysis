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
            verifyOds(spark, root);
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

    private static void verifyOds(SparkSession spark, String root) throws Exception {
        for (int width : new int[]{19, 21, 25}) {
            var fields = new java.util.ArrayList<String>(java.util.List.of(
                    com.social.hotspot.etl.warehouse.WarehouseDatasetWriter.ODS_COLUMNS).subList(0, 25));
            if (width == 21) fields = new java.util.ArrayList<>(fields.subList(0, 21));
            if (width == 19) {
                fields = new java.util.ArrayList<>(fields.subList(0, 21));
                fields.remove("author_id"); fields.remove("view_count");
            }
            String text = "line one, \"quoted\"\nline two";
            String values = fields.stream().map(f -> "\"" + (f.equals("content_text") ? text : f + "_value")
                    .replace("\"", "\"\"") + "\"").collect(java.util.stream.Collectors.joining(","));
            var file = java.nio.file.Files.createTempFile("ods-columns-", ".csv");
            try {
                java.nio.file.Files.writeString(file, String.join(",", fields) + System.lineSeparator() + values);
                var parsed = com.social.hotspot.etl.source.InputDatasetReader.readInput(spark, file.toUri().toString());
                String batch = "20261109" + width;
                com.social.hotspot.etl.warehouse.WarehouseDatasetWriter.writeWarehouseOds(root, batch, parsed);
                var stored = spark.read().parquet(root + "/ods/ods_social_content_raw/batch_id=" + batch);
                if (stored.count() != 1) throw new AssertionError("Multiline CSV split into multiple ODS rows");
                var row = stored.first();
                for (String field : fields) {
                    String expected = field.equals("content_text") ? text : field + "_value";
                    if (!expected.equals(row.getAs(field))) throw new AssertionError("ODS field shifted: " + width + "/" + field);
                }
                if (width == 19 && (row.getAs("author_id") != null || row.getAs("view_count") != null)) {
                    throw new AssertionError("Missing CSV fields must stay NULL");
                }
            } finally { java.nio.file.Files.deleteIfExists(file); }
        }
        var broken = java.nio.file.Files.createTempFile("ods-broken-", ".csv");
        try {
            java.nio.file.Files.writeString(broken, "event_id,content_id\nevent,valid\nevent,broken,extra\n");
            var parsed = com.social.hotspot.etl.source.InputDatasetReader.readInput(spark, broken.toUri().toString()).cache();
            try {
                if (parsed.count() != 2 || parsed.filter(col("_corrupt_record").isNotNull()).count() != 1) {
                    throw new AssertionError("Malformed CSV evidence lost before DWD quality checks");
                }
            } finally { parsed.unpersist(); }
        } finally { java.nio.file.Files.deleteIfExists(broken); }
    }
}
