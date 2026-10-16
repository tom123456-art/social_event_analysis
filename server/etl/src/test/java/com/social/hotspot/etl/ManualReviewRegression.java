package com.social.hotspot.etl;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import java.util.List;

public class ManualReviewRegression {
    public static void main(String[] args) throws Exception {
        try (var spark = SparkSession.builder().master("local[1]").appName("manual-review-regression")
                .config("spark.ui.enabled", "false").config("spark.sql.shuffle.partitions", "1").getOrCreate()) {
            spark.sparkContext().setLogLevel("WARN");
            var schema = new StructType().add("_review_approved", DataTypes.BooleanType)
                    .add("_review_sentiment", DataTypes.StringType).add("sentiment_label", DataTypes.StringType)
                    .add("sentiment_positive_score", DataTypes.DoubleType)
                    .add("sentiment_neutral_score", DataTypes.DoubleType)
                    .add("sentiment_negative_score", DataTypes.DoubleType);
            var input = spark.createDataFrame(List.of(
                    RowFactory.create(true, "neutral", "positive", .6, 0., .4),
                    RowFactory.create(false, "negative", "positive", .95, 0., .05)), schema);
            var method = com.social.hotspot.etl.sentiment.SentimentResultEnricher.class.getDeclaredMethod("applyManualSentiment", Dataset.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked") Dataset<Row> output = (Dataset<Row>) method.invoke(null, input);
            var rows = output.collectAsList();
            if (!"MANUAL".equals(rows.getFirst().getAs("sentiment_source"))
                    || !"neutral".equals(rows.getFirst().getAs("sentiment_label"))) throw new AssertionError("Manual label lost");
            for (String field : List.of("sentiment_positive_score", "sentiment_neutral_score", "sentiment_negative_score"))
                if (rows.getFirst().getAs(field) != null) throw new AssertionError("Fabricated manual probability");
            if (!"MODEL".equals(rows.get(1).getAs("sentiment_source"))
                    || !"positive".equals(rows.get(1).getAs("sentiment_label"))) throw new AssertionError("Unapproved label applied");
        }
        System.out.println("PASS: manual sentiment labels override models without fabricating probabilities.");
    }
}
