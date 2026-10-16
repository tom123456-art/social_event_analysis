package com.social.hotspot.etl.sentiment;
import static com.social.hotspot.etl.cleaning.DataFrameColumns.hasColumn;
import static com.social.hotspot.etl.cleaning.DataFrameColumns.nullIfBlank;
import static com.social.hotspot.etl.cleaning.DataFrameColumns.safeCol;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import static org.apache.spark.sql.functions.*;

public final class SentimentResultEnricher {
    // 禁止实例化情感结果处理工具类。
    private SentimentResultEnricher() {}
    // 校验模型二分类概率与标签是否符合输出契约。
    public static Column validBinarySentiment() {
        // 读取模型正面概率。
        Column positive = col("sentiment_positive_score");
        // 读取模型负面概率。
        Column negative = col("sentiment_negative_score");
        // 标签应对应最大概率，相等时按推理脚本约定选正面。
        Column expectedLabel = when(positive.geq(negative), lit("positive")).otherwise(lit("negative"));
        // 检查概率非空、有限、范围合法、合计接近一且标签一致。
        return coalesce(positive.isNotNull().and(negative.isNotNull())
                .and(not(isnan(positive))).and(not(isnan(negative)))
                .and(positive.between(0D, 1D)).and(negative.between(0D, 1D))
                .and(abs(positive.plus(negative).minus(1D)).leq(0.000002D))
                .and(col("sentiment_label").equalTo(expectedLabel)), lit(false));
    }

    // 同时满足概率契约和最低置信度才可计入模型情感统计。
    public static Column highConfidenceSentiment(double minimum) {
        return validBinarySentiment().and(greatest(col("sentiment_positive_score"), col("sentiment_negative_score")).geq(minimum));
    }

    // 人工标签仅在审核通过且标签属于约定三类时生效。
    public static Column manualSentiment() {
        return col("_review_approved").and(col("_review_sentiment").isin("positive", "negative", "neutral"));
    }

    // 用有效人工标注覆盖模型标签，但不伪造模型概率。
    public static Dataset<Row> applyManualSentiment(Dataset<Row> data) {
        // 人工来源标记为 MANUAL，其余记录暂按模型来源处理。
        return data.withColumn("sentiment_source", when(manualSentiment(), lit("MANUAL")).otherwise(lit("MODEL")))
                // 人工通过记录采用审核标签。
                .withColumn("sentiment_label", when(manualSentiment(), col("_review_sentiment")).otherwise(col("sentiment_label")))
                // 人工标注没有模型正面概率。
                .withColumn("sentiment_positive_score", when(manualSentiment(), lit(null).cast("double")).otherwise(col("sentiment_positive_score")))
                // 人工标注没有模型负面概率。
                .withColumn("sentiment_negative_score", when(manualSentiment(), lit(null).cast("double")).otherwise(col("sentiment_negative_score")))
                // 人工概率保持空值，二分类模型中性概率固定为零。
                .withColumn("sentiment_neutral_score", when(manualSentiment(), lit(null).cast("double")).otherwise(lit(0D)));
    }

    // 保留所有有效正文，但只让人工或高置信度模型标签参与情感分析。
    public static Dataset<Row> applySentimentEligibility(Dataset<Row> data, double minimum) {
        // 不确定性不等于中性情感，也不应导致可用正文被删除。
        data = data.withColumn("_sentiment_status", coalesce(safeCol(data, "_sentiment_status"),
                when(coalesce(safeCol(data, "sentiment_source"), lit("")).equalTo("NOT_APPLICABLE"), lit("SKIPPED"))
                        .otherwise(lit("PREDICTED"))));
        // 先应用人工覆盖，再根据跳过状态和模型置信度确定资格。
        return applyManualSentiment(data)
                .withColumn("_sentiment_eligible", manualSentiment().or(
                        col("_sentiment_status").notEqual("SKIPPED").and(highConfidenceSentiment(minimum))))
                // 无资格记录区分不适用和低置信度，避免混入中性统计。
                .withColumn("sentiment_source", when(col("_sentiment_eligible"), col("sentiment_source"))
                        .otherwise(when(col("_sentiment_status").equalTo("SKIPPED"), lit("NOT_APPLICABLE"))
                                .otherwise(lit("LOW_CONFIDENCE"))))
                // 无资格记录的标签和概率全部置空。
                .withColumn("sentiment_label", when(col("_sentiment_eligible"), col("sentiment_label")))
                .withColumn("sentiment_positive_score", when(col("_sentiment_eligible"), col("sentiment_positive_score")))
                .withColumn("sentiment_negative_score", when(col("_sentiment_eligible"), col("sentiment_negative_score")))
                .withColumn("sentiment_neutral_score", when(col("_sentiment_eligible"), col("sentiment_neutral_score")))
                // 删除仅用于本步骤的资格判定列。
                .drop("_sentiment_eligible");
    }

    // 严格读取并验证 Python 推理结果文件。
    public static Dataset<Row> readSentimentInput(SparkSession spark, String input) {
        // CSV 解析异常立即失败，避免静默接收损坏的模型结果。
        Dataset<Row> inputRows = spark.read().option("header", "true").option("encoding", "UTF-8")
                .option("mode", "FAILFAST").csv(input);
        // 新版协议要求显式区分跳过记录和预测记录。
        if (!hasColumn(inputRows, "sentiment_status")) {
            throw new IllegalArgumentException("情感结果缺少前置筛选状态，请使用新版脚本重新生成结果。");
        }
        // 读取前置门控状态。
        Column status = col("sentiment_status");
        // 跳过记录不允许携带标签或任一概率。
        Column malformedSkip = status.equalTo("SKIPPED").and(
                nullIfBlank(col("sentiment_label")).isNotNull()
                        .or(nullIfBlank(col("sentiment_positive_score")).isNotNull())
                        .or(nullIfBlank(col("sentiment_neutral_score")).isNotNull())
                        .or(nullIfBlank(col("sentiment_negative_score")).isNotNull()));
        // 出现未知状态或伪造的跳过预测值时拒绝整个结果文件。
        if (inputRows.filter(status.isNull().or(not(status.isin("SKIPPED", "PREDICTED")))
                .or(malformedSkip)).count() > 0) {
            throw new IllegalArgumentException("情感结果状态无效或跳过记录携带了预测值，请重新生成结果。");
        }
        // 为关联字段增加前缀，避免与有效明细字段冲突。
        return inputRows
                .select(
                        upper(trim(col("platform"))).alias("_sentiment_platform"),
                        trim(col("content_id")).alias("_sentiment_content_id"),
                        lower(trim(col("sentiment_label"))).alias("_sentiment_label"),
                        col("sentiment_positive_score").cast("double").alias("_sentiment_positive_score"),
                        col("sentiment_neutral_score").cast("double").alias("_sentiment_neutral_score"),
                        col("sentiment_negative_score").cast("double").alias("_sentiment_negative_score"),
                        coalesce(safeCol(inputRows, "sentiment_status"), lit("PREDICTED")).alias("_sentiment_status")
                )
                // 跳过行保留状态，预测行必须具有合法标签。
                .filter(col("_sentiment_status").equalTo("SKIPPED")
                        .or(col("_sentiment_label").isin("positive", "neutral", "negative")))
                // 每个平台及内容 ID 最多保留一条推理结果。
                .dropDuplicates("_sentiment_platform", "_sentiment_content_id");
    }

    // 通过平台和内容 ID 将推理结果左关联到有效明细。
    public static Dataset<Row> applySentimentResults(Dataset<Row> data, Dataset<Row> sentiments) {
        // 清除历史推理状态，采用本次结果文件的状态。
        data = data.drop("_sentiment_status");
        // 左关联保证未匹配到情感结果的正文仍然保留。
        return data.join(sentiments,
                        data.col("platform").equalTo(sentiments.col("_sentiment_platform"))
                                .and(data.col("content_id").equalTo(sentiments.col("_sentiment_content_id"))),
                        "left")
                // 删除规范化阶段的默认标签，改用本次推理标签。
                .drop("sentiment_label")
                .withColumnRenamed("_sentiment_label", "sentiment_label")
                .withColumnRenamed("_sentiment_positive_score", "sentiment_positive_score")
                .withColumnRenamed("_sentiment_neutral_score", "sentiment_neutral_score")
                .withColumnRenamed("_sentiment_negative_score", "sentiment_negative_score")
                // 删除只用于关联的临时身份字段。
                .drop("_sentiment_platform", "_sentiment_content_id");
    }
}
