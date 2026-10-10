package com.social.hotspot.etl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import java.util.List;

public class TextQualityRegression {
    public static void main(String[] args) throws Exception {
        try (var input = TextQualityRegression.class.getResourceAsStream("/text-quality-cases.json")) {
            for (var test : new ObjectMapper().readTree(input)) {
                String text = test.get("text").isNull() ? null : test.get("text").asText();
                if ((TextQuality.rejectionReason(text) != null) != test.get("reject").asBoolean()) {
                    throw new AssertionError("Wrong text quality decision: " + text);
                }
            }
        }
        try (SparkSession spark = SparkSession.builder().appName("global-text-quality-regression")
                .config("spark.ui.enabled", "false").getOrCreate()) {
            spark.sparkContext().setLogLevel("WARN");
            StructType schema = new StructType().add("title", DataTypes.StringType)
                    .add("content_text", DataTypes.StringType).add("author_name", DataTypes.StringType)
                    .add("keywords", DataTypes.StringType).add("category", DataTypes.StringType);
            Dataset<Row> rows = spark.createDataFrame(List.of(
                    RowFactory.create("上海6岁女Y??接?ā基因编辑??疗后死亡", "正常正文", "作者", "医学", "社会"),
                    RowFactory.create("正常标题", "卫健?T门已经介入调查", "作者", "医学", "社会"),
                    RowFactory.create("正常标题", "AI治疗与DNA编辑😊", "作者", "医学", "社会")
            ), schema);
            var method = SocialHotspotEtlJob.class.getDeclaredMethod("textQualityReason", Dataset.class);
            method.setAccessible(true);
            Column reason = (Column) method.invoke(null, rows);
            List<Row> result = rows.withColumn("reason", reason).collectAsList();
            if (!result.get(0).getAs("reason").toString().startsWith("title:")) throw new AssertionError("Damaged title escaped");
            if (!result.get(1).getAs("reason").toString().startsWith("content_text:")) throw new AssertionError("Damaged body escaped");
            if (!result.get(2).isNullAt(5)) throw new AssertionError("Valid content rejected");
        }
        System.out.println("PASS: shared text quality fixtures and Spark original-field rejection.");
    }
}
