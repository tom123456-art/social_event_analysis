package com.social.hotspot.etl;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class SentimentConfidenceRegression {
    public static void main(String[] args) throws Exception {
        try (SparkSession spark = SparkSession.builder().appName("sentiment-confidence-regression")
                .config("spark.ui.enabled", "false").getOrCreate()) {
            spark.sparkContext().setLogLevel("WARN");
            StructType schema = new StructType()
                    .add("id", DataTypes.StringType, false)
                    .add("sentiment_label", DataTypes.StringType, true)
                    .add("sentiment_positive_score", DataTypes.DoubleType, true)
                    .add("sentiment_negative_score", DataTypes.DoubleType, true);
            Dataset<Row> rows = spark.createDataFrame(List.of(
                    RowFactory.create("positive-boundary", "positive", .9, .1),
                    RowFactory.create("negative-boundary", "negative", .1, .9),
                    RowFactory.create("positive-high", "positive", .999, .001),
                    RowFactory.create("below-threshold", "positive", .899999, .100001),
                    RowFactory.create("tie", "positive", .5, .5),
                    RowFactory.create("legacy-neutral", "neutral", .96, .04),
                    RowFactory.create("label-override", "negative", .99, .01),
                    RowFactory.create("missing", "positive", null, .1),
                    RowFactory.create("nan", "positive", Double.NaN, .1),
                    RowFactory.create("invalid-sum", "positive", .99, .99),
                    RowFactory.create("invalid-range", "positive", 1.1, -.1)
            ), schema);
            Method filter = com.social.hotspot.etl.sentiment.SentimentResultEnricher.class.getDeclaredMethod("highConfidenceSentiment", double.class);
            filter.setAccessible(true);
            Column condition = (Column) filter.invoke(null, .9D);
            Set<String> retained = rows.filter(condition).select("id").collectAsList().stream()
                    .map(row -> row.getString(0)).collect(Collectors.toSet());
            if (!retained.equals(Set.of("positive-boundary", "negative-boundary", "positive-high"))) {
                throw new AssertionError("Unexpected retained IDs: " + retained);
            }
            Dataset<Row> annotated = rows.withColumn("_review_approved", org.apache.spark.sql.functions.lit(false))
                    .withColumn("_review_sentiment", org.apache.spark.sql.functions.lit(""))
                    .withColumn("sentiment_neutral_score", org.apache.spark.sql.functions.lit(0D));
            Dataset<Row> eligible = com.social.hotspot.etl.sentiment.SentimentResultEnricher.applySentimentEligibility(annotated, .9D);
            if (eligible.count() != rows.count()) throw new AssertionError("Sentiment eligibility removed content");
            if (eligible.filter(org.apache.spark.sql.functions.col("sentiment_label").isNotNull()).count() != 3)
                throw new AssertionError("Uncertain predictions entered sentiment analysis");
            for (Row row : eligible.filter(org.apache.spark.sql.functions.col("sentiment_label").isNull()).collectAsList()) {
                if (!"LOW_CONFIDENCE".equals(row.getAs("sentiment_source"))) throw new AssertionError("Missing uncertainty marker");
                for (String field : List.of("sentiment_positive_score", "sentiment_negative_score", "sentiment_neutral_score"))
                    if (row.getAs(field) != null) throw new AssertionError("Uncertain probability leaked: " + field);
            }
            Dataset<Row> manual = annotated.withColumn("_review_approved", org.apache.spark.sql.functions.lit(true))
                    .withColumn("_review_sentiment", org.apache.spark.sql.functions.lit("neutral"));
            if (com.social.hotspot.etl.sentiment.SentimentResultEnricher.applySentimentEligibility(manual, .9D)
                    .filter(org.apache.spark.sql.functions.col("sentiment_label").equalTo("neutral")).count() != rows.count())
                throw new AssertionError("Manual sentiment excluded");
            Dataset<Row> secondPass = com.social.hotspot.etl.sentiment.SentimentResultEnricher.applySentimentEligibility(eligible, .9D);
            if (secondPass.count() != rows.count()
                    || secondPass.filter(org.apache.spark.sql.functions.col("sentiment_label").isNotNull()).count() != 3)
                throw new AssertionError("Incremental eligibility changed retained content");
            Dataset<Row> skipped = annotated.withColumn("_sentiment_status", org.apache.spark.sql.functions.lit("SKIPPED"));
            Dataset<Row> notApplicable = com.social.hotspot.etl.sentiment.SentimentResultEnricher.applySentimentEligibility(skipped, .95D);
            if (notApplicable.count() != rows.count()
                    || notApplicable.filter(org.apache.spark.sql.functions.col("sentiment_source").equalTo("NOT_APPLICABLE")).count() != rows.count()
                    || notApplicable.filter(org.apache.spark.sql.functions.col("sentiment_label").isNotNull()).count() != 0)
                throw new AssertionError("Skipped prediction lost content or leaked sentiment");
            Dataset<Row> restored = com.social.hotspot.etl.sentiment.SentimentResultEnricher.applySentimentEligibility(notApplicable.drop("_sentiment_status"), .95D);
            if (restored.filter(org.apache.spark.sql.functions.col("sentiment_source").equalTo("NOT_APPLICABLE")).count() != rows.count())
                throw new AssertionError("Prior DWD lost not-applicable provenance");
            var inputReader = com.social.hotspot.etl.sentiment.SentimentResultEnricher.class.getDeclaredMethod("readSentimentInput", SparkSession.class, String.class);
            inputReader.setAccessible(true);
            var joiner = com.social.hotspot.etl.sentiment.SentimentResultEnricher.class.getDeclaredMethod("applySentimentResults", Dataset.class, Dataset.class);
            joiner.setAccessible(true);
            var csv = java.nio.file.Files.createTempFile("sentiment-gate-test-", ".csv");
            try {
                java.nio.file.Files.writeString(csv, "platform,content_id,sentiment_label,sentiment_positive_score,sentiment_neutral_score,sentiment_negative_score,sentiment_status,sentiment_reason\n"
                        + "WEIBO,skipped,,,,,SKIPPED,NO_ATTITUDE_CUE\nWEIBO,predicted,positive,0.96,0,0.04,PREDICTED,MODEL_BINARY\n");
                @SuppressWarnings("unchecked") Dataset<Row> predictions = (Dataset<Row>) inputReader.invoke(null, spark, csv.toString());
                if (predictions.count() != 2) throw new AssertionError("Skipped CSV row discarded");
                Dataset<Row> content = spark.createDataFrame(List.of(RowFactory.create("WEIBO", "skipped"),
                        RowFactory.create("WEIBO", "predicted"), RowFactory.create("WEIBO", "missing")),
                        new StructType().add("platform", DataTypes.StringType).add("content_id", DataTypes.StringType))
                        .withColumn("sentiment_label", org.apache.spark.sql.functions.lit("neutral"))
                        .withColumn("_sentiment_status", org.apache.spark.sql.functions.lit("OLD"));
                @SuppressWarnings("unchecked") Dataset<Row> joined = (Dataset<Row>) joiner.invoke(null, content, predictions);
                if (joined.count() != 3 || joined.filter(org.apache.spark.sql.functions.col("_sentiment_status").equalTo("SKIPPED")).count() != 1
                        || joined.filter(org.apache.spark.sql.functions.col("_sentiment_status").isNull()).count() != 1)
                    throw new AssertionError("Join confused skipped and missing predictions");
            } finally { java.nio.file.Files.deleteIfExists(csv); }
            System.out.println("PASS: Spark binary-confidence filter covers threshold boundaries, ties, legacy labels, overrides, missing/NaN/invalid probabilities.");
        }
    }
}
