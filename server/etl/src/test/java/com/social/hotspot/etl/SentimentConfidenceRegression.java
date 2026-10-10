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
            Method filter = SocialHotspotEtlJob.class.getDeclaredMethod("highConfidenceSentiment", double.class);
            filter.setAccessible(true);
            Column condition = (Column) filter.invoke(null, .9D);
            Set<String> retained = rows.filter(condition).select("id").collectAsList().stream()
                    .map(row -> row.getString(0)).collect(Collectors.toSet());
            if (!retained.equals(Set.of("positive-boundary", "negative-boundary", "positive-high"))) {
                throw new AssertionError("Unexpected retained IDs: " + retained);
            }
            System.out.println("PASS: Spark binary-confidence filter covers threshold boundaries, ties, legacy labels, overrides, missing/NaN/invalid probabilities.");
        }
    }
}
