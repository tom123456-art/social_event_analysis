package com.social.hotspot.etl;

import com.social.hotspot.etl.cleaning.FieldNormalizer;
import com.social.hotspot.etl.cleaning.RecordDeduplicator;
import com.social.hotspot.etl.cleaning.RecordFilter;
import com.social.hotspot.etl.cleaning.TextNormalizer;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.spark.sql.functions.*;

/** In-memory regression: no database access or warehouse writes. */
public class CleaningRegression {
    private static final String TIME = "2026-10-01 10:00:00";

    public static void main(String[] args) throws Exception {
        try (SparkSession spark = SparkSession.builder().master("local[2]").appName("cleaning-regression")
                .config("spark.ui.enabled", "false").config("spark.sql.ansi.enabled", "false")
                .config("spark.sql.session.timeZone", "Asia/Shanghai")
                .config("spark.sql.shuffle.partitions", "2").getOrCreate()) {
            spark.sparkContext().setLogLevel("WARN");
            verifyText(spark);
            verifyFilterAndDedupe(spark);
        }
        System.out.println("PASS: shared text normalization, repair flags, raw evidence, filters and batch/incremental deduplication.");
    }

    private static void verifyText(SparkSession spark) {
        StructType schema = new StructType().add("title", DataTypes.StringType)
                .add("content_text", DataTypes.StringType).add("keywords", DataTypes.StringType);
        Dataset<Row> input = spark.createDataFrame(List.of(
                RowFactory.create(" A  B ", " A  B ", " A  B "),
                RowFactory.create("<b>Title</b>", null, "word!!!"),
                RowFactory.create("Title", "Body https://example.com", "word"),
                RowFactory.create("Title", "Body", "word"),
                RowFactory.create("Title", "中".repeat(1100), "word"),
                RowFactory.create(null, null, null)), schema);
        List<Row> rows = TextNormalizer.normalize(input).collectAsList();
        Row first = rows.getFirst();
        for (String field : List.of("title", "content_text", "keywords")) {
            require("A B".equals(first.getAs("_normalized_" + field)), "Different whitespace rules: " + field);
            require(" A  B ".equals(first.getAs(field)), "Raw evidence overwritten: " + field);
        }
        require(rows.get(0).getAs("text_normalized"), "Whitespace repair not counted");
        require(rows.get(1).getAs("text_normalized"), "HTML/punctuation repair not counted");
        require("Title".equals(rows.get(1).getAs("clean_text")), "Title fallback changed");
        require(rows.get(2).getAs("text_normalized"), "URL repair not counted");
        require(!rows.get(3).<Boolean>getAs("text_normalized"), "Unchanged text counted as repaired");
        require(!rows.get(4).<Boolean>getAs("text_normalized"), "Truncation counted as formatting repair");
        require(rows.get(4).<String>getAs("clean_text").length() == 1000, "Analysis text limit changed");
        require(!rows.get(5).<Boolean>getAs("text_normalized"), "Missing text counted as repaired");
        require("".equals(rows.get(5).getAs("clean_text")), "Missing text not normalized");
    }

    private static void verifyFilterAndDedupe(SparkSession spark) {
        StructType schema = new StructType();
        for (String field : List.of("content_id", "platform", "content_text", "title", "publish_time", "crawl_time",
                "_review_approved", "_review_hold", "_corrupt_record", "keywords", "like_count")) {
            schema = schema.add(field, DataTypes.StringType);
        }
        var input = new ArrayList<Row>();
        input.add(record("duplicate", "SOHU_NEWS", "正常有效正文", "人工标题", TIME, TIME, "true", "false", null));
        input.add(record("duplicate", "SOHU_NEWS", "正常有效正文", "较新标题", "2026-10-02 10:00:00", TIME, "false", "false", null));
        input.add(record("duplicate", "SOHU_NEWS", "正常有效正文", "暂缓标题", TIME, TIME, "true", "true", null));
        input.add(record("damaged", "SOHU_NEWS", "正常\uFFFD正文", "正常标题", TIME, TIME, "false", "false", null));
        input.add(record("corrupt", "SOHU_NEWS", "正常有效正文", "正常标题", TIME, TIME, "false", "false", "bad csv"));
        input.add(record("empty", "SOHU_NEWS", " ", " ", TIME, TIME, "false", "false", null));
        input.add(record("weibo-empty", "WEIBO", " ", "正常有效标题", TIME, TIME, "false", "false", null));
        input.add(record("unknown", "UNKNOWN", "正常有效正文", "正常标题", TIME, TIME, "false", "false", null));
        input.add(record("bad-time", "SOHU_NEWS", "正常有效正文", "正常标题", "bad", TIME, "false", "false", null));
        input.add(record("no-crawl", "SOHU_NEWS", "正常有效正文", "正常标题", TIME, null, "false", "false", null));
        input.add(record("short", "SOHU_NEWS", "短文", "正常标题", TIME, TIME, "false", "false", null));
        input.add(record("symbols", "SOHU_NEWS", "正文有效" + "★".repeat(30), "正常标题", TIME, TIME, "false", "false", null));
        input.add(record("repairable", "sohu-news", "正常有效正文", "标题... ", TIME, TIME, "false", "false", null));
        input.add(record(null, "SOHU_NEWS", " 原始  正文 ", "正常标题", TIME, TIME, "false", "false", null));
        Dataset<Row> raw = RecordFilter.inspectRaw(FieldNormalizer.prepareEvent(
                spark.createDataFrame(input, schema), "event", "Event"));
        Dataset<Row> normalized = FieldNormalizer.normalize(TextNormalizer.normalize(raw), "test").cache();
        require(raw.filter(col("text_quality_reason").isNotNull()).count() == 1, "Damage evidence lost");
        require(normalized.filter(col("content_id").equalTo("repairable")).first().<Boolean>getAs("has_invalid_numeric"),
                "Invalid numeric flag lost");
        String expectedHash = raw.filter(col("content_id").isNull()).select(sha2(concat_ws("||",
                col("platform"), col("content_text"), col("publish_time")), 256)).first().getString(0);
        require(normalized.filter(col("content_id").equalTo(expectedHash)).count() == 1, "Original content hash changed");
        Dataset<Row> candidates = RecordFilter.retainUsable(normalized);
        require(candidates.count() == 4, "Invalid records participated in deduplication");
        require(RecordFilter.retainUsable(normalized.withColumn("event_id", lit(null).cast("string"))).count() == 0,
                "Unassigned records retained");
        var result = RecordDeduplicator.deduplicate(candidates);
        require(result.duplicateCount() == 1, "Wrong duplicate count");
        Set<String> ids = result.valid().select("content_id").collectAsList().stream()
                .map(row -> row.getString(0)).collect(Collectors.toSet());
        require(ids.equals(Set.of("duplicate", "repairable", expectedHash)), "Wrong retained records: " + ids);
        require("人工标题".equals(result.valid().filter(col("content_id").equalTo("duplicate")).first().getAs("title")),
                "Manual approval lost priority");
        require(!List.of(result.valid().columns()).contains("text_quality_reason"), "Internal quality fields leaked");
        Dataset<Row> historical = candidates.filter(col("content_id").equalTo("repairable"));
        Dataset<Row> newer = historical.withColumn("crawl_time", to_timestamp(lit("2026-10-03 10:00:00")))
                .withColumn("title", lit("latest"));
        var incremental = RecordDeduplicator.deduplicateIncremental(historical.unionByName(newer));
        require(incremental.duplicateCount() == 1, "Wrong incremental duplicate count");
        Row merged = incremental.valid().first();
        require("latest".equals(merged.getAs("title")), "Incremental merge lost latest crawl");
        Dataset<Row> separateKeys = historical
                .unionByName(historical.withColumn("event_id", lit("other-event")))
                .unionByName(historical.withColumn("platform", lit("SINA_NEWS")))
                .unionByName(historical.withColumn("content_type", lit("comment")));
        require(RecordDeduplicator.deduplicate(separateKeys).valid().count() == 4, "Different content keys merged");
        Dataset<Row> publishedLater = historical.withColumn("publish_time", to_timestamp(lit("2026-10-02 10:00:00")))
                .withColumn("title", lit("published-later"));
        Dataset<Row> crawledLater = publishedLater.withColumn("crawl_time", to_timestamp(lit("2026-10-03 10:00:00")))
                .withColumn("title", lit("crawled-later"));
        Row ranked = RecordDeduplicator.deduplicate(historical.unionByName(publishedLater).unionByName(crawledLater))
                .valid().first();
        require("crawled-later".equals(ranked.getAs("title")), "Batch time priority changed");
        Dataset<Row> aligned = FieldNormalizer.alignToSchema(historical.drop("keywords"), historical);
        require(aligned.schema().equals(historical.schema()) && aligned.first().getAs("keywords") == null,
                "Schema alignment lost missing-field semantics");
        normalized.unpersist();
    }

    private static Row record(String id, String platform, String body, String title, String published, String crawled,
                              String approved, String hold, String corrupt) {
        return RowFactory.create(id, platform, body, title, published, crawled, approved, hold, corrupt, "关键词, ", "bad");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
