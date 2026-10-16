package com.social.hotspot.etl.aggregate;
import static com.social.hotspot.etl.cleaning.DataFrameColumns.nullIfBlank;
import static com.social.hotspot.etl.publish.JdbcDatasetPublisher.upsertEvent;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import java.util.Map;
import static org.apache.spark.sql.functions.*;

public final class DwsSummaryAggregator {
    private DwsSummaryAggregator() {}
    public static Map<String, Dataset<Row>> buildDwsSummaries(Dataset<Row> detail, long duplicateCount) {
        Dataset<Row> warehouseHeatTrend = detail.groupBy(col("event_id"), col("time_bucket"), col("platform"))
                .agg(
                    count(lit(1)).alias("content_count"),
                    sum(col("interaction_count")).cast("long").alias("interaction_count"),
                    round(sum(col("hot_score")), 2).alias("hot_score")
                );

        Dataset<Row> platformDailyHeatAll = detail
                .filter(col("platform").isin("TENCENT_NEWS", "NETEASE_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER"))
                .withColumn("heat_day", to_date(col("publish_time")))
                .groupBy(col("event_id"), col("platform"), col("heat_day"))
                .agg(
                    count(lit(1)).alias("content_count"),
                    round(avg(col("platform_heat_index")), 2).alias("average_heat_index"),
                    round(max(col("platform_heat_index")), 2).alias("peak_content_heat_index"),
                    sum(when(col("platform_heat_index").geq(80), 1).otherwise(0)).alias("high_heat_content_count"),
                    first(col("signal_mode"), true).alias("heat_algorithm")
                );
        Dataset<Row> timeline = detail.groupBy(col("event_id"), col("platform"))
                .agg(
                    min(col("publish_time")).alias("first_publish_time"),
                    count(lit(1)).alias("content_count"),
                    round(sum(col("hot_score")), 2).alias("hot_score")
                );

        Dataset<Row> platformCategoryBase = detail
                .filter(col("platform").isin("TENCENT_NEWS", "NETEASE_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER"))
                .withColumn("category_label", when(length(trim(col("category"))).gt(0), trim(col("category"))).otherwise(lit("未分类")))
                .withColumn("category_day", to_date(col("publish_time")));
        Dataset<Row> platformDailyTotal = platformCategoryBase.groupBy(col("event_id"), col("platform"), col("category_day"))
                .agg(count(lit(1)).alias("platform_content_count"));
        Dataset<Row> platformCategoryHeatAll = platformCategoryBase
                .groupBy(col("event_id"), col("platform"), col("category_day"), col("category_label"))
                .agg(count(lit(1)).alias("content_count"), round(avg(col("platform_heat_index")), 2).alias("average_relative_heat_index"))
                .join(platformDailyTotal, new String[]{"event_id", "platform", "category_day"}, "inner")
                .withColumn("coverage_share", col("content_count").cast("double").divide(col("platform_content_count")).multiply(100))
                .withColumn("composite_attention_index", round(sqrt(col("coverage_share").multiply(coalesce(col("average_relative_heat_index"), lit(0D)))), 2))
                .withColumn("signal_mode", lit("PLATFORM_RELATIVE_HEAT"));
        Dataset<Row> sentiment = detail.filter(col("sentiment_label").isin("positive", "negative", "neutral"))
                .groupBy(col("event_id"), col("time_bucket"), col("platform"), col("sentiment_label"))
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
                .select("event_id", "rank_no", "platform", "content_id", "parent_content_id", "content_type", "title", "clean_text", "author_name", "publish_time", "keywords", "category", "like_count", "favorite_count", "comment_count", "repost_count", "sentiment_label", "sentiment_source", "sentiment_positive_score", "sentiment_neutral_score", "sentiment_negative_score", "hot_score", "platform_heat_index", "source_url");
        // ads_event_overview was pruned to the current four-column contract.
        // Keep event_name in event_info, where it is maintained by upsertEvent.
        Dataset<Row> overview = detail.groupBy(col("event_id"))
                .agg(
                    count(lit(1)).alias("content_count"),
                    countDistinct(col("platform")).alias("platform_count"),
                    round(sum(col("hot_score")), 2).alias("hot_score")
                );
        Dataset<Row> noise = detail.groupBy(col("event_id"), col("time_bucket"), col("platform"))
                .agg(
                    sum(when(col("is_noise").equalTo(true), 1).otherwise(0)).alias("noise_count"),
                    count(lit(1)).alias("content_count")
                )
                .withColumn("duplicate_count", lit(duplicateCount));
        Map<String, Dataset<Row>> summaries = buildTopicSummaries(detail);
        summaries.put("dws_event_heat_summary", warehouseHeatTrend);
        summaries.put("dws_platform_daily_summary", platformDailyHeatAll);
        summaries.put("dws_platform_category_summary", platformCategoryHeatAll);
        summaries.put("dws_platform_spread_summary", timeline);
        summaries.put("dws_sentiment_summary", sentiment);
        summaries.put("dws_keyword_summary", keywordRank);
        summaries.put("dws_content_rank", contentRank);
        summaries.put("dws_event_summary", overview);
        summaries.put("dws_noise_summary", noise);
        return summaries;
    }

    public static Map<String, Dataset<Row>> buildTopicSummaries(Dataset<Row> detail) {
        Map<String, Dataset<Row>> summaries = new java.util.LinkedHashMap<>();
        Dataset<Row> topicBase = detail
                .withColumn("topic_name", trim(topicSeedExpr()))
                .filter(length(col("topic_name")).geq(2))
                .withColumn("topic_id", sha2(concat_ws("||", col("event_id"), col("topic_name")), 256))
                .withColumn("topic_day", to_date(col("publish_time")));
        Dataset<Row> topicTrend = topicBase.groupBy(col("event_id"), col("topic_id"), col("topic_name"), col("category"), col("topic_day"))
                .agg(
                        count(lit(1)).alias("content_count"),
                        countDistinct(col("platform")).alias("platform_count")
                )
                .withColumn("burst_index", round(col("content_count").cast("double")
                        .multiply(log(col("platform_count").plus(1D)).divide(log(lit(2D)))), 2))
                .select(col("event_id"), col("topic_id"), col("topic_name"), col("category"), col("topic_day").alias("time_bucket"),
                        col("content_count"), col("platform_count"), col("burst_index"));
        summaries.put("dws_topic_trend", topicTrend);

        Dataset<Row> topicSummaryBase = topicBase.groupBy(col("event_id"), col("topic_id"), col("topic_name"), col("category"))
                .agg(
                        min(col("publish_time")).alias("first_publish_time"),
                        max(col("publish_time")).alias("latest_publish_time"),
                        count(lit(1)).alias("content_count"),
                        countDistinct(col("platform")).alias("platform_count")
                );
        WindowSpec topicPeakWindow = Window.partitionBy("event_id", "topic_id").orderBy(col("content_count").desc(), col("time_bucket").asc());
        Dataset<Row> topicPeak = topicTrend
                .withColumn("_peak_rank", row_number().over(topicPeakWindow))
                .filter(col("_peak_rank").equalTo(1))
                .select(col("event_id"), col("topic_id"), col("time_bucket").alias("peak_time"),
                        col("content_count").alias("peak_daily_count"), col("burst_index").alias("peak_burst_index"));
        Dataset<Row> topicSummary = topicSummaryBase.join(topicPeak, new String[]{"event_id", "topic_id"}, "inner")
                .withColumn("duration_days", datediff(col("latest_publish_time"), col("first_publish_time")).plus(1).cast("int"))
                .withColumn("current_stage", lit("COMPLETED"))
                .select("event_id", "topic_id", "topic_name", "category", "first_publish_time", "peak_time", "latest_publish_time",
                        "content_count", "platform_count", "duration_days", "peak_daily_count", "peak_burst_index", "current_stage");
        summaries.put("dws_topic_summary", topicSummary);

        Dataset<Row> firstContent = topicBase.withColumn("_rank", row_number().over(Window.partitionBy("event_id", "topic_id").orderBy(col("publish_time").asc())))
                .filter(col("_rank").equalTo(1)).withColumn("event_role", lit("FIRST"));
        Dataset<Row> peakContent = topicBase.withColumn("_rank", row_number().over(Window.partitionBy("event_id", "topic_id").orderBy(col("hot_score").desc(), col("publish_time").asc())))
                .filter(col("_rank").equalTo(1)).withColumn("event_role", lit("PEAK"));
        Dataset<Row> latestContent = topicBase.withColumn("_rank", row_number().over(Window.partitionBy("event_id", "topic_id").orderBy(col("publish_time").desc())))
                .filter(col("_rank").equalTo(1)).withColumn("event_role", lit("LATEST"));
        summaries.put("dws_topic_key_content", firstContent.unionByName(peakContent).unionByName(latestContent)
                .select("event_id", "topic_id", "event_role", "publish_time", "platform", "content_id", "title", "clean_text", "source_url"));
        return summaries;
    }

    public static Column topicSeedExpr() {
        Column keywordText = regexp_replace(coalesce(nullIfBlank(col("keywords")), lit("")), "[,，�?|�?\\s]+", ",");
        Column keywordSeed = concat_ws(" / ", slice(split(keywordText, ","), 1, 2));
        Column titleSeed = regexp_replace(coalesce(nullIfBlank(col("title")), lit("")), "[\\p{Punct}\\s]+", "").substr(1, 24);
        return when(length(keywordSeed).geq(2), keywordSeed).otherwise(titleSeed);
    }
}
