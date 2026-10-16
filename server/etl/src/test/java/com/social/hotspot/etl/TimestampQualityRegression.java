package com.social.hotspot.etl;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.spark.sql.functions.col;

/** 验证两个时间字段独立解析，任一无效时不进入有效明细。 */
public class TimestampQualityRegression {
    public static void main(String[] args) throws Exception {
        try (SparkSession spark = SparkSession.builder().appName("timestamp-quality-regression")
                .config("spark.ui.enabled", "false")
                .config("spark.sql.session.timeZone", "Asia/Shanghai")
                .config("spark.sql.ansi.enabled", "false").getOrCreate()) {
            spark.sparkContext().setLogLevel("WARN");
            StructType schema = new StructType()
                    .add("content_id", DataTypes.StringType, false)
                    .add("publish_time", DataTypes.StringType, true)
                    .add("crawl_time", DataTypes.StringType, true);
            String published = "2026-10-01 10:00:00";
            String crawled = "2026-10-01 11:00:00";
            DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            Dataset<Row> input = spark.createDataFrame(List.of(
                    RowFactory.create("valid", published, crawled),
                    RowFactory.create("missing-publish", null, crawled),
                    RowFactory.create("missing-crawl", published, null),
                    RowFactory.create("blank-publish", "  ", crawled),
                    RowFactory.create("blank-crawl", published, ""),
                    RowFactory.create("invalid-publish", "invalid", crawled),
                    RowFactory.create("invalid-crawl", published, "invalid"),
                    RowFactory.create("missing-both", null, null)
            ), schema);
            // 调用实际字段规范化方法，检查缺失时间没有被另一个时间替代。
            Method normalize = com.social.hotspot.etl.cleaning.ContentNormalizer.class.getDeclaredMethod("normalize", Dataset.class, String.class);
            normalize.setAccessible(true);
            @SuppressWarnings("unchecked")
            Dataset<Row> normalized = (Dataset<Row>) normalize.invoke(null, input, "timestamp-test");
            for (Row row : normalized.select("content_id", "publish_time", "crawl_time").collectAsList()) {
                String id = row.getString(0);
                if ((id.contains("publish") || id.equals("missing-both")) && !row.isNullAt(1)) {
                    throw new AssertionError("无效的发布时间被替代：" + id);
                }
                if ((id.contains("crawl") || id.equals("missing-both")) && !row.isNullAt(2)) {
                    throw new AssertionError("无效的采集时间被替代：" + id);
                }
                if (id.equals("valid") && (!LocalDateTime.parse(published, format).equals(row.getTimestamp(1).toLocalDateTime())
                        || !LocalDateTime.parse(crawled, format).equals(row.getTimestamp(2).toLocalDateTime()))) {
                    throw new AssertionError("有效时间字段发生变化。");
                }
            }
            // 两个时间均有效的记录才通过完整性检查。
            Set<String> retained = normalized.filter(col("publish_time").isNotNull())
                    .filter(col("crawl_time").isNotNull()).select("content_id").collectAsList().stream()
                    .map(row -> row.getString(0)).collect(Collectors.toSet());
            if (!retained.equals(Set.of("valid"))) {
                throw new AssertionError("无效时间记录未被剔除：" + retained);
            }
            System.out.println("通过：时间字段独立解析，缺失、空白及无效时间均被剔除。");
        }
    }
}
