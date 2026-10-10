package com.social.hotspot.etl;

import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;

/** Read-only checks against the completed warehouse snapshot. */
public class GlobalTextQualityVerification {
    public static void main(String[] args) throws Exception {
        String warehouse = args[0];
        String batch = args[1];
        try (SparkSession spark = SparkSession.builder().appName("verify-global-text-quality")
                .config("spark.ui.enabled", "false").getOrCreate()) {
            spark.sparkContext().setLogLevel("WARN");
            var rejected = spark.read().parquet(warehouse + "/quarantine/text_encoding/batch_id=" + batch);
            var detail = spark.read().parquet(warehouse + "/dwd/dwd_social_content_detail/batch_id=" + batch);
            long rejectedCount = rejected.count();
            long retainedCount = detail.count();
            if (rejectedCount != Long.parseLong(args[2])) throw new AssertionError("Wrong quarantine count");
            if (retainedCount != Long.parseLong(args[3])) throw new AssertionError("Wrong DWD count");
            var detect = functions.udf((org.apache.spark.sql.api.java.UDF1<String, String>) TextQuality::rejectionReason,
                    org.apache.spark.sql.types.DataTypes.StringType);
            for (String field : new String[]{"title", "clean_text", "author_name", "keywords", "category"}) {
                if (detail.filter(detect.apply(functions.col(field)).isNotNull()).count() != 0) {
                    throw new AssertionError("Damaged field remains in DWD: " + field);
                }
            }
            String example = "SINA_NEWS_doc-inikfhfi3152264_001398";
            if (rejected.filter(functions.col("content_id").equalTo(example)).count() != 1
                    || detail.filter(functions.col("content_id").equalTo(example)).count() != 0) {
                throw new AssertionError("Reported damaged article was not quarantined");
            }
            var topicEvidence = spark.read().parquet(warehouse + "/ads/ads_topic_key_content");
            for (String field : new String[]{"title", "clean_text"}) {
                if (topicEvidence.filter(detect.apply(functions.col(field)).isNotNull()).count() != 0) {
                    throw new AssertionError("Damaged field remains in topic evidence: " + field);
                }
            }
            System.out.println("PASS: quarantine=" + rejectedCount + " DWD=" + retainedCount
                    + "; no damaged display fields in DWD/topic evidence; reported article removed.");
        }
    }
}
