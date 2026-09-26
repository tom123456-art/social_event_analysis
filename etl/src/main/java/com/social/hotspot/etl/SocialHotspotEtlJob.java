package com.social.hotspot.etl;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.expressions.UserDefinedFunction;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import org.apache.spark.storage.StorageLevel;

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
    private static final String[] INTERACTION_FIELDS = {"like_count", "comment_count", "repost_count", "favorite_count"};
    // A field must be non-zero in at least 1% of one platform's records before it participates in that platform''s score.
    private static final double SIGNAL_COVERAGE_THRESHOLD = 0.01D;
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
        String sentimentModel = params.getOrDefault("sentiment-model",
                System.getenv().getOrDefault("HANLP_SENTIMENT_MODEL", "weibo-sentiment.bin"));
        String sentimentModelName = fileName(sentimentModel);
        double sentimentNeutralThreshold = doubleParam(params, "sentiment-neutral-threshold", 0.62D);
        double sentimentNeutralMargin = doubleParam(params, "sentiment-neutral-margin", 0.15D);

        SparkSession spark = SparkSession.builder()
                .appName("social-hotspot-etl-" + batchId)
                .config("spark.sql.session.timeZone", "Asia/Shanghai")
                .config("spark.sql.ansi.enabled", "false")
                .getOrCreate();
        if (new java.io.File(sentimentModel).isFile()) {
            spark.sparkContext().addFile(sentimentModel);
        }
        UserDefinedFunction hanlpSentiment = udf(
                (String text) -> HanlpSentimentAnalyzer.analyzeEncoded(text, sentimentModelName,
                        sentimentNeutralThreshold, sentimentNeutralMargin),
                DataTypes.StringType
        );

        LocalDateTime startedAt = LocalDateTime.now();
        upsertBatch(jdbcUrl, jdbcUser, jdbcPassword, batchId, eventId, input, "RUNNING", "ODS", startedAt, null, null, 0, 0, 0, 0);

        try {
            Dataset<Row> parsed = ensureColumns(readInput(spark, input), "_corrupt_record").cache();
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
            // Optional interaction fields and keyword punctuation are repaired during normalization.
            // They should not invalidate otherwise usable news content.
            Dataset<Row> qualityCandidates = normalized
                    .filter(col("event_id").isNotNull())
                    .filter(col("platform").isin("TENCENT_NEWS", "NETEASE_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER", "WEIBO"))
                    .filter(not(col("is_structurally_invalid")))
                    .filter(col("publish_time").isNotNull())
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
            Dataset<Row> sentimentAnalyzed = applyHanlpSentiment(valid, hanlpSentiment);
            Dataset<Row> detail = buildPlatformRelativeHeat(sentimentAnalyzed).persist(StorageLevel.MEMORY_AND_DISK());
            long validCount = detail.count();
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
                            round(sum(col("hot_score")), 2).alias("hot_score"),
                            round(avg(col("platform_heat_index")), 2).alias("avg_heat_index")
                    );

            Dataset<Row> platformDailyHeat = detail
                    .filter(col("platform").isin("TENCENT_NEWS", "NETEASE_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER"))
                    .withColumn("heat_day", to_date(col("publish_time")))
                    .groupBy(col("event_id"), col("platform"), col("heat_day"))
                    .agg(
                            count(lit(1)).alias("content_count"),
                            round(avg(col("platform_heat_index")), 2).alias("average_heat_index"),
                            round(max(col("platform_heat_index")), 2).alias("peak_content_heat_index"),
                            sum(when(col("platform_heat_index").geq(80), 1).otherwise(0)).alias("high_heat_content_count"),
                            first(col("signal_mode"), true).alias("heat_algorithm")
                    )
                    .select(col("event_id"), col("platform"), col("heat_day").alias("time_bucket"), col("content_count"),
                            col("average_heat_index"), col("peak_content_heat_index"), col("high_heat_content_count"), col("heat_algorithm"));
            Dataset<Row> interaction = detail.groupBy(col("event_id"), col("platform"))
                    .agg(
                            count(lit(1)).alias("content_count"),
                            sum(col("comment_count")).alias("comment_count"),
                            sum(col("repost_count")).alias("repost_count"),
                            sum(col("like_count")).alias("like_count"),
                            sum(col("favorite_count")).alias("favorite_count"),
                            sum(col("view_count")).alias("view_count"),
                            round(sum(col("hot_score")), 2).alias("hot_score")
                    );

            Dataset<Row> timeline = detail.groupBy(col("event_id"), col("platform"))
                    .agg(
                            min(col("publish_time")).alias("first_publish_time"),
                            count(lit(1)).alias("content_count"),
                            round(sum(col("hot_score")), 2).alias("hot_score"),
                            round(avg(col("platform_heat_index")), 2).alias("avg_heat_index"),
                            round(max(col("platform_heat_index")), 2).alias("max_heat_index"),
                            sum(when(col("platform_heat_index").geq(80), 1).otherwise(0)).alias("high_heat_count")
                    );
            Dataset<Row> platformCategoryBase = detail
                    .filter(col("platform").isin("TENCENT_NEWS", "NETEASE_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER"))
                    .withColumn("category_label", when(length(trim(col("category"))).gt(0), trim(col("category"))).otherwise(lit("未分类")))
                    .withColumn("category_day", to_date(col("publish_time")));
            Dataset<Row> platformDailyTotal = platformCategoryBase.groupBy(col("event_id"), col("platform"), col("category_day"))
                    .agg(count(lit(1)).alias("platform_content_count"));
            Dataset<Row> platformCategoryHeat = platformCategoryBase
                    .groupBy(col("event_id"), col("platform"), col("category_day"), col("category_label"))
                    .agg(
                            count(lit(1)).alias("content_count"),
                            round(avg(col("platform_heat_index")), 2).alias("average_relative_heat_index"),
                            first(col("signal_mode"), true).alias("signal_mode")
                    )
                    .join(platformDailyTotal, new String[]{"event_id", "platform", "category_day"}, "inner")
                    .withColumn("coverage_share", round(col("content_count").cast("double").divide(col("platform_content_count")).multiply(100), 2))
                    .withColumn("composite_attention_index", round(
                            sqrt(col("coverage_share").multiply(coalesce(col("average_relative_heat_index"), lit(0D)))), 2))
                    .select(
                            col("event_id"), col("platform"), col("category_day").alias("time_bucket"), col("category_label").alias("category"),
                            col("content_count"), col("platform_content_count"), col("coverage_share"),
                            col("average_relative_heat_index"), col("composite_attention_index"), col("signal_mode")
                    );
            Timestamp firstTime = (Timestamp) timeline.agg(min(col("first_publish_time")).alias("t")).first().getAs("t");
            if (firstTime == null) {
                throw new IllegalStateException("有效记录的 publish_time 和 crawl_time 均无法解析，无法生成时间趋势");
            }
            WindowSpec platformPeakWindow = Window.partitionBy("event_id", "platform")
                    .orderBy(col("avg_heat_index").desc_nulls_last(), col("time_bucket").asc());
            Dataset<Row> platformPeak = heatTrend.withColumn("_peak_rank", row_number().over(platformPeakWindow))
                    .filter(col("_peak_rank").equalTo(1))
                    .select(col("event_id"), col("platform"), col("time_bucket").alias("peak_time"));
            Dataset<Row> timelineWithDelay = timeline.withColumn("delay_minutes",
                    unix_timestamp(col("first_publish_time")).minus(lit(firstTime.getTime() / 1000)).divide(60).cast("long"))
                    .join(platformPeak, new String[]{"event_id", "platform"}, "left");

            Dataset<Row> sentiment = detail.groupBy(col("event_id"), col("time_bucket"), col("platform"), col("sentiment_label"))
                    .agg(count(lit(1)).alias("sentiment_count"));
            Dataset<Row> sentimentResult = detail
                    .filter(col("platform").equalTo("WEIBO"))
                    .select(
                            col("event_id"), col("content_id"), col("parent_content_id"), col("platform"),
                            col("publish_time"), col("clean_text").alias("content_text"),
                            col("sentiment_label"), col("sentiment_positive_score"),
                            col("sentiment_neutral_score"), col("sentiment_negative_score"),
                            greatest(col("sentiment_positive_score"), col("sentiment_neutral_score"),
                                    col("sentiment_negative_score")).alias("confidence"),
                            lit("HANLP_NAIVE_BAYES").alias("analysis_method"),
                            lit(sentimentModelName).substr(1, 128).alias("model_version"),
                            lit(batchId).alias("batch_id"), current_timestamp().alias("analyzed_at")
                    );

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
                            "favorite_count", "comment_count", "forward_count", "repost_count", "view_count",
                            "sentiment_label", "hot_score", "platform_heat_index", "source_url", "image_url");

            Dataset<Row> topicTagged = detail
                    .withColumn("topic_seed", topicSeedExpr())
                    .filter(length(col("topic_seed")).geq(2))
                    .withColumn("topic_id", sha2(concat_ws("||", col("event_id"), col("category"), col("topic_seed")), 256).substr(1, 64))
                    .withColumn("topic_day", to_date(col("publish_time")));
            topicTagged = topicTagged.persist(StorageLevel.MEMORY_AND_DISK());
            Dataset<Row> topicNames = topicTagged.groupBy(col("event_id"), col("topic_id"))
                    .agg(first(col("title"), true).alias("topic_name"), first(col("category"), true).alias("category"));
            Dataset<Row> topicTrendBase = topicTagged.groupBy(col("event_id"), col("topic_id"), col("topic_day"))
                    .agg(count(lit(1)).alias("content_count"), countDistinct(col("platform")).alias("platform_count"));
            WindowSpec topicBaselineWindow = Window.partitionBy("event_id", "topic_id")
                    .orderBy(col("topic_day").asc()).rowsBetween(-7, -1);
            Dataset<Row> topicTrend = topicTrendBase
                    .withColumn("_baseline_count", avg(col("content_count")).over(topicBaselineWindow))
                    .withColumn("burst_index", round(when(col("_baseline_count").isNull(), least(lit(500.0), log1p(col("content_count")).multiply(100)))
                            .otherwise(least(lit(500.0), col("content_count").divide(col("_baseline_count").plus(1)).multiply(100))), 2))
                    .drop("_baseline_count")
                    .join(topicNames, new String[]{"event_id", "topic_id"}, "left")
                    .select("event_id", "topic_id", "topic_name", "category", "topic_day", "content_count", "platform_count", "burst_index")
                    .withColumnRenamed("topic_day", "time_bucket");
            Dataset<Row> topicTotals = topicTagged.groupBy(col("event_id"), col("topic_id"))
                    .agg(min(col("publish_time")).alias("first_publish_time"),
                            max(col("publish_time")).alias("latest_publish_time"),
                            count(lit(1)).alias("content_count"),
                            countDistinct(col("platform")).alias("platform_count"))
                    .withColumn("duration_days", datediff(to_date(col("latest_publish_time")), to_date(col("first_publish_time"))).plus(1));
            WindowSpec topicPeakWindow = Window.partitionBy("event_id", "topic_id")
                    .orderBy(col("burst_index").desc(), col("content_count").desc(), col("time_bucket").asc());
            Dataset<Row> topicPeak = topicTrend.withColumn("_topic_peak_rank", row_number().over(topicPeakWindow))
                    .filter(col("_topic_peak_rank").equalTo(1))
                    .select(col("event_id"), col("topic_id"), col("time_bucket").alias("peak_time"),
                            col("content_count").alias("peak_daily_count"), col("burst_index").alias("peak_burst_index"));
            Dataset<Row> topicLatest = topicTrend.groupBy(col("event_id"), col("topic_id"))
                    .agg(max(col("time_bucket")).alias("_latest_day"));
            Dataset<Row> topicWindows = topicTrend.join(topicLatest, new String[]{"event_id", "topic_id"})
                    .groupBy(col("event_id"), col("topic_id"))
                    .agg(sum(when(datediff(col("_latest_day"), col("time_bucket")).lt(3), col("content_count")).otherwise(0)).alias("_recent_count"),
                            sum(when(datediff(col("_latest_day"), col("time_bucket")).between(3, 5), col("content_count")).otherwise(0)).alias("_previous_count"));
            Dataset<Row> topicSummary = topicTotals.join(topicPeak, new String[]{"event_id", "topic_id"})
                    .join(topicNames, new String[]{"event_id", "topic_id"})
                    .join(topicWindows, new String[]{"event_id", "topic_id"})
                    .withColumn("current_stage", when(col("_previous_count").gt(0).and(col("_recent_count").gt(col("_previous_count").multiply(1.3))), "上升中")
                            .when(col("_previous_count").gt(0).and(col("_recent_count").lt(col("_previous_count").multiply(0.7))), "回落中")
                            .when(col("duration_days").geq(7), "持续关注")
                            .otherwise("短期集中"))
                    .filter(col("content_count").geq(3).and(col("duration_days").geq(2).or(col("platform_count").geq(2))))
                    .select("event_id", "topic_id", "topic_name", "category", "first_publish_time", "peak_time", "latest_publish_time",
                            "content_count", "platform_count", "duration_days", "peak_daily_count", "peak_burst_index", "current_stage");
            Dataset<Row> qualifiedTopicIds = topicSummary.select("event_id", "topic_id");
            topicTrend = topicTrend.join(qualifiedTopicIds, new String[]{"event_id", "topic_id"}, "inner");
            Dataset<Row> qualifiedTopicContent = topicTagged.join(qualifiedTopicIds, new String[]{"event_id", "topic_id"}, "inner");
            WindowSpec firstContentWindow = Window.partitionBy("event_id", "topic_id").orderBy(col("publish_time").asc());
            WindowSpec latestContentWindow = Window.partitionBy("event_id", "topic_id").orderBy(col("publish_time").desc());
            WindowSpec peakContentWindow = Window.partitionBy("event_id", "topic_id").orderBy(col("publish_time").asc());
            Dataset<Row> firstTopicContent = qualifiedTopicContent.withColumn("_content_rank", row_number().over(firstContentWindow))
                    .filter(col("_content_rank").equalTo(1)).withColumn("event_role", lit("首次报道"));
            Dataset<Row> latestTopicContent = qualifiedTopicContent.withColumn("_content_rank", row_number().over(latestContentWindow))
                    .filter(col("_content_rank").equalTo(1)).withColumn("event_role", lit("最新进展"));
            Dataset<Row> peakTopicContent = qualifiedTopicContent.join(topicPeak.select("event_id", "topic_id", "peak_time"),
                            new String[]{"event_id", "topic_id"}, "inner")
                    .filter(to_date(col("publish_time")).equalTo(col("peak_time")))
                    .withColumn("_content_rank", row_number().over(peakContentWindow))
                    .filter(col("_content_rank").equalTo(1)).withColumn("event_role", lit("峰值报道"));
            String[] topicContentColumns = {"event_id", "topic_id", "event_role", "publish_time", "platform", "content_id", "title", "clean_text", "source_url"};
            Dataset<Row> topicKeyContents = firstTopicContent.selectExpr(topicContentColumns)
                    .unionByName(peakTopicContent.selectExpr(topicContentColumns))
                    .unionByName(latestTopicContent.selectExpr(topicContentColumns));

            Dataset<Row> overview = detail.groupBy(col("event_id"), col("event_name"))
                    .agg(
                            count(lit(1)).alias("content_count"),
                            countDistinct(when(col("author_id").isNotNull(), col("author_id"))).alias("user_count"),
                            countDistinct(col("platform")).alias("platform_count"),
                            sum(col("comment_count")).alias("comment_count"),
                            sum(col("repost_count")).alias("repost_count"),
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
            writeJdbc(sentimentResult, jdbcUrl, jdbcUser, jdbcPassword, "dwd_weibo_sentiment_result");
            writeJdbc(contentRank, jdbcUrl, jdbcUser, jdbcPassword, "ads_content_hot_rank");
            writeJdbc(noise, jdbcUrl, jdbcUser, jdbcPassword, "ads_noise_summary");
            writeJdbc(topicTrend, jdbcUrl, jdbcUser, jdbcPassword, "ads_topic_trend");
            writeJdbc(topicSummary, jdbcUrl, jdbcUser, jdbcPassword, "ads_topic_summary");
            writeJdbc(topicKeyContents, jdbcUrl, jdbcUser, jdbcPassword, "ads_topic_key_content");
            writeJdbc(platformCategoryHeat, jdbcUrl, jdbcUser, jdbcPassword, "ads_platform_category_heat");
            writeJdbc(platformDailyHeat, jdbcUrl, jdbcUser, jdbcPassword, "ads_platform_daily_heat");
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "DwsToAdsAndMysqlSyncJob", "ADS", validCount, 14, "SUCCESS", null);

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
        return spark.read().option("header", "true").option("inferSchema", "false").option("multiLine", "true")
                .option("escape", Character.toString((char) 34)).option("mode", "PERMISSIVE")
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
                        coalesce(parseTimestamp(safeCol(raw, "publish_time")), parseTimestamp(safeCol(raw, "crawl_time"))).alias("publish_time"),
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
                        normalizeCategory(safeCol(raw, "category")).alias("category"),
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
                .withColumn("sentiment_label", lit("neutral"))
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

    private static Column normalizeCategory(Column value) {
        Column original = trim(cleanText(coalesce(value, lit(""))));
        Column lowerCase = lower(original);
        return when(lowerCase.isin("", "null", "undefined", "none", "n/a", "nan", "unknown", "未知", "其他"), "未分类")
                .when(original.rlike("^\\d+$"), "未分类")
                .when(original.isin("娱乐", "影视", "音乐"), "文娱")
                .when(original.isin("社会新闻", "民生"), "社会")
                .when(original.isin("综合类", "新闻"), "综合")
                .otherwise(original.substr(1, 100));
    }
    private static Column normalizeUrl(Column value) {
        return when(value.rlike("(?i)^https?://\\S+$"), trim(value)).otherwise(lit(""));
    }

    private static Column hasInvalidNumeric(Dataset<Row> data) {
        String[] names = {"like_count", "comment_count", "repost_count", "share_count", "favorite_count", "view_count", "hot_rank"};
        Column invalid = lit(false);
        for (String name : names) {
            Column value = trim(safeCol(data, name));
            // CSV exports commonly serialize integral interaction values as "2014.0".
            // Treat those as valid numeric values rather than discarding otherwise usable news.
            Column digits = regexp_replace(value, "[+,.]", "");
            Column validNumber = value.rlike("^\\+?\\d[\\d,]*(?:\\.\\d+)?$").and(length(digits).leq(8));
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
        Column normalized = regexp_replace(trim(coalesce(safeCol(data, name), lit(""))), ",", "");
        return coalesce(round(normalized.cast("double"), 0).cast("long"), lit(0L));
    }

    private static Column parseTimestamp(Column value) {
        Column normalized = trim(regexp_replace(coalesce(value, lit("")), "\\s+", " "));
        String pattern = "^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?$";
        Column year = regexp_extract(normalized, pattern, 1);
        Column month = regexp_extract(normalized, pattern, 2);
        Column day = regexp_extract(normalized, pattern, 3);
        Column hour = regexp_extract(normalized, pattern, 4);
        Column minute = regexp_extract(normalized, pattern, 5);
        Column second = regexp_extract(normalized, pattern, 6);
        Column canonical = concat(year, lit("-"), lpad(month, 2, "0"), lit("-"), lpad(day, 2, "0"),
                lit(" "), lpad(hour, 2, "0"), lit(":"), minute, lit(":"),
                when(length(second).equalTo(0), lit("00")).otherwise(second));
        return to_timestamp(canonical, "yyyy-MM-dd HH:mm:ss");
    }

    private static Dataset<Row> applyHanlpSentiment(Dataset<Row> data, UserDefinedFunction analyzer) {
        Column encoded = when(col("platform").equalTo("WEIBO"), analyzer.apply(col("clean_text")))
                .otherwise(lit("neutral|0.000000|1.000000|0.000000"));
        Dataset<Row> analyzed = data.withColumn("_sentiment_result", encoded)
                .withColumn("_sentiment_parts", split(col("_sentiment_result"), "\\|"));
        return analyzed
                .withColumn("sentiment_label", element_at(col("_sentiment_parts"), 1))
                .withColumn("sentiment_positive_score", element_at(col("_sentiment_parts"), 2).cast("double"))
                .withColumn("sentiment_neutral_score", element_at(col("_sentiment_parts"), 3).cast("double"))
                .withColumn("sentiment_negative_score", element_at(col("_sentiment_parts"), 4).cast("double"))
                .drop("_sentiment_result", "_sentiment_parts");
    }

    private static Dataset<Row> buildPlatformRelativeHeat(Dataset<Row> valid) {
        WindowSpec platformWindow = Window.partitionBy("event_id", "platform");
        Dataset<Row> result = valid;
        for (String field : INTERACTION_FIELDS) {
            String coverageColumn = "_" + field + "_coverage";
            String percentileColumn = "_" + field + "_percentile";
            result = result
                    .withColumn(coverageColumn, avg(when(col(field).gt(0), lit(1D)).otherwise(lit(0D))).over(platformWindow))
                    .withColumn(percentileColumn, round(percent_rank().over(platformWindow.orderBy(log1p(col(field)).asc())).multiply(100), 2));
        }

        Column interactionSignalCount = lit(0);
        Column interactionPercentileSum = lit(0D);
        for (String field : INTERACTION_FIELDS) {
            Column available = supportsInteractionField(field)
                    .and(col("_" + field + "_coverage").geq(SIGNAL_COVERAGE_THRESHOLD));
            interactionSignalCount = interactionSignalCount.plus(when(available, lit(1)).otherwise(lit(0)));
            interactionPercentileSum = interactionPercentileSum.plus(when(available, col("_" + field + "_percentile")).otherwise(lit(0D)));
        }
        Column interactionAvailable = interactionSignalCount.gt(0);
        Column relativeIndex = when(interactionAvailable, interactionPercentileSum.divide(interactionSignalCount))
                .otherwise(lit(null).cast("double"));

        return result
                .withColumn("interaction_signal_count", interactionSignalCount)
                .withColumn("has_heat_signal", interactionAvailable)
                .withColumn("signal_mode", heatAlgorithmExpr())
                .withColumn("platform_heat_index", round(relativeIndex, 2))
                .withColumn("hot_score", coalesce(round(relativeIndex, 2), lit(0D)));
    }

    private static Column supportsInteractionField(String field) {
        Column platform = col("platform");
        return switch (field) {
            case "like_count" -> platform.isin("TENCENT_NEWS", "SOHU_NEWS", "THE_PAPER");
            case "comment_count" -> platform.isin("TENCENT_NEWS", "SOHU_NEWS", "THE_PAPER", "SINA_NEWS", "NETEASE_NEWS");
            case "repost_count", "favorite_count" -> platform.equalTo("TENCENT_NEWS");
            default -> lit(false);
        };
    }

    private static Column heatAlgorithmExpr() {
        return when(col("platform").equalTo("TENCENT_NEWS"), "TENCENT_4_FIELDS")
                .when(col("platform").isin("SOHU_NEWS", "THE_PAPER"), "LIKE_COMMENT_2_FIELDS")
                .when(col("platform").isin("SINA_NEWS", "NETEASE_NEWS"), "COMMENT_1_FIELD")
                .otherwise("UNSUPPORTED");
    }
    private static Column topicSeedExpr() {
        Column keywordText = regexp_replace(coalesce(nullIfBlank(col("keywords")), lit("")), "[,，�?|�?\\s]+", ",");
        Column keywordSeed = concat_ws(" / ", slice(split(keywordText, ","), 1, 2));
        Column titleSeed = regexp_replace(coalesce(nullIfBlank(col("title")), lit("")), "[\\p{Punct}\\s]+", "").substr(1, 24);
        return when(length(keywordSeed).geq(2), keywordSeed).otherwise(titleSeed);
    }

    private static double doubleParam(Map<String, String> params, String key, double defaultValue) {
        String value = params.get(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid numeric parameter --" + key + ": " + value, ex);
        }
    }

    private static String fileName(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int fragment = name.indexOf('#');
        return fragment >= 0 ? name.substring(fragment + 1) : name;
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
                "ads_sentiment_trend", "dwd_weibo_sentiment_result", "ads_noise_summary", "ads_topic_trend",
                "ads_topic_summary", "ads_topic_key_content", "ads_platform_category_heat", "ads_platform_daily_heat"
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
