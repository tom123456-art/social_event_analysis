package com.social.hotspot.etl.job;
import static com.social.hotspot.etl.cleaning.DataFrameColumns.alignToSchema;
import static com.social.hotspot.etl.metric.InteractionHeatCalculator.applyInteractionPolicy;
import static com.social.hotspot.etl.sentiment.SentimentResultEnricher.applySentimentEligibility;
import static com.social.hotspot.etl.sentiment.SentimentResultEnricher.applySentimentResults;
import static com.social.hotspot.etl.aggregate.DwsSummaryAggregator.buildDwsSummaries;
import static com.social.hotspot.etl.metric.InteractionHeatCalculator.buildPlatformRelativeHeat;
import static com.social.hotspot.etl.cleaning.DataFrameColumns.ensureColumns;
import static com.social.hotspot.etl.sentiment.SentimentResultEnricher.highConfidenceSentiment;
import static com.social.hotspot.etl.tracking.EtlProgressRecorder.logTask;
import static com.social.hotspot.etl.sentiment.SentimentResultEnricher.manualSentiment;
import static com.social.hotspot.etl.config.JobArguments.parseArgs;
import static com.social.hotspot.etl.source.InputDatasetReader.readInput;
import static com.social.hotspot.etl.sentiment.SentimentResultEnricher.readSentimentInput;
import static com.social.hotspot.etl.config.JobArguments.required;
import static com.social.hotspot.etl.cleaning.RecordQualityExpressions.textQualityReason;
import static com.social.hotspot.etl.tracking.EtlProgressRecorder.truncate;
import static com.social.hotspot.etl.tracking.EtlProgressRecorder.upsertBatch;
import static com.social.hotspot.etl.publish.JdbcDatasetPublisher.upsertEvent;
import static com.social.hotspot.etl.sentiment.SentimentResultEnricher.validBinarySentiment;
import static com.social.hotspot.etl.publish.JdbcDatasetPublisher.writeJdbc;
import static com.social.hotspot.etl.warehouse.WarehouseDatasetWriter.writeWarehouseAds;
import static com.social.hotspot.etl.warehouse.WarehouseDatasetWriter.writeWarehouseDwd;
import com.social.hotspot.etl.cleaning.CleaningStage;
import com.social.hotspot.etl.cleaning.TextDamageDetector;
import com.social.hotspot.etl.warehouse.DwsSnapshotMaterializer;
import com.social.hotspot.etl.publish.AdsSnapshotPublisher;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.apache.spark.sql.functions.*;

/** Spark ETL 主任务：清洗社交内容、计算指标并写入 MySQL 分析表。 */
public class HotspotAnalysisJob {
    // A field must be non-zero in at least 1% of one platform's records before it participates in that platform''s score.
    public static void main(String[] args) throws Exception {
        Map<String, String> params = parseArgs(args);
        String input = required(params, "input");
        String sentimentInput = required(params, "sentiment-input");
        String eventId = required(params, "event-id");
        String requestedEventName = params.getOrDefault("event-name", "网络媒体热点事件传播态势与舆情分析");
        String eventName = "public_rss_latest".equals(eventId)
                ? "网络媒体热点事件传播态势与舆情分析"
                : requestedEventName;
        String batchId = params.getOrDefault("batch-id", DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()));
        String jdbcUrl = params.getOrDefault("jdbc-url", "jdbc:mysql://127.0.0.1:3306/social_hotspot_analytics?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false");
        String jdbcUser = params.getOrDefault("jdbc-user", "root");
        String jdbcPassword = params.getOrDefault("jdbc-password", System.getenv().getOrDefault("DB_PASSWORD", ""));
        String warehouseOutput = required(params, "warehouse-output");
        String etlMode = params.getOrDefault("etl-mode", "full").trim().toLowerCase();
        boolean incremental = "incremental".equals(etlMode);
        String baseDetail = params.get("base-detail");
        double sentimentMinConfidence = Double.parseDouble(params.getOrDefault("sentiment-min-confidence", "0.95"));
        if (!Double.isFinite(sentimentMinConfidence) || sentimentMinConfidence <= 0.5D || sentimentMinConfidence > 1D) {
            throw new IllegalArgumentException("情感最低置信度必须大于 0.5 且不超过 1，请检查配置。");
        }
        if (incremental && (warehouseOutput == null || warehouseOutput.isBlank()
                || baseDetail == null || baseDetail.isBlank())) {
            throw new IllegalArgumentException("增量处理需要配置数仓输出目录和上一批明细基线，请检查任务参数。");
        }

        SparkSession spark = SparkSession.builder()
                .appName("social-hotspot-etl-" + batchId)
                .config("spark.sql.session.timeZone", "Asia/Shanghai")
                .config("spark.sql.ansi.enabled", "false")
                .config("spark.sql.shuffle.partitions", "8")
                .config("spark.default.parallelism", "4")
                .getOrCreate();

        LocalDateTime startedAt = LocalDateTime.now();
        upsertBatch(jdbcUrl, jdbcUser, jdbcPassword, batchId, eventId, input, "RUNNING", "ODS", startedAt, null, null, 0, 0, 0, 0);

        try {
            Dataset<Row> parsed = ensureColumns(readInput(spark, input), "_corrupt_record").cache();
            long sourceCount = parsed.count();
            long structuralDirtyCount = parsed.filter(col("_corrupt_record").isNotNull()).count();
            // 清洗入口同时提供原始损坏证据和规范化视图；日志、隔离输出仍由作业负责；保留原始字段供损坏计数和隔离文件使用。
            CleaningStage.Prepared prepared = CleaningStage.prepare(parsed, eventId, eventName, batchId);
            Dataset<Row> raw = prepared.raw();
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "RawToOdsJob", "ODS", sourceCount, sourceCount, "SUCCESS", null);
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "DataQualityStructural", "DWD", sourceCount,
                    sourceCount - structuralDirtyCount, "SUCCESS", null);

            // 规范化视图携带审核和质量标记，供统计及核验使用。
            Dataset<Row> normalized = prepared.normalized();
            upsertBatch(jdbcUrl, jdbcUser, jdbcPassword, batchId, eventId, input, "RUNNING", "DWD", startedAt, null, null, sourceCount, 0, 0, 0);
            long textRepairedCount = normalized.filter(col("text_normalized")).count();
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "DataQualityTextNormalization", "DWD", sourceCount,
                    textRepairedCount, "SUCCESS", null);
            long textRejectedCount = raw.filter(col("text_quality_reason").isNotNull()).count();
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "DataQualityTextEncoding", "DWD", sourceCount,
                    sourceCount - textRejectedCount, "SUCCESS", null);
            System.out.println("text_quality_policy=" + TextDamageDetector.version() + " text_damage_discarded=" + textRejectedCount);
            if (textRejectedCount > 0 && warehouseOutput != null && !warehouseOutput.isBlank()) {
                raw.filter(col("text_quality_reason").isNotNull()).withColumn("batch_id", lit(batchId))
                        .write().mode(SaveMode.ErrorIfExists).parquet(warehouseOutput
                                + "/quarantine/text_encoding/batch_id=" + batchId);
            }
            // 统一执行有效性过滤和批内去重，作业层不再拼装规则；此处仍为去重前的质量通过数，与原任务日志口径一致。
            CleaningStage.Result cleaned = CleaningStage.clean(normalized);
            long candidateCount = cleaned.candidateCount();
            long dirtyCount = Math.max(0, sourceCount - candidateCount);
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "DataQualityCompletenessValidity", "DWD", sourceCount, candidateCount, "SUCCESS", null);

            // 重复数仅包含通过质量过滤的候选记录。
            long duplicateCount = cleaned.duplicateCount();
            // 从去重完成的明细开始合并情感结果。
            Dataset<Row> valid = cleaned.valid();
            Dataset<Row> sentimentAnalyzed = applySentimentResults(valid, readSentimentInput(spark, sentimentInput));
            long missingSentimentCount = sentimentAnalyzed
                    .filter(col("platform").isin("WEIBO", "SOHU_NEWS", "TENCENT_NEWS", "NETEASE_NEWS", "SINA_NEWS", "THE_PAPER")
                            .and(coalesce(col("_sentiment_status"), lit("MISSING")).notEqual("SKIPPED"))
                            .and(col("sentiment_label").isNull()).and(not(manualSentiment())))
                    .count();
            if (missingSentimentCount > 0) {
                throw new IllegalStateException("有 " + missingSentimentCount + " 条记录缺少情感模型结果，请重新执行情感分析。");
            }
            long invalidScores = sentimentAnalyzed.filter(not(manualSentiment())
                    .and(coalesce(col("_sentiment_status"), lit("MISSING")).notEqual("SKIPPED"))
                    .and(not(validBinarySentiment()))).count();
            if (invalidScores > 0) {
                throw new IllegalStateException("有 " + invalidScores + " 条记录的情感模型得分无效，请重新生成情感分析结果。");
            }
            long beforeConfidenceFilter = candidateCount - duplicateCount;
            long sentimentValidCount = sentimentAnalyzed.filter(manualSentiment().or(
                    col("_sentiment_status").equalTo("PREDICTED").and(highConfidenceSentiment(sentimentMinConfidence)))).count();
            Dataset<Row> scoredIncoming = sentimentAnalyzed;
            sentimentAnalyzed = applySentimentEligibility(sentimentAnalyzed, sentimentMinConfidence)
                    .persist(StorageLevel.MEMORY_AND_DISK());
            long processedValidCount = sentimentAnalyzed.count();
            long skippedSentimentCount = scoredIncoming.filter(col("_sentiment_status").equalTo("SKIPPED").and(not(manualSentiment()))).count();
            long confidenceRejectedCount = beforeConfidenceFilter - sentimentValidCount - skippedSentimentCount;
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "SentimentPolarityGate", "DWD",
                    beforeConfidenceFilter, beforeConfidenceFilter - skippedSentimentCount, "SUCCESS", null);
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "SentimentConfidenceFilter", "DWD",
                    beforeConfidenceFilter - skippedSentimentCount, sentimentValidCount, "SUCCESS", null);
            System.out.println("sentiment_min_confidence=" + sentimentMinConfidence
                    + " content_retained=" + processedValidCount + " sentiment_eligible=" + sentimentValidCount
                    + " skipped_retained=" + skippedSentimentCount + " low_confidence_retained=" + confidenceRejectedCount);
            Dataset<Row> completeCore = sentimentAnalyzed;
            if (incremental) {
                Dataset<Row> previous = applyInteractionPolicy(alignToSchema(spark.read().parquet(baseDetail), sentimentAnalyzed));
                Dataset<Row> damagedKeys = normalized.filter(col("text_quality_reason").isNotNull())
                        .select("event_id", "platform", "content_id", "content_type").distinct();
                previous = previous.join(damagedKeys,
                        new String[]{"event_id", "platform", "content_id", "content_type"}, "left_anti");
                previous = previous.filter(textQualityReason(previous).isNull());
                // Replace stale predictions without discarding newer usable content.
                Dataset<Row> combined = previous.unionByName(scoredIncoming, true);
                // 增量合并规则也由清洗包维护，按最近采集版本覆盖历史。
                Dataset<Row> merged = CleaningStage.rankIncremental(combined);
                long mergeDuplicates = merged.filter(col("_merge_rank").gt(1)).count();
                duplicateCount += mergeDuplicates;
                completeCore = applySentimentEligibility(merged.filter(col("_merge_rank").equalTo(1)).drop("_merge_rank"), sentimentMinConfidence);
            }
            Dataset<Row> detail = buildPlatformRelativeHeat(completeCore).persist(StorageLevel.MEMORY_AND_DISK());
            long fullValidCount = detail.count();
            long lostApprovals = normalized.filter(col("_review_approved")).select("_review_id").distinct()
                    .join(detail.select("_review_id").distinct(), new String[]{"_review_id"}, "left_anti").count();
            if (lostApprovals > 0) {
                throw new IllegalStateException("有 " + lostApprovals + " 条通过的审核记录未能进入分析，请检查重复来源、正文和人工情感标注；原有结果未被替换。");
            }
            if (fullValidCount == 0) {
                throw new IllegalStateException("清洗后没有可保留的记录，原有分析结果未被替换，请检查输入数据。");
            }
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "DataQualityUniqueness", "DWD", candidateCount, processedValidCount, "SUCCESS", null);
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "OdsToDwdCleanJob", "DWD", sourceCount, processedValidCount, "SUCCESS", null);
            if (warehouseOutput != null && !warehouseOutput.isBlank()) {
                writeWarehouseDwd(warehouseOutput, batchId, detail);
                detail.unpersist();
                detail = spark.read().parquet(warehouseOutput + "/dwd/dwd_social_content_detail/batch_id=" + batchId);
            }

            // Replace the committed ADS snapshot only after the input has passed
            // validation. A malformed CSV must not erase the last good result.
            upsertBatch(jdbcUrl, jdbcUser, jdbcPassword, batchId, eventId, input, "RUNNING", "DWS", startedAt, null, null, sourceCount, processedValidCount, dirtyCount, duplicateCount);
            Map<String, Dataset<Row>> summaries = buildDwsSummaries(detail, duplicateCount);
            Map<String, Dataset<Row>> dws = DwsSnapshotMaterializer.materialize(spark, warehouseOutput, batchId, summaries);
            Dataset<Row> warehouseHeatTrend = dws.get("dws_event_heat_summary");
            Dataset<Row> heatTrend = warehouseHeatTrend.select("event_id", "time_bucket", "platform", "content_count", "hot_score");
            Dataset<Row> platformDailyHeatAll = dws.get("dws_platform_daily_summary");
            Dataset<Row> platformDailyHeat = platformDailyHeatAll.select(col("event_id"), col("platform"),
                    col("heat_day").alias("time_bucket"), col("content_count"), col("average_heat_index"), col("high_heat_content_count"));
            Dataset<Row> platformCategoryHeatAll = dws.get("dws_platform_category_summary");
            Dataset<Row> platformCategoryHeat = platformCategoryHeatAll.select(col("event_id"), col("platform"),
                    col("category_day").alias("time_bucket"), col("category_label").alias("category"),
                    col("content_count"), col("composite_attention_index"));
            Dataset<Row> timeline = dws.get("dws_platform_spread_summary");
            Dataset<Row> interaction = timeline.select("event_id", "platform", "content_count", "hot_score");
            Dataset<Row> sentiment = dws.get("dws_sentiment_summary");
            Dataset<Row> keywordRank = dws.get("dws_keyword_summary").orderBy(col("word_count").desc());
            Dataset<Row> contentRank = dws.get("dws_content_rank");
            Dataset<Row> overview = dws.get("dws_event_summary");
            Dataset<Row> noise = dws.get("dws_noise_summary");
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "DwdToDwsAggregateJob", "DWS", fullValidCount, heatTrend.count(), "SUCCESS", null);
            upsertBatch(jdbcUrl, jdbcUser, jdbcPassword, batchId, eventId, input, "RUNNING", "ADS", startedAt, null, null, sourceCount, processedValidCount, dirtyCount, duplicateCount);
            writeWarehouseAds(warehouseOutput, dws);
            try (var publication = new AdsSnapshotPublisher(jdbcUrl, jdbcUser, jdbcPassword)) {
                writeJdbc(overview, jdbcUrl, jdbcUser, jdbcPassword, publication.stage("ads_event_overview"));
                writeJdbc(heatTrend, jdbcUrl, jdbcUser, jdbcPassword, publication.stage("ads_event_heat_trend"));
                writeJdbc(timeline, jdbcUrl, jdbcUser, jdbcPassword, publication.stage("ads_platform_spread_timeline"));
                writeJdbc(interaction, jdbcUrl, jdbcUser, jdbcPassword, publication.stage("ads_interaction_summary"));
                writeJdbc(keywordRank.limit(200), jdbcUrl, jdbcUser, jdbcPassword, publication.stage("ads_keyword_rank"));
                writeJdbc(sentiment, jdbcUrl, jdbcUser, jdbcPassword, publication.stage("ads_sentiment_trend"));
                writeJdbc(contentRank, jdbcUrl, jdbcUser, jdbcPassword, publication.stage("ads_content_hot_rank"));
                writeJdbc(noise, jdbcUrl, jdbcUser, jdbcPassword, publication.stage("ads_noise_summary"));
                writeJdbc(platformCategoryHeat, jdbcUrl, jdbcUser, jdbcPassword, publication.stage("ads_platform_category_heat"));
                writeJdbc(platformDailyHeat, jdbcUrl, jdbcUser, jdbcPassword, publication.stage("ads_platform_daily_heat"));
                publication.publish(eventId);
            }
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "DwsToAdsAndMysqlSyncJob", "ADS", fullValidCount, 10, "SUCCESS", null);

            upsertBatch(jdbcUrl, jdbcUser, jdbcPassword, batchId, eventId, input, "RUNNING", "MYSQL_SYNC", startedAt, null, null, sourceCount, processedValidCount, dirtyCount, duplicateCount);
            upsertEvent(jdbcUrl, jdbcUser, jdbcPassword, eventId, eventName, detail);
            upsertBatch(jdbcUrl, jdbcUser, jdbcPassword, batchId, eventId, input, "SUCCESS", "MYSQL_SYNC",
                    startedAt, LocalDateTime.now(), null, sourceCount, processedValidCount, dirtyCount, duplicateCount);
        } catch (Exception ex) {
            upsertBatch(jdbcUrl, jdbcUser, jdbcPassword, batchId, eventId, input, "FAILED", "FAILED",
                    startedAt, LocalDateTime.now(), truncate(ex.getMessage(), 1000), 0, 0, 0, 0);
            logTask(jdbcUrl, jdbcUser, jdbcPassword, batchId, "FullEtlJob", "FAILED", 0, 0, "FAILED", truncate(ex.getMessage(), 1000));
            throw ex;
        } finally {
            spark.stop();
        }
    }

    /** Projects a prior DWD snapshot onto the current cleaned-row schema before unioning a delta. */

    // 从关键词或标题中提取前两个词，作为热点话题的稳定标识。

}
