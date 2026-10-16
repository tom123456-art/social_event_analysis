package com.social.hotspot.etl;
import com.social.hotspot.etl.cleaning.HtmlTextCleaner;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HtmlInteractionRegression {
    private static final String[] FIELDS = {"like_count", "comment_count", "repost_count", "favorite_count"};
    private static final Map<String, List<String>> SUPPORT = Map.of(
            "TENCENT_NEWS", List.of(FIELDS), "THE_PAPER", List.of("like_count", "comment_count"),
            "SOHU_NEWS", List.of("like_count", "comment_count", "favorite_count"),
            "SINA_NEWS", List.of("comment_count"), "NETEASE_NEWS", List.of("comment_count"),
            "WEIBO", List.of());

    public static void main(String[] args) throws Exception {
        if (!"A B C D & E A B".equals(HtmlTextCleaner.clean("<p>A&nbsp;<b>B</b></p><div>C<br>D &amp; E &#65; &#x42;</div>")))
            throw new AssertionError("HTML extraction failed");
        if (!"AB".equals(HtmlTextCleaner.clean("A<script>alert(1)</script><style>x</style><template>hidden</template><!-- note -->B")))
            throw new AssertionError("Hidden HTML leaked");
        if (!"1 < 2 & 3 > 2".equals(HtmlTextCleaner.clean("1 < 2 &amp; 3 > 2")))
            throw new AssertionError("Plain text comparison damaged");
        if (!"<b>text</b>".equals(HtmlTextCleaner.clean("&lt;b&gt;text&lt;/b&gt;")))
            throw new AssertionError("Entities decoded twice");
        try (SparkSession spark = SparkSession.builder().master("local[1]").appName("html-interaction-regression")
                .config("spark.ui.enabled", "false").config("spark.sql.ansi.enabled", "false")
                .config("spark.sql.shuffle.partitions", "1").getOrCreate()) {
            spark.sparkContext().setLogLevel("WARN");
            StructType schema = new StructType().add("event_id", DataTypes.StringType)
                    .add("platform", DataTypes.StringType).add("content_id", DataTypes.StringType);
            for (String field : FIELDS) schema = schema.add(field, DataTypes.StringType);
            List<Row> input = new ArrayList<>();
            for (String platform : SUPPORT.keySet()) {
                input.add(RowFactory.create("event", platform, "empty-" + platform, null, "", " ", null));
                input.add(RowFactory.create("event", platform, "zero-" + platform, "0", "0", "0", "0"));
                input.add(RowFactory.create("event", platform, "value-" + platform, "10", "20", "30", "40"));
            }
            var normalize = com.social.hotspot.etl.cleaning.ContentNormalizer.class.getDeclaredMethod("normalize", Dataset.class, String.class);
            normalize.setAccessible(true);
            @SuppressWarnings("unchecked")
            Dataset<Row> normalized = (Dataset<Row>) normalize.invoke(null, spark.createDataFrame(input, schema), "test");
            for (Row row : normalized.collectAsList()) {
                String platform = row.getAs("platform");
                boolean valued = row.<String>getAs("content_id").startsWith("value-");
                long expectedTotal = 0;
                for (int i = 0; i < FIELDS.length; i++) {
                    Object actual = row.getAs(FIELDS[i]);
                    if (SUPPORT.get(platform).contains(FIELDS[i])) {
                        long expected = valued ? (i + 1) * 10L : 0L;
                        if (!Long.valueOf(expected).equals(actual)) throw new AssertionError(platform + " " + FIELDS[i] + ": " + actual);
                        expectedTotal += expected;
                    } else if (actual != null) throw new AssertionError("Unsupported field is not null");
                }
                if (row.<Number>getAs("interaction_count").doubleValue() != expectedTotal)
                    throw new AssertionError("Null field broke total");
            }
            var heat = com.social.hotspot.etl.metric.InteractionHeatCalculator.class.getDeclaredMethod("buildPlatformRelativeHeat", Dataset.class);
            heat.setAccessible(true);
            @SuppressWarnings("unchecked")
            Dataset<Row> scored = (Dataset<Row>) heat.invoke(null, normalized);
            for (Row row : scored.collectAsList()) {
                String platform = row.getAs("platform");
                int supported = SUPPORT.get(platform).size();
                if (row.<Integer>getAs("interaction_signal_count") != supported)
                    throw new AssertionError("Wrong supported signal count: " + platform);
                if (supported == 0 && row.getAs("platform_heat_index") != null)
                    throw new AssertionError("Weibo received heat signal");
            }
            var clean = com.social.hotspot.etl.cleaning.ContentNormalizer.class.getDeclaredMethod("cleanText", Column.class);
            clean.setAccessible(true);
            var html = spark.createDataFrame(List.of(RowFactory.create("<p>A&nbsp;B</p>")),
                    new StructType().add("text", DataTypes.StringType));
            if (!"A B".equals(html.select((Column) clean.invoke(null, org.apache.spark.sql.functions.col("text"))).first().getString(0)))
                throw new AssertionError("Spark HTML UDF failed");
        }
        System.out.println("PASS: HTML cleanup, six-platform null/zero policy, totals, heat signals.");
    }
}
