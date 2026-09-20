package com.social.hotspot.service.impl;

import com.social.hotspot.mapper.AnalyticsMapper;
import com.social.hotspot.service.AnalyticsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AnalyticsServiceImpl implements AnalyticsService {
    private static final String CANONICAL_EVENT_ID = "public_rss_latest";
    private static final String CANONICAL_EVENT_NAME = "社交媒体热点事件传播分析";
    private static final Set<String> GENERIC_SOURCE_CATEGORIES = Set.of("未分类", "新闻", "微博", "微博文章");
    private static final List<String> RAW_COLUMNS = List.of(
            "event_id", "event_name", "platform", "content_id", "parent_content_id", "content_type",
            "title", "content_text", "author_id", "author_name", "publish_time", "crawl_time",
            "like_count", "comment_count", "repost_count", "share_count", "favorite_count", "view_count",
            "hot_rank", "location", "user_age_group", "user_gender", "keywords", "source_url", "image_url", "category"
    );
    private final AnalyticsMapper mapper;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public AnalyticsServiceImpl(AnalyticsMapper mapper, JdbcTemplate jdbcTemplate,
                            PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<Map<String, Object>> events() {
        return mapper.events();
    }

    @Override
    public Map<String, Object> dashboard(String eventId) {
        List<Map<String, Object>> heatTrend = aggregateHeatTrend(mapper.heatTrend(eventId));
        List<Map<String, Object>> platformTimeline = aggregatePlatformTimeline(mapper.platformTimeline(eventId));
        List<Map<String, Object>> sentimentTrend = aggregateSentimentTrend(mapper.sentimentTrend(eventId));
        List<Map<String, Object>> contentRank = enrichContentRows(mapper.contentRank(eventId, 20));
        List<Map<String, Object>> realPublicContents = enrichContentRows(mapper.realPublicContents(eventId, 300));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("overview", normalizeOverview(mapper.overview(eventId), platformTimeline));
        data.put("heatTrend", heatTrend);
        data.put("platformTimeline", platformTimeline);
        data.put("interaction", mapper.interaction(eventId));
        data.put("keywordRank", mapper.keywordRank(eventId, 30));
        data.put("sentimentTrend", sentimentTrend);
        data.put("contentRank", contentRank);
        data.put("realPublicContents", realPublicContents);
        data.put("categoryRank", categoryRank(realPublicContents.isEmpty() ? contentRank : realPublicContents));
        data.put("topicCount", mapper.topicCount(eventId));
        data.put("topicRank", mapper.topicRank(eventId, 30));
        data.put("propagationLinks", mapper.propagationLinks(eventId));
        data.put("topicPropagationLinks", mapper.topicPropagationLinks(eventId));
        data.put("noiseSummary", aggregateNoise(mapper.noiseSummary(eventId)));
        data.put("latestBatch", mapper.latestBatch(eventId));
        return data;
    }

    /**
     * Records from the deployed comment site are grouped into one platform. The
     * original comment URL remains the audit trail and no publisher dimension is
     * invented from the comment metadata.
     */
    private String normalizePlatform(Object value) {
        String platform = value == null ? "" : String.valueOf(value).trim();
        String upper = platform.toUpperCase(Locale.ROOT);
        if (upper.isBlank()) {
            return "UNKNOWN";
        }
        if (Set.of("TENCENT_NEWS", "NETEASE_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER", "WEIBO", "UNKNOWN").contains(upper)) {
            return upper;
        }
        // Unknown legacy values must not become extra chart dimensions. Active
        // crawlers use a fixed platform code; this fallback keeps old records readable.
        return upper.contains("RSS") || platform.contains("新闻") || platform.contains("中新网") || platform.contains("百度")
                ? "NEWS" : "UNKNOWN";
    }

    private Map<String, Object> normalizeOverview(Map<String, Object> overview,
                                                    List<Map<String, Object>> timeline) {
        Map<String, Object> result = overview == null ? new LinkedHashMap<>() : new LinkedHashMap<>(overview);
        long platformCount = timeline.stream().map(item -> normalizePlatform(item.get("platform"))).distinct().count();
        result.put("platform_count", platformCount);
        return result;
    }

    private List<Map<String, Object>> aggregateHeatTrend(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String platform = normalizePlatform(row.get("platform"));
            String time = String.valueOf(row.getOrDefault("time_bucket", ""));
            Map<String, Object> target = grouped.computeIfAbsent(time + "::" + platform, key -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("time_bucket", row.get("time_bucket"));
                item.put("platform", platform);
                item.put("content_count", 0L);
                item.put("hot_score", 0D);
                return item;
            });
            addLong(target, "content_count", row.get("content_count"));
            addDouble(target, "hot_score", row.get("hot_score"));
        }
        return grouped.values().stream()
                .sorted(Comparator.comparing(item -> String.valueOf(item.get("time_bucket"))))
                .toList();
    }

    private List<Map<String, Object>> aggregatePlatformTimeline(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        LocalDateTime earliest = null;
        for (Map<String, Object> row : rows) {
            String platform = normalizePlatform(row.get("platform"));
            LocalDateTime firstTime = timestampValue(row.get("first_publish_time"));
            if (earliest == null || firstTime.isBefore(earliest)) {
                earliest = firstTime;
            }
            Map<String, Object> target = grouped.computeIfAbsent(platform, key -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("platform", platform);
                item.put("first_publish_time", row.get("first_publish_time"));
                item.put("content_count", 0L);
                item.put("hot_score", 0D);
                return item;
            });
            if (firstTime.isBefore(timestampValue(target.get("first_publish_time")))) {
                target.put("first_publish_time", row.get("first_publish_time"));
            }
            addLong(target, "content_count", row.get("content_count"));
            addDouble(target, "hot_score", row.get("hot_score"));
        }
        LocalDateTime baseTime = earliest == null ? LocalDateTime.now() : earliest;
        for (Map<String, Object> item : grouped.values()) {
            long delay = Math.max(0, ChronoUnit.MINUTES.between(baseTime, timestampValue(item.get("first_publish_time"))));
            item.put("delay_minutes", delay);
        }
        return grouped.values().stream()
                .sorted(Comparator.comparingLong(item -> longValue(item.get("delay_minutes"))))
                .toList();
    }

    private List<Map<String, Object>> aggregateSentimentTrend(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String platform = normalizePlatform(row.get("platform"));
            String label = String.valueOf(row.getOrDefault("sentiment_label", "neutral"));
            String time = String.valueOf(row.getOrDefault("time_bucket", ""));
            Map<String, Object> target = grouped.computeIfAbsent(time + "::" + platform + "::" + label, key -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("time_bucket", row.get("time_bucket"));
                item.put("platform", platform);
                item.put("sentiment_label", label);
                item.put("sentiment_count", 0L);
                return item;
            });
            addLong(target, "sentiment_count", row.get("sentiment_count"));
        }
        return grouped.values().stream()
                .sorted(Comparator.comparing(item -> String.valueOf(item.get("time_bucket"))))
                .toList();
    }

    private List<Map<String, Object>> aggregateNoise(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String platform = normalizePlatform(row.get("platform"));
            Map<String, Object> target = grouped.computeIfAbsent(platform, key -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("platform", platform);
                for (String field : List.of("content_count", "duplicate_count", "high_freq_user_count", "noise_count")) {
                    item.put(field, 0L);
                }
                return item;
            });
            for (String field : List.of("content_count", "duplicate_count", "high_freq_user_count", "noise_count")) {
                addLong(target, field, row.get(field));
            }
        }
        return grouped.values().stream()
                .sorted(Comparator.comparingLong((Map<String, Object> item) -> longValue(item.get("content_count"))).reversed())
                .toList();
    }

    private List<Map<String, Object>> enrichContentRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("platform", normalizePlatform(row.get("platform")));
            String sourceCategory = String.valueOf(row.getOrDefault("category", "")).trim();
            boolean needsClassification = sourceCategory.isBlank() || GENERIC_SOURCE_CATEGORIES.contains(sourceCategory);
            String category = needsClassification ? categoryLabel(row) : sourceCategory;
            item.put("source_category", sourceCategory);
            item.put("category", categoryCode(category));
            item.put("category_label", category);
            item.put("hot_rank", row.get("rank_no"));
            item.put("content_text", row.get("clean_text"));
            item.put("url", row.get("source_url"));
            enriched.add(item);
        }
        return enriched;
    }

    private List<Map<String, Object>> categoryRank(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String label = String.valueOf(row.getOrDefault("category_label", categoryLabel(row)));
            Map<String, Object> target = grouped.computeIfAbsent(label, key -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("category", categoryCode(label));
                item.put("category_label", label);
                item.put("content_count", 0L);
                item.put("hot_score", 0D);
                item.put("top_title", row.getOrDefault("title", row.get("clean_text")));
                return item;
            });
            addLong(target, "content_count", 1);
            addDouble(target, "hot_score", row.get("hot_score"));
            if (doubleValue(row.get("hot_score")) > doubleValue(target.get("top_hot_score"))) {
                target.put("top_title", row.getOrDefault("title", row.get("clean_text")));
                target.put("top_hot_score", doubleValue(row.get("hot_score")));
            }
        }
        grouped.values().forEach(item -> item.remove("top_hot_score"));
        return grouped.values().stream()
                .sorted(Comparator.comparingDouble((Map<String, Object> item) -> doubleValue(item.get("hot_score"))).reversed())
                .toList();
    }

    private String categoryLabel(Map<String, Object> row) {
        String text = String.join(" ", String.valueOf(row.getOrDefault("title", "")),
                String.valueOf(row.getOrDefault("clean_text", "")),
                String.valueOf(row.getOrDefault("author_name", "")));
        if (containsAny(text, "股票", "基金", "证券", "银行", "保险", "融资", "资本", "央行", "经济", "金融", "财经", "股市", "投资", "贸易", "营收", "利润")) {
            return "财经";
        }
        if (containsAny(text, "人工智能", "AI", "芯片", "科技", "技术", "机器人", "算法", "数字化", "互联网", "量子", "航天", "新能源")) {
            return "科技";
        }
        if (containsAny(text, "政府", "国务院", "政策", "政治", "外交", "总统", "议会", "选举", "法治", "依法", "国际")) {
            return "政治";
        }
        if (containsAny(text, "足球", "篮球", "体育", "赛事", "比赛", "夺冠", "奥运", "球员", "联赛")) {
            return "体育";
        }
        if (containsAny(text, "电影", "电视剧", "演唱会", "明星", "文娱", "文化", "音乐", "展览", "非遗")) {
            return "文娱";
        }
        if (containsAny(text, "教育", "医疗", "交通", "民生", "旅游", "消费", "就业", "暴雨", "安全", "社区", "养老")) {
            return "社会";
        }
        return "综合";
    }

    private String categoryCode(String label) {
        return Map.of("财经", "finance", "政治", "politics", "科技", "technology", "体育", "sports",
                "文娱", "culture", "社会", "society", "综合", "general").getOrDefault(label, "general");
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private void addLong(Map<String, Object> target, String key, Object value) {
        target.put(key, longValue(target.get(key)) + longValue(value));
    }

    private void addDouble(Map<String, Object> target, String key, Object value) {
        target.put(key, doubleValue(target.get(key)) + doubleValue(value));
    }

    private long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return new BigDecimal(String.valueOf(value)).longValue();
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private double doubleValue(Object value) {
        if (value == null) {
            return 0D;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0D;
        }
    }

    private LocalDateTime timestampValue(Object value) {
        if (value == null) {
            return LocalDateTime.MAX;
        }
        String text = String.valueOf(value).replace('T', ' ');
        if (text.length() > 19) {
            text = text.substring(0, 19);
        }
        for (String pattern : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/M/d H:mm:ss", "yyyy/M/d H:mm")) {
            try {
                return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern));
            } catch (DateTimeParseException ignored) {
            }
        }
        return LocalDateTime.MAX;
    }

    @Override
    public Map<String, Object> adminOverview() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventCount", mapper.eventCount());
        data.put("topicCount", mapper.topicCount(CANONICAL_EVENT_ID));
        data.put("batches", mapper.batches(20));
        return data;
    }

    @Override
    public List<Map<String, Object>> batches(int limit) {
        return mapper.batches(limit);
    }

    @Override
    public List<Map<String, Object>> taskLogs(String batchId) {
        return mapper.taskLogs(batchId);
    }

    @Override
    public void clearReplacedDatasetHistory(String eventId, String currentBatchId) {
        transactionTemplate.executeWithoutResult(status -> {
            mapper.deleteTaskLogsForEventExceptBatch(eventId, currentBatchId);
            mapper.deleteBatchesForEventExceptBatch(eventId, currentBatchId);
        });
    }

    @Override
    public Map<String, Object> runLocalCsvEtl(Path rawCsvPath, String requestedEventId) throws Exception {
        if (!Files.exists(rawCsvPath)) {
            throw new IllegalArgumentException("Raw CSV 文件不存在，请先上传 Raw CSV");
        }
        String batchId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        LocalDateTime started = LocalDateTime.now();
        List<RawRow> sourceRows = readRawCsv(rawCsvPath);
        if (sourceRows.isEmpty()) {
            throw new IllegalArgumentException("Raw CSV 中没有可处理的数据");
        }
        List<RawRow> scopedRows = sourceRows;

        int duplicateCount = duplicateExcess(scopedRows);
        int dirtyCount = 0;
        List<RawRow> validRows = new ArrayList<>();
        for (RawRow row : scopedRows) {
            if (blank(row.contentId) || blank(row.title) || blank(row.contentText)
                    || row.publishTime == null || !isSupportedRow(row)) {
                dirtyCount++;
                continue;
            }
            validRows.add(row);
        }
        if (validRows.isEmpty()) {
            throw new IllegalArgumentException("Raw CSV 中没有受支持的真实采集数据，无法执行 ETL；请先准备符合 Raw Schema 的 CSV");
        }

        insertBatch(batchId, rawCsvPath, scopedRows.size(), validRows.size(), dirtyCount, duplicateCount, started);
        try {
            logTask(batchId, "ODS接入", "ODS", scopedRows.size(), scopedRows.size(), "SUCCESS", null);
            logTask(batchId, "DWD字段校验", "DWD", scopedRows.size(), validRows.size(), "SUCCESS", null);
            transactionTemplate.executeWithoutResult(status -> writeAds(validRows));
            logTask(batchId, "DWS聚合", "DWS", validRows.size(), validRows.size(), "SUCCESS", null);
            logTask(batchId, "ADS统计写入", "ADS", validRows.size(), validRows.size(), "SUCCESS", null);
            logTask(batchId, "MySQL同步", "MYSQL_SYNC", validRows.size(), validRows.size(), "SUCCESS", null);
            jdbcTemplate.update("""
                    update etl_batch set status='SUCCESS', current_stage='MYSQL_SYNC', finished_at=now(), updated_at=now()
                    where batch_id=?
                    """, batchId);
        } catch (Exception ex) {
            jdbcTemplate.update("""
                    update etl_batch set status='FAILED', current_stage='FAILED', finished_at=now(), error_message=?, updated_at=now()
                    where batch_id=?
                    """, truncate(ex.getMessage(), 1000), batchId);
            logTask(batchId, "ETL异常", "FAILED", validRows.size(), 0, "FAILED", ex.getMessage());
            throw ex;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batch_id", batchId);
        result.put("source_count", scopedRows.size());
        result.put("valid_count", validRows.size());
        result.put("dirty_count", dirtyCount);
        result.put("duplicate_count", duplicateCount);
        result.put("event_count", 1);
        result.put("status", "SUCCESS");
        result.put("message", "本地CSV ETL已完成：Raw -> DWD清洗 -> ADS聚合 -> MySQL同步");
        return result;
    }

    private void insertBatch(String batchId, Path rawCsvPath, int sourceCount, int validCount, int dirtyCount, int duplicateCount, LocalDateTime started) {
        jdbcTemplate.update("""
                insert into etl_batch(batch_id, event_id, source_path, source_count, valid_count, dirty_count, duplicate_count, status, current_stage, started_at)
                values(?, ?, ?, ?, ?, ?, ?, 'RUNNING', 'ODS', ?)
                """, batchId, CANONICAL_EVENT_ID, rawCsvPath.toAbsolutePath().toString(), sourceCount, validCount, dirtyCount, duplicateCount, Timestamp.valueOf(started));
    }

    private void logTask(String batchId, String taskName, String stage, int input, int output, String status, String error) {
        jdbcTemplate.update("""
                insert into etl_task_log(batch_id, task_name, task_stage, input_count, output_count, status, started_at, finished_at, error_message)
                values(?, ?, ?, ?, ?, ?, now(), now(), ?)
                """, batchId, taskName, stage, input, output, status, truncate(error, 1000));
    }

    private void writeAds(List<RawRow> rows) {
        cleanupLegacyEvents();
        deleteAds(CANONICAL_EVENT_ID);
        upsertEvent(rows);
        writeOverview(CANONICAL_EVENT_ID, rows);
        writeHeatTrend(CANONICAL_EVENT_ID, rows);
        writePlatformTimeline(CANONICAL_EVENT_ID, rows);
        writeInteraction(CANONICAL_EVENT_ID, rows);
        writeContentRank(CANONICAL_EVENT_ID, rows);
        writeKeywordRank(CANONICAL_EVENT_ID, rows);
        writeSentimentTrend(CANONICAL_EVENT_ID, rows);
        writeNoise(CANONICAL_EVENT_ID, rows);
    }

    private void deleteAds(String eventId) {
        List<String> tables = List.of("ads_event_overview", "ads_event_heat_trend", "ads_platform_spread_timeline",
                "ads_interaction_summary", "ads_keyword_rank", "ads_sentiment_trend", "ads_noise_summary", "ads_content_hot_rank");
        for (String table : tables) {
            jdbcTemplate.update("delete from " + table + " where event_id = ?", eventId);
        }
    }

    private void cleanupLegacyEvents() {
        List<String> tables = List.of("ads_event_overview", "ads_event_heat_trend", "ads_platform_spread_timeline",
                "ads_interaction_summary", "ads_keyword_rank", "ads_sentiment_trend", "ads_noise_summary", "ads_content_hot_rank");
        for (String table : tables) {
            jdbcTemplate.update("delete from " + table + " where event_id <> ?", CANONICAL_EVENT_ID);
        }
        jdbcTemplate.update("delete from event_info where event_id <> ?", CANONICAL_EVENT_ID);
    }

    private void upsertEvent(List<RawRow> rows) {
        LocalDateTime startTime = rows.stream().map(row -> row.publishTime).min(LocalDateTime::compareTo).orElse(LocalDateTime.now());
        LocalDateTime endTime = rows.stream().map(row -> row.publishTime).max(LocalDateTime::compareTo).orElse(startTime);
        jdbcTemplate.update("""
                insert into event_info(event_id, event_name, description, start_time, status)
                    values(?, ?, '由外部 Python 采集器提供 Raw CSV 后执行 ETL 生成的热点事件', ?, 'ACTIVE')
                on duplicate key update event_name=values(event_name), start_time=least(start_time, values(start_time)), end_time=greatest(coalesce(end_time, values(start_time)), values(start_time)), updated_at=now()
                """, CANONICAL_EVENT_ID, CANONICAL_EVENT_NAME, Timestamp.valueOf(startTime));
        jdbcTemplate.update("update event_info set end_time=?, updated_at=now() where event_id=?",
                Timestamp.valueOf(endTime), CANONICAL_EVENT_ID);
    }

    private void writeOverview(String eventId, List<RawRow> rows) {
        long users = rows.stream()
                .map(row -> blank(row.authorId) ? "" : row.authorId.trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .count();
        long platforms = rows.stream().map(this::platformDimension).filter(value -> !blank(value)).distinct().count();
        long comments = sum(rows, row -> row.commentCount);
        long reposts = sum(rows, row -> row.repostCount);
        long shares = sum(rows, row -> row.shareCount);
        long likes = sum(rows, row -> row.likeCount);
        long views = sum(rows, row -> row.viewCount);
        Map<String, Long> sentiment = rows.stream().collect(Collectors.groupingBy(row -> sentiment(row.text()), Collectors.counting()));
        RawRow peak = rows.stream().max(Comparator.comparingDouble(this::hotScore)).orElse(rows.get(0));
        jdbcTemplate.update("""
                insert into ads_event_overview(event_id, event_name, content_count, user_count, platform_count, comment_count, repost_count, share_count, like_count, view_count,
                  positive_count, neutral_count, negative_count, hot_score, peak_time)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, eventId, CANONICAL_EVENT_NAME, rows.size(), users, platforms,
                comments, reposts, shares, likes, views,
                sentiment.getOrDefault("positive", 0L), sentiment.getOrDefault("neutral", 0L), sentiment.getOrDefault("negative", 0L),
                BigDecimal.valueOf(rows.stream().mapToDouble(this::hotScore).sum()), Timestamp.valueOf(peak.publishTime));
    }

    private void writeHeatTrend(String eventId, List<RawRow> rows) {
        Map<String, List<RawRow>> groups = rows.stream().collect(Collectors.groupingBy(row -> bucket(row.publishTime) + "::" + platformDimension(row)));
        for (List<RawRow> group : groups.values()) {
            RawRow first = group.get(0);
            jdbcTemplate.update("""
                    insert into ads_event_heat_trend(event_id, time_bucket, platform, content_count, interaction_count, hot_score)
                    values(?, ?, ?, ?, ?, ?)
                    """, eventId, Timestamp.valueOf(bucket(first.publishTime)), platformDimension(first), group.size(),
                    group.stream().mapToLong(this::interactionCount).sum(),
                    BigDecimal.valueOf(group.stream().mapToDouble(this::hotScore).sum()));
        }
    }

    private void writePlatformTimeline(String eventId, List<RawRow> rows) {
        LocalDateTime min = rows.stream().map(row -> row.publishTime).min(LocalDateTime::compareTo).orElse(LocalDateTime.now());
        Map<String, List<RawRow>> groups = rows.stream().collect(Collectors.groupingBy(this::platformDimension));
        for (Map.Entry<String, List<RawRow>> entry : groups.entrySet()) {
            List<RawRow> group = entry.getValue();
            LocalDateTime firstTime = group.stream().map(row -> row.publishTime).min(LocalDateTime::compareTo).orElse(min);
            jdbcTemplate.update("""
                    insert into ads_platform_spread_timeline(event_id, platform, first_publish_time, delay_minutes, content_count, hot_score)
                    values(?, ?, ?, timestampdiff(minute, ?, ?), ?, ?)
                    """, eventId, entry.getKey(), Timestamp.valueOf(firstTime), Timestamp.valueOf(min), Timestamp.valueOf(firstTime),
                    group.size(), BigDecimal.valueOf(group.stream().mapToDouble(this::hotScore).sum()));
        }
    }

    private void writeInteraction(String eventId, List<RawRow> rows) {
        Map<String, List<RawRow>> groups = rows.stream().collect(Collectors.groupingBy(this::platformDimension));
        for (Map.Entry<String, List<RawRow>> entry : groups.entrySet()) {
            List<RawRow> group = entry.getValue();
            long comments = sum(group, row -> row.commentCount);
            long reposts = sum(group, row -> row.repostCount);
            long shares = sum(group, row -> row.shareCount);
            long likes = sum(group, row -> row.likeCount);
            long favorites = sum(group, row -> row.favoriteCount);
            long views = sum(group, row -> row.viewCount);
            jdbcTemplate.update("""
                    insert into ads_interaction_summary(event_id, platform, content_count, comment_count, repost_count, like_count, share_count, favorite_count, view_count, hot_score)
                    values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, eventId, entry.getKey(), group.size(), comments, reposts, likes, shares, favorites, views,
                    BigDecimal.valueOf(group.stream().mapToDouble(this::hotScore).sum()));
        }
    }

    private void writeContentRank(String eventId, List<RawRow> rows) {
        List<RawRow> ranked = rows.stream()
                .sorted(Comparator.comparing((RawRow row) -> blank(row.sourceUrl) ? 1 : 0)
                        .thenComparing(Comparator.comparingDouble(this::hotScore).reversed()))
                .toList();
        int rankPosition = 1;
        for (RawRow row : ranked) {
            jdbcTemplate.update("""
                    insert into ads_content_hot_rank(event_id, rank_no, platform, content_id, parent_content_id, content_type, title, clean_text,
                      author_id, author_name, publish_time, crawl_time, keywords, category, like_count, favorite_count, comment_count,
                      forward_count, repost_count, share_count, view_count, location, user_age_group, user_gender,
                      sentiment_label, hot_score, source_url, image_url)
                    values(
                      ?, ?, ?, ?, ?, ?, ?, ?,
                      ?, ?, ?, ?, ?, ?, ?, ?,
                      ?, ?, ?, ?, ?, ?, ?, ?,
                      ?, ?, ?, ?
                    )
                    """, eventId, rankPosition++, platformDimension(row), row.contentId, row.parentContentId, row.contentType,
                    truncate(row.title, 300), truncate(row.contentText, 1000), row.authorId, truncate(row.authorName, 100),
                    Timestamp.valueOf(row.publishTime), row.crawlTime == null ? null : Timestamp.valueOf(row.crawlTime),
                    truncate(row.keywords, 1000), truncate(row.category, 100), row.likeCount, row.favoriteCount, row.commentCount,
                    row.repostCount, row.repostCount, row.shareCount, row.viewCount, truncate(row.location, 100),
                    truncate(row.userAgeGroup, 50), truncate(row.userGender, 30), sentiment(row.text()), BigDecimal.valueOf(hotScore(row)),
                    truncate(row.sourceUrl, 800), truncate(row.imageUrl, 800));
        }
    }

    private long interactionCount(RawRow row) {
        return value(row.likeCount) + value(row.favoriteCount) + value(row.commentCount) + value(row.repostCount)
                + value(row.shareCount) + value(row.viewCount) / 100;
    }

    private long sum(List<RawRow> rows, java.util.function.Function<RawRow, Long> selector) {
        return rows.stream().map(selector).mapToLong(this::value).sum();
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private int duplicateExcess(List<RawRow> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (RawRow row : rows) {
            String key = platformDimension(row) + "::" + row.contentId;
            counts.merge(key, 1, Integer::sum);
        }
        return counts.values().stream().mapToInt(count -> Math.max(0, count - 1)).sum();
    }

    private boolean isNoise(RawRow row) {
        String text = row.text();
        return text.matches("(?s).*?(领取|福利|点击链接|加群|广告|刷屏).*?");
    }

    private long highFreqUserCount(List<RawRow> rows) {
        return rows.stream()
                .filter(row -> !blank(row.authorId))
                .collect(Collectors.groupingBy(row -> row.authorId, Collectors.counting()))
                .values().stream()
                .filter(count -> count > 3)
                .count();
    }

    private void writeKeywordRank(String eventId, List<RawRow> rows) {
        Map<String, KeywordAgg> map = new HashMap<>();
        for (RawRow row : rows) {
            for (String word : keywords(row)) {
                KeywordAgg agg = map.computeIfAbsent(word, key -> new KeywordAgg());
                agg.count++;
                agg.platformCount.merge(platformDimension(row), 1, Integer::sum);
                agg.sentimentCount.merge(sentiment(row.text()), 1, Integer::sum);
            }
        }
        map.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue().count, a.getValue().count)).limit(100).forEach(entry -> {
            KeywordAgg agg = entry.getValue();
            jdbcTemplate.update("""
                    insert into ads_keyword_rank(event_id, keyword, word_count, platform_top, sentiment_top)
                    values(?, ?, ?, ?, ?)
                    """, eventId, truncate(entry.getKey(), 100), agg.count, topKey(agg.platformCount), topKey(agg.sentimentCount));
        });
    }

    private void writeSentimentTrend(String eventId, List<RawRow> rows) {
        Map<String, Long> groups = rows.stream().collect(Collectors.groupingBy(row -> bucket(row.publishTime) + "::" + platformDimension(row) + "::" + sentiment(row.text()), Collectors.counting()));
        for (Map.Entry<String, Long> entry : groups.entrySet()) {
            String[] parts = entry.getKey().split("::", -1);
            jdbcTemplate.update("""
                    insert into ads_sentiment_trend(event_id, time_bucket, platform, sentiment_label, sentiment_count)
                    values(?, ?, ?, ?, ?)
                    """, eventId, Timestamp.valueOf(LocalDateTime.parse(parts[0])), parts[1], parts[2], entry.getValue());
        }
    }

    private void writeNoise(String eventId, List<RawRow> rows) {
        Map<String, List<RawRow>> groups = rows.stream().collect(Collectors.groupingBy(row -> bucket(row.publishTime) + "::" + platformDimension(row)));
        for (List<RawRow> group : groups.values()) {
            RawRow first = group.get(0);
            jdbcTemplate.update("""
                    insert into ads_noise_summary(event_id, time_bucket, platform, content_count, duplicate_count, high_freq_user_count, noise_count)
                    values(?, ?, ?, ?, ?, ?, ?)
                    """, eventId, Timestamp.valueOf(bucket(first.publishTime)), platformDimension(first), group.size(),
                    duplicateExcess(group), highFreqUserCount(group), group.stream().filter(this::isNoise).count());
        }
    }

    private String platformDimension(RawRow row) {
        String platform = row.platform == null ? "" : row.platform.trim();
        return platform.isBlank() ? "UNKNOWN" : platform.toUpperCase(Locale.ROOT);
    }

    private boolean isSupportedRow(RawRow row) {
        if (row.contentId == null) {
            return false;
        }
        String platform = row.platform == null ? "" : row.platform.toUpperCase(Locale.ROOT);
        if (row.contentId.startsWith(platform + "_IMPORTED_") && blank(row.sourceUrl)) {
            return Set.of("TENCENT_NEWS", "NETEASE_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER", "WEIBO").contains(platform);
        }
        if (row.sourceUrl == null) {
            return false;
        }
        String host = urlHost(row.sourceUrl);
        String path = urlPath(row.sourceUrl);
        return switch (platform) {
            case "TENCENT_NEWS" -> row.contentId.startsWith("TENCENT_NEWS_")
                    && Set.of("news.qq.com", "new.qq.com", "h5.news.qq.com", "view.inews.qq.com").contains(host);
            case "NETEASE_NEWS" -> row.contentId.startsWith("NETEASE_NEWS_")
                    && Set.of("www.163.com", "news.163.com", "c.m.163.com").contains(host);
            case "SOHU_NEWS" -> row.contentId.startsWith("SOHU_NEWS_")
                    && Set.of("news.sohu.com", "www.sohu.com", "q8.itc.cn").contains(host);
            case "SINA_NEWS" -> row.contentId.startsWith("SINA_NEWS_")
                    && (host.equals("sina.com.cn") || host.endsWith(".sina.com.cn") || host.equals("sina.cn") || host.endsWith(".sina.cn"));
            case "THE_PAPER" -> row.contentId.startsWith("THE_PAPER_") && "www.thepaper.cn".equals(host);
            case "WEIBO" -> row.contentId.startsWith("WEIBO_") && Set.of("weibo.com", "s.weibo.com").contains(host)
                    && (path.startsWith("/2/detail/") || path.equals("/ttarticle/p/show") || path.startsWith("/weibo"));
            default -> false;
        };
    }

    private String urlHost(String value) {
        try {
            String host = URI.create(value).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String urlPath(String value) {
        try {
            String path = URI.create(value).getPath();
            return path == null ? "" : path;
        } catch (Exception ignored) {
            return "";
        }
    }

    private List<RawRow> readRawCsv(Path path) throws Exception {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<RawRow> rows = new ArrayList<>();
        if (lines.isEmpty() || !RAW_COLUMNS.equals(parseCsvLine(stripBom(lines.get(0)).trim()))) {
            throw new IllegalArgumentException("活动 Raw 文件表头不是纯真实采集格式，应为：" + String.join(",", RAW_COLUMNS));
        }
        for (int i = 1; i < lines.size(); i++) {
            String line = stripBom(lines.get(i)).trim();
            if (line.isBlank()) {
                continue;
            }
            List<String> cells = parseCsvLine(line);
            if (cells.size() < RAW_COLUMNS.size()) {
                while (cells.size() < RAW_COLUMNS.size()) {
                    cells.add("");
                }
            }
            rows.add(RawRow.from(cells));
        }
        return rows;
    }

    private List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private List<String> keywords(RawRow row) {
        Set<String> stop = Set.of("新闻", "中新网", "记者", "日电", "公开", "内容", "进行", "表示", "一个", "今年", "近日");
        List<String> words = new ArrayList<>();
        String text = (blank(row.keywords) ? row.title + " " + row.contentText : row.keywords).replaceAll("[^\\u4e00-\\u9fa5A-Za-z0-9]+", " ");
        for (String word : text.split("\\s+")) {
            String value = word.trim();
            if (value.length() >= 2 && !stop.contains(value)) {
                words.add(value);
            }
        }
        return words;
    }

    private String sentiment(String value) {
        String text = value == null ? "" : value;
        if (text.contains("事故") || text.contains("死亡") || text.contains("质疑") || text.contains("争议")
                || text.contains("诉讼") || text.contains("判决") || text.contains("维权") || text.contains("欺骗")
                || text.contains("推诿") || text.contains("暴雨") || text.contains("违纪") || text.contains("违法")
                || text.contains("风险") || text.contains("暂停")) {
            return "negative";
        }
        if (text.contains("增长") || text.contains("精彩") || text.contains("夺冠") || text.contains("成功")
                || text.contains("火爆") || text.contains("奇妙") || text.contains("安康") || text.contains("成就")
                || text.contains("发展") || text.contains("收获") || text.contains("创新") || text.contains("满意")) {
            return "positive";
        }
        return "neutral";
    }

    private double hotScore(RawRow row) {
        double rankBoost = row.hotRank > 0 ? Math.max(1, 101 - row.hotRank) : 1;
        double engagement = value(row.likeCount) + value(row.commentCount) * 2D + value(row.repostCount) * 3D
                + value(row.shareCount) * 2D + value(row.favoriteCount) + value(row.viewCount) / 1000D;
        return rankBoost + Math.log1p(Math.max(0, engagement)) * 5D;
    }

    private LocalDateTime bucket(LocalDateTime value) {
        return value.withMinute(value.getMinute() / 10 * 10).withSecond(0).withNano(0);
    }

    private String topKey(Map<String, Integer> map) {
        return map.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
    }

    private String stripBom(String value) {
        return value == null ? "" : value.replaceFirst("^\\uFEFF", "");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String truncate(String value, int length) {
        if (value == null) {
            return null;
        }
        return value.length() <= length ? value : value.substring(0, length);
    }

    private static class KeywordAgg {
        int count;
        Map<String, Integer> platformCount = new HashMap<>();
        Map<String, Integer> sentimentCount = new HashMap<>();
    }

    private record RawRow(
            String eventId, String eventName, String platform, String contentId, String parentContentId, String contentType, String title,
            String contentText, String authorId, String authorName, LocalDateTime publishTime, LocalDateTime crawlTime, Long likeCount,
            Long commentCount, Long repostCount, Long shareCount, Long favoriteCount, Long viewCount, int hotRank, String location,
            String userAgeGroup, String userGender, String keywords, String sourceUrl, String imageUrl, String category
    ) {
        static RawRow from(List<String> cells) {
            return new RawRow(
                    CANONICAL_EVENT_ID, blankTo(cells.get(1), CANONICAL_EVENT_NAME), cells.get(2), blankToId(cells.get(3)), cells.get(4), blankTo(cells.get(5), "news"),
                    cells.get(6), cells.get(7), cells.get(8), cells.get(9), parseTime(cells.get(10)), parseTime(cells.get(11)),
                    parseNullableLong(cells.get(12)), parseNullableLong(cells.get(13)), parseNullableLong(cells.get(14)), parseNullableLong(cells.get(15)),
                    parseNullableLong(cells.get(16)), parseNullableLong(cells.get(17)), (int) parseLong(cells.get(18)), cells.get(19),
                    cells.get(20), cells.get(21), cells.get(22), cells.get(23), cells.get(24), cells.get(25)
                );
        }

        String text() {
            return title + " " + contentText + " " + keywords;
        }

        private static String blankToId(String value) {
            return value == null || value.isBlank() ? "csv_" + UUID.randomUUID() : value;
        }

        private static String blankTo(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }

        private static long parseLong(String value) {
            try {
                return Long.parseLong(value == null || value.isBlank() ? "0" : value.trim());
            } catch (NumberFormatException ex) {
                return 0;
            }
        }

        private static Long parseNullableLong(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private static LocalDateTime parseTime(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return OffsetDateTime.parse(value.trim())
                        .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                        .toLocalDateTime();
            } catch (DateTimeParseException ignored) {
                // Continue with the legacy local timestamp patterns below.
            }
            String text = value.trim().replace('T', ' ');
            for (String pattern : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/M/d H:mm:ss", "yyyy/M/d H:mm")) {
                try {
                    return LocalDateTime.parse(text.length() > 19 ? text.substring(0, 19) : text, DateTimeFormatter.ofPattern(pattern));
                } catch (DateTimeParseException ignored) {
                }
            }
            return null;
        }
    }
}
