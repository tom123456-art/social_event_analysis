package com.social.hotspot.etl;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import java.util.List;

import static org.apache.spark.sql.functions.*;

/** In-memory regression only: no JDBC, Hive registration or warehouse writes. */
public class SourceRankRegression {
    public static void main(String[] args) throws Exception {
        try (SparkSession spark = SparkSession.builder().master("local[1]").appName("source-rank-regression")
                .config("spark.ui.enabled", "false").config("spark.sql.ansi.enabled", "false")
                .config("spark.sql.adaptive.enabled", "false").config("spark.sql.shuffle.partitions", "1").getOrCreate()) {
            spark.sparkContext().setLogLevel("WARN");
            var schema = new StructType().add("event_id", DataTypes.StringType).add("platform", DataTypes.StringType)
                    .add("content_id", DataTypes.StringType).add("like_count", DataTypes.StringType)
                    .add("comment_count", DataTypes.StringType).add("favorite_count", DataTypes.StringType)
                    .add("hot_rank", DataTypes.StringType);
            Dataset<Row> raw = spark.createDataFrame(List.of(
                    RowFactory.create("event", "SOHU_NEWS", "item-001", "10", "10", "10", "1"),
                    RowFactory.create("event", "SOHU_NEWS", "item-002", "20", "20", "20", "bad-rank"),
                    RowFactory.create("event", "SOHU_NEWS", "item-003", "30", "30", "30", "999")), schema);
            var normalize = com.social.hotspot.etl.cleaning.ContentNormalizer.class.getDeclaredMethod("normalize", Dataset.class, String.class);
            normalize.setAccessible(true);
            @SuppressWarnings("unchecked")
            Dataset<Row> normalized = (Dataset<Row>) normalize.invoke(null, raw, "test");
            if (List.of(normalized.columns()).contains("hot_rank") || normalized.filter(col("has_invalid_numeric")).count() != 0)
                throw new AssertionError("Source rank affected normalization or validity");
            Dataset<Row> scored = com.social.hotspot.etl.metric.InteractionHeatCalculator.buildPlatformRelativeHeat(normalized);
            Dataset<Row> changed = com.social.hotspot.etl.metric.InteractionHeatCalculator.buildPlatformRelativeHeat(normalized.withColumn("hot_rank", lit(42)));
            if (List.of(changed.columns()).contains("hot_rank"))
                throw new AssertionError("Legacy DWD rank not removed");
            var expected = scored.select("content_id", "hot_score", "platform_heat_index").orderBy("content_id").collectAsList();
            var actual = changed.select("content_id", "hot_score", "platform_heat_index").orderBy("content_id").collectAsList();
            if (!expected.equals(actual)) throw new AssertionError("Rank changed heat scores");
            var enriched = scored.withColumn("sentiment_source", lit("MODEL"));
            for (String field : new String[]{"sentiment_positive_score", "sentiment_neutral_score", "sentiment_negative_score"})
                enriched = enriched.withColumn(field, lit(0D));
            var ranks = com.social.hotspot.etl.aggregate.DwsSummaryAggregator.buildDwsSummaries(enriched, 0).get("dws_content_rank");
            if (!"item-003".equals(ranks.filter(col("rank_no").equalTo(1)).first().getAs("content_id")))
                throw new AssertionError("Ranking did not follow calculated heat");
        }
        System.out.println("PASS: source rank ignored for numeric validity, normalization, heat, legacy DWD and DWS ranking.");
    }
}
