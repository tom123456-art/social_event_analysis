package com.social.hotspot.service.impl;

import com.social.hotspot.mapper.AnalyticsMapper;
import com.social.hotspot.service.AnalyticsService;
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
    private static final Set<String> GENERIC_SOURCE_CATEGORIES = Set.of("未分类", "新闻", "微博", "微博文章");
    private final AnalyticsMapper mapper;
    private final TransactionTemplate transactionTemplate;

    public AnalyticsServiceImpl(AnalyticsMapper mapper, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
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

}
