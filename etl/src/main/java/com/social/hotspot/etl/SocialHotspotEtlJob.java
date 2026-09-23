package com.social.hotspot.etl;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.apache.spark.sql.functions.*;

public class SocialHotspotEtlJob {
    public static void main(String[] args) throws Exception {
        Map<String, String> params = parseArgs(args);
        String input = required(params, "input");
        String eventId = required(params, "event-id");
        String requestedEventName = params.getOrDefault("event-name", "社交媒体热点事件传播分析");
        String eventName = "public_rss_latest".equals(eventId)
                ? "社交媒体热点事件传播分析"
                : requestedEventName;
        String batchId = params.getOrDefault("batch-id", DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()));
        String jdbcUrl = params.getOrDefault("jdbc-url", "jdbc:mysql://127.0.0.1:3306/social_hotspot_analytics?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false");
        String jdbcUser = params.getOrDefault("jdbc-user", "root");
        String jdbcPassword = params.getOrDefault("jdbc-password", System.getenv().getOrDefault("DB_PASSWORD", ""));
        String warehouseOutput = params.get("warehouse-output");

        SparkSession spark = SparkSession.builder()
                .appName("social-hotspot-etl-" + batchId)
                .config("spark.sql.session.timeZone", "Asia/Shanghai")
                .config("spark.sql.ansi.enabled", "false")
                .getOrCreate();

        LocalDateTime startedAt = LocalDateTime.now();
        upsertBatch(jdbcUrl, jdbcUser, jdbcPassword, batchId, eventId, input, "RUNNING", "ODS", startedAt, null, null, 0, 0, 0, 0);

        try {
            Dataset<Row> parsed = readInput(spark, input).cache();
            long sourceCount = parsed.count();
            long structuralDirtyCount = parsed.filter(col("_corrupt_record").isNotNull()).count();
            Dataset<Row> raw = ensureColumns(parsed, "event_id", "event_name", "url", "source_url")
                    .withColumn("event_id", coalesce(nullIfBlank(col("event_id")), lit(eventId)))
                    .withColumn("event_name", lit(eventName).substr(1, 200));
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "RawToOdsJob", "ODS", sourceCount, sourceCount, "SUCCESS", null);
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "DataQualityStructural", "DWD", sourceCount,
                    sourceCount - structuralDirtyCount, "SUCCESS", null);

            Dataset<Row> normalized = normalize(raw, batchId);
            upsertBatch(jdbcUrl, jdbcUser, jdbcPassword, batchId, eventId, input, "RUNNING", "DWD", startedAt, null, null, sourceCount, 0, 0, 0);
            long textRepairedCount = normalized.filter(col("text_normalized")).count();
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "DataQualityTextNormalization", "DWD", sourceCount,
                    textRepairedCount, "SUCCESS", null);
            Column meaningfulChars = regexp_replace(col("clean_text"), "[^\\p{IsHan}A-Za-z0-9]", "");
            Column compactChars = regexp_replace(col("clean_text"), "\\s+", "");
            Dataset<Row> qualityCandidates = normalized
                    .filter(col("event_id").isNotNull())
                    .filter(col("platform").isin("TENCENT_NEWS", "NETEASE_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER", "WEIBO"))
                    .filter(not(col("is_structurally_invalid")))
                    .filter(not(col("has_invalid_content_id")))
                    .filter(not(col("is_potentially_truncated")))
                    .filter(col("publish_time").isNotNull())
                    .filter(not(col("has_invalid_numeric")))
                    .filter(length(meaningfulChars).geq(4))
                    .filter(length(meaningfulChars).multiply(5).geq(length(compactChars)));
            long candidateCount = qualityCandidates.count();
            long dirtyCount = Math.max(0, sourceCount - candidateCount);
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "DataQualityCompletenessValidity", "DWD", sourceCount, candidateCount, "SUCCESS", null);

            WindowSpec dedupeWindow = Window.partitionBy("event_id", "platform", "content_id", "content_type")
                    .orderBy(col("publish_time").desc_nulls_last(), col("crawl_time").desc_nulls_last());
            Dataset<Row> ranked = qualityCandidates.withColumn("_dedupe_rank", row_number().over(dedupeWindow));
            long duplicateCount = ranked.filter(col("_dedupe_rank").gt(1)).count();
            Dataset<Row> valid = ranked.filter(col("_dedupe_rank").equalTo(1)).drop("_dedupe_rank", "has_invalid_numeric",
                    "is_structurally_invalid", "has_invalid_content_id", "is_potentially_truncated", "text_normalized");
            Dataset<Row> detail = valid.withColumn("hot_score", hotScoreExpr());
            long validCount = valid.count();
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "DataQualityUniqueness", "DWD", candidateCount, validCount, "SUCCESS", null);
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "OdsToDwdCleanJob", "DWD", sourceCount, validCount, "SUCCESS", null);
            if (warehouseOutput != null && !warehouseOutput.isBlank()) {
                detail.write().mode(SaveMode.Overwrite).parquet(warehouseOutput + "/dwd/dwd_social_content_detail/batch_id=" + batchId);
            }

            // Replace the committed ADS snapshot only after the input has passed
            // validation. A malformed CSV must not erase the last good result.
            upsertBatch(jdbcUrl, jdbcUser, jdbcPassword, batchId, eventId, input, "RUNNING", "DWS", startedAt, null, null, sourceCount, validCount, dirtyCount, duplicateCount);
            deleteEventAds(jdbcUrl, jdbcUser, jdbcPassword, eventId);

            Dataset<Row> heatTrend = detail.groupBy(col("event_id"), col("time_bucket"), col("platform"))
                    .agg(
                            count(lit(1)).alias("content_count"),
                            sum(col("interaction_count")).alias("interaction_count"),
                            round(sum(col("hot_score")), 2).alias("hot_score")
                    );

            Dataset<Row> interaction = detail.groupBy(col("event_id"), col("platform"))
                    .agg(
                            count(lit(1)).alias("content_count"),
                            sum(col("comment_count")).alias("comment_count"),
                            sum(col("repost_count")).alias("repost_count"),
                            sum(col("like_count")).alias("like_count"),
                            sum(col("share_count")).alias("share_count"),
                            sum(col("favorite_count")).alias("favorite_count"),
                            sum(col("view_count")).alias("view_count"),
                            round(sum(col("hot_score")), 2).alias("hot_score")
                    );

            Dataset<Row> timeline = detail.groupBy(col("event_id"), col("platform"))
                    .agg(
                            min(col("publish_time")).alias("first_publish_time"),
                            count(lit(1)).alias("content_count"),
                            round(sum(col("hot_score")), 2).alias("hot_score")
                    );
            Timestamp firstTime = (Timestamp) timeline.agg(min(col("first_publish_time")).alias("t")).first().getAs("t");
            Dataset<Row> timelineWithDelay = timeline.withColumn("delay_minutes",
                    unix_timestamp(col("first_publish_time")).minus(lit(firstTime.getTime() / 1000)).divide(60).cast("long"));

            Dataset<Row> sentiment = detail.groupBy(col("event_id"), col("time_bucket"), col("platform"), col("sentiment_label"))
                    .agg(count(lit(1)).alias("sentiment_count"));

            Dataset<Row> keywords = detail
                    .withColumn("keyword", explode(split(regexp_replace(coalesce(col("keywords"), col("clean_text")), "[,，、/|；;]+", " "), "\\s+")))
                    .withColumn("keyword", trim(col("keyword")))
                    .filter(length(col("keyword")).between(2, 12))
                    .filter(not(col("keyword").isin("这个", "一个", "我们", "你们", "他们", "以及", "但是", "然后")));
            Dataset<Row> keywordRank = keywords.groupBy(col("event_id"), col("keyword"))
                    .agg(
                            count(lit(1)).alias("word_count"),
                            first(col("platform"), true).alias("platform_top"),
                            first(col("sentiment_label"), true).alias("sentiment_top")
                    )
                    .orderBy(col("word_count").desc());

            WindowSpec rankWindow = Window.partitionBy("event_id").orderBy(col("hot_score").desc());
            Dataset<Row> contentRank = detail
                    .withColumn("rank_no", row_number().over(rankWindow))
                    .select("event_id", "rank_no", "platform", "content_id", "parent_content_id", "content_type", "title", "clean_text",
                            "author_id", "author_name", "publish_time", "crawl_time", "keywords", "category", "like_count",
                            "favorite_count", "comment_count", "forward_count", "repost_count", "share_count", "view_count",
                            "location", "user_age_group", "user_gender", "sentiment_label", "hot_score", "source_url", "image_url");

            Dataset<Row> overview = detail.groupBy(col("event_id"), col("event_name"))
                    .agg(
                            count(lit(1)).alias("content_count"),
                            countDistinct(when(col("author_id").isNotNull(), col("author_id"))).alias("user_count"),
                            countDistinct(col("platform")).alias("platform_count"),
                            sum(col("comment_count")).alias("comment_count"),
                            sum(col("repost_count")).alias("repost_count"),
                            sum(col("share_count")).alias("share_count"),
                            sum(col("like_count")).alias("like_count"),
                            sum(col("view_count")).alias("view_count"),
                            sum(when(col("sentiment_label").equalTo("positive"), 1).otherwise(0)).alias("positive_count"),
                            sum(when(col("sentiment_label").equalTo("neutral"), 1).otherwise(0)).alias("neutral_count"),
                            sum(when(col("sentiment_label").equalTo("negative"), 1).otherwise(0)).alias("negative_count"),
                            round(sum(col("hot_score")), 2).alias("hot_score"),
                            max(col("publish_time")).alias("peak_time")
                    );

            Dataset<Row> highFreqUsers = detail.groupBy(col("event_id"), col("time_bucket"), col("platform"), col("author_id"))
                    .agg(count(lit(1)).alias("user_content_count"))
                    .filter(col("user_content_count").gt(3))
                    .groupBy(col("event_id"), col("time_bucket"), col("platform"))
                    .agg(countDistinct(col("author_id")).alias("high_freq_user_count"));

            Dataset<Row> noise = detail.groupBy(col("event_id"), col("time_bucket"), col("platform"))
                    .agg(
                            sum(when(col("is_noise").equalTo(true), 1).otherwise(0)).alias("noise_count"),
                            count(lit(1)).alias("content_count")
                    )
                    .join(highFreqUsers, new String[]{"event_id", "time_bucket", "platform"}, "left")
                    .withColumn("high_freq_user_count", coalesce(col("high_freq_user_count"), lit(0)))
                    .withColumn("duplicate_count", lit(duplicateCount));

            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "DwdToDwsAggregateJob", "DWS", validCount, heatTrend.count(), "SUCCESS", null);
            upsertBatch(jdbcUrl, jdbcUser, jdbcPassword, batchId, eventId, input, "RUNNING", "ADS", startedAt, null, null, sourceCount, validCount, dirtyCount, duplicateCount);
            writeJdbc(overview, jdbcUrl, jdbcUser, jdbcPassword, "ads_event_overview");
            writeJdbc(heatTrend, jdbcUrl, jdbcUser, jdbcPassword, "ads_event_heat_trend");
            writeJdbc(timelineWithDelay, jdbcUrl, jdbcUser, jdbcPassword, "ads_platform_spread_timeline");
            writeJdbc(interaction, jdbcUrl, jdbcUser, jdbcPassword, "ads_interaction_summary");
            writeJdbc(keywordRank.limit(200), jdbcUrl, jdbcUser, jdbcPassword, "ads_keyword_rank");
            writeJdbc(sentiment, jdbcUrl, jdbcUser, jdbcPassword, "ads_sentiment_trend");
            writeJdbc(contentRank, jdbcUrl, jdbcUser, jdbcPassword, "ads_content_hot_rank");
            writeJdbc(noise, jdbcUrl, jdbcUser, jdbcPassword, "ads_noise_summary");
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "DwsToAdsAndMysqlSyncJob", "ADS", validCount, 8, "SUCCESS", null);

            upsertBatch(jdbcUrl, jdbcUser, jdbcPassword, batchId, eventId, input, "RUNNING", "MYSQL_SYNC", startedAt, null, null, sourceCount, validCount, dirtyCount, duplicateCount);
            upsertEvent(jdbcUrl, jdbcUser, jdbcPassword, eventId, eventName, detail);
            upsertBatch(jdbcUrl, jdbcUser, jdbcPassword, batchId, eventId, input, "SUCCESS", "MYSQL_SYNC",
                    startedAt, LocalDateTime.now(), null, sourceCount, validCount, dirtyCount, duplicateCount);
        } catch (Exception ex) {
            upsertBatch(jdbcUrl, jdbcUser, jdbcPassword, batchId, eventId, input, "FAILED", "FAILED",
                    startedAt, LocalDateTime.now(), truncate(ex.getMessage(), 1000), 0, 0, 0, 0);
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "FullEtlJob", "FAILED", 0, 0, "FAILED", truncate(ex.getMessage(), 1000));
            throw ex;
        } finally {
            spark.stop();
        }
    }

    private static Dataset<Row> readInput(SparkSession spark, String input) {
        if (input.toLowerCase().endsWith(".json") || input.toLowerCase().endsWith(".jsonl")) {
            return spark.read().option("multiLine", "false").json(input);
        }
        String[] columns = {"event_id", "event_name", "platform", "content_id", "parent_content_id", "content_type",
                "title", "content_text", "author_id", "author_name", "publish_time", "crawl_time", "like_count",
                "comment_count", "repost_count", "share_count", "favorite_count", "view_count", "hot_rank", "location",
                "user_age_group", "user_gender", "keywords", "source_url", "image_url", "category"};
        StructType schema = new StructType();
        for (String column : columns) {
            schema = schema.add(column, DataTypes.StringType, true);
        }
        schema = schema.add("_corrupt_record", DataTypes.StringType, true);
        return spark.read().schema(schema).option("header", "true").option("multiLine", "true")
                .option("escape", "\"").option("mode", "PERMISSIVE")
                .option("columnNameOfCorruptRecord", "_corrupt_record").csv(input);
    }
    private static Dataset<Row> ensureColumns(Dataset<Row> data, String... names) {
        Dataset<Row> result = data;
        for (String name : names) {
            if (!hasColumn(result, name)) {
                result = result.withColumn(name, lit(null));
            }
        }
        return result;
    }

    private static Dataset<Row> normalize(Dataset<Row> raw, String batchId) {
        return raw.select(
                        safeCol(raw, "event_id"),
                        safeCol(raw, "event_name"),
                        normalizePlatform(safeCol(raw, "platform")).alias("platform"),
                        coalesce(nullIfBlank(safeCol(raw, "content_id")), sha2(concat_ws("||", safeCol(raw, "platform"), safeCol(raw, "content_text"), safeCol(raw, "publish_time")), 256)).substr(1, 128).alias("content_id"),
                        coalesce(nullIfBlank(safeCol(raw, "parent_content_id")), lit("")).substr(1, 128).alias("parent_content_id"),
                        normalizeContentType(safeCol(raw, "content_type")).alias("content_type"),
                        cleanText(coalesce(nullIfBlank(safeCol(raw, "title")), lit(""))).substr(1, 300).alias("title"),
                        cleanText(coalesce(nullIfBlank(safeCol(raw, "content_text")), nullIfBlank(safeCol(raw, "title")), lit(""))).alias("clean_text"),
                        nullIfBlank(safeCol(raw, "author_id")).substr(1, 128).alias("author_id"),
                        cleanText(coalesce(nullIfBlank(safeCol(raw, "author_name")), lit(""))).substr(1, 100).alias("author_name"),
                        parseTimestamp(safeCol(raw, "publish_time")).alias("publish_time"),
                        coalesce(parseTimestamp(safeCol(raw, "crawl_time")), parseTimestamp(safeCol(raw, "publish_time"))).alias("crawl_time"),
                        numberCol(raw, "like_count").alias("like_count"),
                        numberCol(raw, "comment_count").alias("comment_count"),
                        numberCol(raw, "repost_count").alias("repost_count"),
                        numberCol(raw, "share_count").alias("share_count"),
                        numberCol(raw, "favorite_count").alias("favorite_count"),
                        numberCol(raw, "view_count").alias("view_count"),
                        numberCol(raw, "hot_rank").cast("int").alias("hot_rank"),
                        coalesce(nullIfBlank(safeCol(raw, "location")), lit("")).substr(1, 100).alias("location"),
                        coalesce(nullIfBlank(safeCol(raw, "user_age_group")), lit("")).substr(1, 50).alias("user_age_group"),
                        coalesce(nullIfBlank(safeCol(raw, "user_gender")), lit("")).substr(1, 30).alias("user_gender"),
                        cleanText(coalesce(nullIfBlank(safeCol(raw, "keywords")), lit(""))).substr(1, 1000).alias("keywords"),
                        normalizeUrl(coalesce(nullIfBlank(safeCol(raw, "source_url")), nullIfBlank(safeCol(raw, "url")))).alias("source_url"),
                        coalesce(nullIfBlank(safeCol(raw, "image_url")), lit("")).substr(1, 800).alias("image_url"),
                        cleanText(coalesce(nullIfBlank(safeCol(raw, "category")), lit(""))).substr(1, 100).alias("category"),
                        hasInvalidNumeric(raw).alias("has_invalid_numeric"),
                        safeCol(raw, "_corrupt_record").isNotNull().alias("is_structurally_invalid"),
                        not(coalesce(nullIfBlank(safeCol(raw, "content_id")), lit("")).rlike("^[A-Za-z0-9_-]{3,128}$")).alias("has_invalid_content_id"),
                        hasPotentialTruncation(raw).alias("is_potentially_truncated"),
                        requiresTextNormalization(raw).alias("text_normalized")
                )
                .withColumn("time_bucket", date_trunc("hour", col("publish_time")))
                .withColumn("interaction_count", col("like_count").plus(col("comment_count")).plus(col("repost_count"))
                        .plus(col("share_count")).plus(col("favorite_count")).plus(col("view_count").divide(100)))
                .withColumn("forward_count", col("repost_count"))
                .withColumn("sentiment_label", sentimentExpr())
                .withColumn("is_noise", col("clean_text").rlike("(?i)(领取|福利|点击链接|加群|广告|刷屏)"))
                .withColumn("batch_id", lit(batchId));
    }

    private static Column cleanText(Column source) {
        Column cleaned = regexp_replace(source, "[\\p{Cc}\\p{Cf}]", "");
        cleaned = regexp_replace(cleaned, "\\uFFFD|\\u951f\\u65a4\\u62f7|\\u00C3\\u00A9", "");
        cleaned = regexp_replace(cleaned, "(?i)https?://\\S+", " ");
        cleaned = regexp_replace(cleaned, "[A-Za-z]{24,}", " ");
        cleaned = regexp_replace(cleaned, "([!\\uFF01?\\uFF1F.\\u3002,\\uFF0C\\u3001;\\uFF1B:\\uFF1A~\\uFF5E_\\-])\\1{2,}", "$1");
        return trim(regexp_replace(cleaned, "\\s+", " ")).substr(1, 1000);
    }
    private static Column hasPotentialTruncation(Dataset<Row> data) {
        Column title = trim(coalesce(safeCol(data, "title"), lit("")));
        Column keywords = trim(coalesce(safeCol(data, "keywords"), lit("")));
        return title.rlike(".*(\\.\\.\\.|\\u2026)$").or(keywords.rlike(".*[,\\uFF0C\\u3001;\\uFF1B|/]$"));
    }

    private static Column requiresTextNormalization(Dataset<Row> data) {
        String[] names = {"title", "content_text", "author_name", "keywords", "category"};
        Column requiresNormalization = lit(false);
        for (String name : names) {
            Column value = coalesce(safeCol(data, name), lit(""));
            requiresNormalization = requiresNormalization.or(value.rlike("[\\p{Cc}\\p{Cf}]|\\uFFFD|\\u951f\\u65a4\\u62f7|\\u00C3\\u00A9|\\s{2,}"));
        }
        return requiresNormalization;
    }
    private static Column normalizePlatform(Column value) {
        Column normalized = upper(regexp_replace(trim(coalesce(value, lit("UNKNOWN"))), "[\\s-]+", "_"));
        return when(normalized.isin("TENCENTNEWS", "TENCENT_NEWS", "TENCENT"), "TENCENT_NEWS")
                .when(normalized.isin("NETEASENEWS", "NETEASE_NEWS", "NETEASE"), "NETEASE_NEWS")
                .when(normalized.isin("SOHUNEWS", "SOHU_NEWS", "SOHU"), "SOHU_NEWS")
                .when(normalized.isin("SINANEWS", "SINA_NEWS", "SINA"), "SINA_NEWS")
                .when(normalized.isin("THEPAPER", "THE_PAPER", "PAPER"), "THE_PAPER")
                .when(normalized.isin("WEIBO", "SINA_WEIBO"), "WEIBO")
                .otherwise(normalized);
    }

    private static Column normalizeContentType(Column value) {
        Column normalized = lower(trim(coalesce(value, lit("post"))));
        return when(normalized.isin("news", "article", "post", "video", "image", "comment"), normalized)
                .otherwise("post");
    }

    private static Column normalizeUrl(Column value) {
        return when(value.rlike("(?i)^https?://\\S+$"), trim(value)).otherwise(lit(""));
    }

    private static Column hasInvalidNumeric(Dataset<Row> data) {
        String[] names = {"like_count", "comment_count", "repost_count", "share_count", "favorite_count", "view_count", "hot_rank"};
        Column invalid = lit(false);
        for (String name : names) {
            Column value = trim(safeCol(data, name));
            Column digits = regexp_replace(value, "[+,]", "");
            Column validNumber = value.rlike("^\\+?\\d[\\d,]*$").and(length(digits).leq(8));
            invalid = invalid.or(value.isNotNull().and(value.notEqual("")).and(not(validNumber)));
        }
        return invalid;
    }

    private static Column safeCol(Dataset<Row> data, String name) {
        return hasColumn(data, name) ? col(name) : lit(null);
    }

    private static boolean hasColumn(Dataset<Row> data, String name) {
        for (String column : data.columns()) {
            if (column.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static Column nullIfBlank(Column column) {
        return when(trim(column).equalTo(""), null).otherwise(trim(column));
    }

    private static Column numberCol(Dataset<Row> data, String name) {
        return coalesce(regexp_replace(safeCol(data, name), "[^0-9]", "").cast("long"), lit(0L));
    }

    private static Column parseTimestamp(Column value) {
        Column normalized = trim(value);
        return coalesce(
                to_timestamp(normalized, "yyyy-MM-dd HH:mm:ss"),
                to_timestamp(normalized, "yyyy-MM-dd HH:mm"),
                to_timestamp(normalized, "yyyy/MM/dd HH:mm:ss"),
                to_timestamp(normalized, "yyyy/M/d H:mm:ss"),
                to_timestamp(normalized, "yyyy/M/d H:mm"),
                to_timestamp(normalized, "yyyy-MM-dd'T'HH:mm:ss")
        );
    }

    private static Column sentimentExpr() {
        Column text = lower(col("clean_text"));
        return when(text.rlike("支持|精彩|夺冠|开心|燃|厉害|喜欢|正能量|祝贺|历史|respect"), "positive")
                .when(text.rlike("争议|质疑|不满|失望|愤怒|离谱|黑幕|造假|负面|骂"), "negative")
                .otherwise("neutral");
    }

    private static Column hotScoreExpr() {
        Column rankBoost = when(col("hot_rank").gt(0), greatest(lit(0.0), lit(101.0).minus(col("hot_rank")))).otherwise(lit(1.0));
        Column engagement = col("like_count").plus(col("comment_count").multiply(2))
                .plus(col("repost_count").multiply(3)).plus(col("share_count").multiply(2))
                .plus(col("favorite_count")).plus(col("view_count").divide(1000));
        return rankBoost.plus(log1p(greatest(engagement, lit(0))).multiply(5));
    }

    private static void writeJdbc(Dataset<Row> data, String url, String user, String password, String table) {
        Properties properties = new Properties();
        properties.put("user", user);
        properties.put("password", password);
        properties.put("driver", "com.mysql.cj.jdbc.Driver");
        data.write().mode(SaveMode.Append).jdbc(url, table, properties);
    }

    private static void deleteEventAds(String url, String user, String password, String eventId) throws Exception {
        String[] tables = {
                "ads_event_overview", "ads_event_heat_trend", "ads_platform_spread_timeline",
                "ads_interaction_summary", "ads_content_hot_rank", "ads_keyword_rank",
                "ads_sentiment_trend", "ads_noise_summary"
        };
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            for (String table : tables) {
                try (PreparedStatement ps = connection.prepareStatement("delete from " + table + " where event_id = ?")) {
                    ps.setString(1, eventId);
                    ps.executeUpdate();
                }
            }
        }
    }

    private static void upsertEvent(String url, String user, String password, String eventId, String eventName, Dataset<Row> detail) throws Exception {
        Row row = detail.agg(min(col("publish_time")).alias("start_time"), max(col("publish_time")).alias("end_time")).first();
        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = connection.prepareStatement("""
                     insert into event_info(event_id, event_name, description, start_time, end_time, status)
                     values(?, ?, ?, ?, ?, 'ACTIVE')
                     on duplicate key update event_name=values(event_name), start_time=values(start_time), end_time=values(end_time), updated_at=current_timestamp
                     """)) {
            ps.setString(1, eventId);
            ps.setString(2, eventName);
            ps.setString(3, "由公开数据源真实采集后经 Spark ETL 计算生成");
            ps.setTimestamp(4, row.getAs("start_time"));
            ps.setTimestamp(5, row.getAs("end_time"));
            ps.executeUpdate();
        }
    }

    private static void upsertBatch(String url, String user, String password, String batchId, String eventId, String sourcePath,
                                    String status, String stage, LocalDateTime startedAt, LocalDateTime finishedAt, String error,
                                    long sourceCount, long validCount, long dirtyCount, long duplicateCount) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = connection.prepareStatement("""
                     insert into etl_batch(batch_id, event_id, source_path, source_count, valid_count, dirty_count, duplicate_count, status, current_stage, started_at, finished_at, error_message)
                     values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     on duplicate key update source_count=values(source_count), valid_count=values(valid_count), dirty_count=values(dirty_count),
                     duplicate_count=values(duplicate_count), status=values(status), current_stage=values(current_stage), finished_at=values(finished_at), error_message=values(error_message), updated_at=current_timestamp
                     """)) {
            ps.setString(1, batchId);
            ps.setString(2, eventId);
            ps.setString(3, sourcePath);
            ps.setLong(4, sourceCount);
            ps.setLong(5, validCount);
            ps.setLong(6, dirtyCount);
            ps.setLong(7, duplicateCount);
            ps.setString(8, status);
            ps.setString(9, stage);
            ps.setTimestamp(10, startedAt == null ? null : Timestamp.valueOf(startedAt));
            ps.setTimestamp(11, finishedAt == null ? null : Timestamp.valueOf(finishedAt));
            ps.setString(12, truncate(error, 1000));
            ps.executeUpdate();
        }
    }

    private static void logTask(String url, String user, String password, String batchId, String taskName, String stage,
                                long input, long output, String status, String error) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = connection.prepareStatement("""
                     insert into etl_task_log(batch_id, task_name, task_stage, input_count, output_count, status, started_at, finished_at, error_message)
                     values(?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, batchId);
            ps.setString(2, taskName);
            ps.setString(3, stage);
            ps.setLong(4, input);
            ps.setLong(5, output);
            ps.setString(6, status);
            ps.setTimestamp(7, Timestamp.valueOf(now));
            ps.setTimestamp(8, Timestamp.valueOf(now));
            ps.setString(9, truncate(error, 1000));
            ps.executeUpdate();
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                String value = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
                params.put(key, value);
            }
        }
        return params;
    }

    private static String required(Map<String, String> params, String key) {
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument --" + key);
        }
        return value;
    }
}
