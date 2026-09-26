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
/** 分析服务实现：整理数据库原始结果并计算页面需要的派生指标。 */
public class AnalyticsServiceImpl implements AnalyticsService {
    private static final String CANONICAL_EVENT_ID = "public_rss_latest";
    private static final Set<String> GENERIC_SOURCE_CATEGORIES = Set.of("未分类", "新闻", "微博", "微博文章");
    private static final Set<String> CATEGORY_LABELS = Set.of("财经", "政治", "科技", "体育", "文娱", "社会", "综合");
    private static final Set<String> SPREAD_PLATFORMS = Set.of("TENCENT_NEWS", "NETEASE_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER");

    private record CategoryActivation(String category, String platform, LocalDateTime firstHotTime,
                                      LocalDateTime peakTime, double peakAttention) {
    }
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
        List<Map<String, Object>> trendHeatTrend = mapper.trendHeatTrend(eventId);
        List<Map<String, Object>> trendPlatformSummary = mapper.trendPlatformSummary(eventId);
        // Dashboard views only render a small top-content subset. Returning the
        // complete table makes every frontend page deserialize and process an
        // unbounded payload, which can block the browser on large events.
        List<Map<String, Object>> trendContentRank = enrichContentRows(mapper.trendContentRank(eventId, 600));
        List<Map<String, Object>> platformCategoryHeat = mapper.platformCategoryHeat(eventId);
        List<Map<String, Object>> platformDailyHeat = mapper.platformDailyHeat(eventId);
        List<Map<String, Object>> recentPlatformTimeline = aggregateRecentPlatformTimeline(platformDailyHeat);
        Map<String, Object> categoryPropagation = analyzeCategoryPropagation(
                recentRows(mapper.platformCategoryHourlyHeat(eventId), "time_bucket", 7));
        List<Map<String, Object>> topicSummary = mapper.topicSummary(eventId, 80);
        List<Map<String, Object>> sentimentTrend = aggregateSentimentTrend(mapper.sentimentTrend(eventId));
        List<Map<String, Object>> contentRank = enrichContentRows(mapper.contentRank(eventId, 20));
        List<Map<String, Object>> realPublicContents = enrichContentRows(mapper.realPublicContents(eventId, 300));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("overview", normalizeOverview(mapper.overview(eventId), platformTimeline));
        data.put("heatTrend", heatTrend);
        data.put("platformTimeline", platformTimeline);
        data.put("recentPlatformTimeline", recentPlatformTimeline);
        data.put("trendHeatTrend", trendHeatTrend);
        data.put("trendPlatformSummary", trendPlatformSummary);
        data.put("trendContentRank", trendContentRank);
        data.put("platformCategoryHeat", platformCategoryHeat);
        data.put("categoryPropagationLinks", categoryPropagation.get("links"));
        data.put("categoryPropagationTimeline", categoryPropagation.get("timeline"));
        data.put("categoryPropagationSummary", categoryPropagation.get("summary"));
        data.put("platformDailyHeat", platformDailyHeat);
        data.put("topicTrend", mapper.topicTrend(eventId));
        data.put("topicSummary", topicSummary);
        data.put("topicKeyContents", topicSummary.isEmpty()
                ? List.of() : mapper.topicKeyContents(eventId, String.valueOf(topicSummary.get(0).get("topic_id"))));
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

    @Override
    public List<Map<String, Object>> keywordAnalysis(String eventId) {
        return enrichContentRows(mapper.keywordAnalysisRows(eventId, 10_000));
    }

    @Override
    public List<Map<String, Object>> sentimentAnalysis(String eventId) {
        return mapper.sentimentAnalysisRows(eventId);
    }
    @Override
    public List<Map<String, Object>> topicKeyContents(String eventId, String topicId) {
        return mapper.topicKeyContents(eventId, topicId);
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

    private List<Map<String, Object>> recentRows(List<Map<String, Object>> rows, String timeField, int days) {
        LocalDateTime latest = rows.stream()
                .map(row -> timestampValue(row.get(timeField)))
                .filter(value -> !value.equals(LocalDateTime.MAX))
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.MIN);
        if (latest.equals(LocalDateTime.MIN)) {
            return List.of();
        }
        LocalDateTime start = latest.toLocalDate().minusDays(Math.max(1, days) - 1L).atStartOfDay();
        return rows.stream()
                .filter(row -> {
                    LocalDateTime time = timestampValue(row.get(timeField));
                    return !time.equals(LocalDateTime.MAX) && !time.isBefore(start) && !time.isAfter(latest);
                })
                .toList();
    }

    private List<Map<String, Object>> aggregateRecentPlatformTimeline(List<Map<String, Object>> rows) {
        List<Map<String, Object>> recent = recentRows(rows, "time_bucket", 7);
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        LocalDateTime earliest = null;
        for (Map<String, Object> row : recent) {
            String platform = normalizePlatform(row.get("platform"));
            if (!SPREAD_PLATFORMS.contains(platform)) {
                continue;
            }
            LocalDateTime day = timestampValue(row.get("time_bucket"));
            if (earliest == null || day.isBefore(earliest)) {
                earliest = day;
            }
            Map<String, Object> target = grouped.computeIfAbsent(platform, ignored -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("platform", platform);
                item.put("first_publish_time", row.get("time_bucket"));
                item.put("content_count", 0L);
                item.put("hot_score", 0D);
                item.put("heat_weight", 0D);
                item.put("peak_heat_index", 0D);
                return item;
            });
            if (day.isBefore(timestampValue(target.get("first_publish_time")))) {
                target.put("first_publish_time", row.get("time_bucket"));
            }
            long count = longValue(row.get("content_count"));
            double averageHeat = doubleValue(row.get("average_heat_index"));
            addLong(target, "content_count", count);
            addDouble(target, "hot_score", averageHeat * count);
            addDouble(target, "heat_weight", averageHeat * count);
            target.put("peak_heat_index", Math.max(doubleValue(target.get("peak_heat_index")), doubleValue(row.get("peak_content_heat_index"))));
        }
        LocalDateTime base = earliest == null ? LocalDateTime.MIN : earliest;
        for (Map<String, Object> item : grouped.values()) {
            double weight = doubleValue(item.remove("heat_weight"));
            item.put("average_heat_index", weight <= 0D ? 0D : weight / Math.max(1L, longValue(item.get("content_count"))));
            item.put("delay_minutes", base.equals(LocalDateTime.MIN) ? 0L : Math.max(0L, ChronoUnit.MINUTES.between(base, timestampValue(item.get("first_publish_time")))));
        }
        return grouped.values().stream()
                .sorted(Comparator.comparingLong(item -> longValue(item.get("delay_minutes"))))
                .toList();
    }

    /**
     * Infers category-level lead/lag signals from hourly platform heat. This is
     * a statistical category response relationship, not a claim that one
     * concrete article was reposted by another platform.
     */
    private Map<String, Object> analyzeCategoryPropagation(List<Map<String, Object>> rows) {
        Map<String, Map<String, Map<LocalDateTime, Double>>> series = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String platform = normalizePlatform(row.get("platform"));
            String category = normalizeCategoryLabel(row.get("category"));
            LocalDateTime time = timestampValue(row.get("time_bucket"));
            if (!SPREAD_PLATFORMS.contains(platform) || category == null || time.equals(LocalDateTime.MAX)) {
                continue;
            }
            double count = doubleValue(row.get("content_count"));
            double platformCount = doubleValue(row.get("platform_content_count"));
            double averageHeat = Math.max(0D, doubleValue(row.get("average_relative_heat_index")));
            double coverageShare = platformCount <= 0D ? 0D : count / platformCount * 100D;
            double attention = Math.sqrt(Math.max(0D, coverageShare) * averageHeat);
            series.computeIfAbsent(category, ignored -> new HashMap<>())
                    .computeIfAbsent(platform, ignored -> new HashMap<>())
                    .merge(time, attention, Math::max);
        }

        Map<String, List<CategoryActivation>> platformActivations = new HashMap<>();
        for (Map.Entry<String, Map<String, Map<LocalDateTime, Double>>> categoryEntry : series.entrySet()) {
            for (Map.Entry<String, Map<LocalDateTime, Double>> platformEntry : categoryEntry.getValue().entrySet()) {
                List<Map.Entry<LocalDateTime, Double>> points = platformEntry.getValue().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .toList();
                Map.Entry<LocalDateTime, Double> peak = points.stream()
                        .max(Map.Entry.comparingByValue())
                        .orElse(null);
                if (peak == null || peak.getValue() < 35D) {
                    continue;
                }
                double activationThreshold = Math.max(35D, peak.getValue() * 0.7D);
                Map.Entry<LocalDateTime, Double> firstHot = points.stream()
                        .filter(point -> point.getValue() >= activationThreshold)
                        .findFirst()
                        .orElse(peak);
                CategoryActivation activation = new CategoryActivation(
                        categoryEntry.getKey(), platformEntry.getKey(), firstHot.getKey(), peak.getKey(), peak.getValue());
                platformActivations.computeIfAbsent(platformEntry.getKey(), ignored -> new ArrayList<>()).add(activation);
            }
        }

        Map<String, Map<String, Object>> linkMap = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Map<LocalDateTime, Double>>> categoryEntry : series.entrySet()) {
            List<CategoryActivation> activations = new ArrayList<>();
            for (String platform : categoryEntry.getValue().keySet()) {
                platformActivations.getOrDefault(platform, List.of()).stream()
                        .filter(item -> item.category().equals(categoryEntry.getKey()))
                        .findFirst()
                        .ifPresent(activations::add);
            }
            activations.sort(Comparator.comparing(CategoryActivation::firstHotTime));
            for (int index = 1; index < activations.size(); index++) {
                CategoryActivation source = activations.get(index - 1);
                CategoryActivation target = activations.get(index);
                long lagMinutes = ChronoUnit.MINUTES.between(source.firstHotTime(), target.firstHotTime());
                if (lagMinutes <= 0 || lagMinutes > 72 * 60L) {
                    continue;
                }
                String key = source.platform() + "::" + target.platform();
                Map<String, Object> link = linkMap.computeIfAbsent(key, ignored -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("source_platform", source.platform());
                    item.put("target_platform", target.platform());
                    item.put("link_count", 0L);
                    item.put("lag_minutes_total", 0L);
                    item.put("categories", new java.util.LinkedHashSet<String>());
                    return item;
                });
                addLong(link, "link_count", 1L);
                addLong(link, "lag_minutes_total", lagMinutes);
                @SuppressWarnings("unchecked")
                Set<String> categories = (Set<String>) link.get("categories");
                categories.add(source.category());
            }
        }

        List<Map<String, Object>> links = new ArrayList<>();
        for (Map<String, Object> link : linkMap.values()) {
            long count = longValue(link.get("link_count"));
            long lagTotal = longValue(link.get("lag_minutes_total"));
            link.put("average_lag_minutes", count == 0 ? 0D : Math.round((double) lagTotal / count * 10D) / 10D);
            link.put("category_label", String.join("、", (Set<String>) link.remove("categories")));
            link.remove("lag_minutes_total");
            link.put("relation_type", "category_heat_lead_lag");
            links.add(link);
        }
        links.sort(Comparator.comparingLong((Map<String, Object> item) -> longValue(item.get("link_count"))).reversed());

        LocalDateTime earliest = platformActivations.values().stream()
                .flatMap(List::stream)
                .map(CategoryActivation::firstHotTime)
                .min(LocalDateTime::compareTo)
                .orElse(LocalDateTime.MAX);
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (Map.Entry<String, List<CategoryActivation>> entry : platformActivations.entrySet()) {
            CategoryActivation first = entry.getValue().stream()
                    .min(Comparator.comparing(CategoryActivation::firstHotTime))
                    .orElse(null);
            if (first == null) {
                continue;
            }
            CategoryActivation peak = entry.getValue().stream()
                    .max(Comparator.comparing(CategoryActivation::peakAttention))
                    .orElse(first);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("platform", entry.getKey());
            item.put("lead_category", first.category());
            item.put("first_hot_time", first.firstHotTime());
            item.put("delay_minutes", earliest.equals(LocalDateTime.MAX) ? 0L : ChronoUnit.MINUTES.between(earliest, first.firstHotTime()));
            item.put("category_count", entry.getValue().stream().map(CategoryActivation::category).distinct().count());
            item.put("peak_attention", Math.round(peak.peakAttention() * 10D) / 10D);
            timeline.add(item);
        }
        timeline.sort(Comparator.comparingLong(item -> longValue(item.get("delay_minutes"))));

        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> firstPlatform = timeline.isEmpty() ? null : timeline.get(0);
        summary.put("category_count", series.size());
        summary.put("relation_count", links.stream().mapToLong(item -> longValue(item.get("link_count"))).sum());
        summary.put("platform_count", timeline.size());
        summary.put("lead_platform", firstPlatform == null ? null : firstPlatform.get("platform"));
        summary.put("lead_category", firstPlatform == null ? null : firstPlatform.get("lead_category"));
        summary.put("first_hot_time", firstPlatform == null ? null : firstPlatform.get("first_hot_time"));
        LocalDateTime windowStart = rows.stream().map(row -> timestampValue(row.get("time_bucket")))
                .filter(value -> !value.equals(LocalDateTime.MAX)).min(LocalDateTime::compareTo).orElse(null);
        LocalDateTime windowEnd = rows.stream().map(row -> timestampValue(row.get("time_bucket")))
                .filter(value -> !value.equals(LocalDateTime.MAX)).max(LocalDateTime::compareTo).orElse(null);
        summary.put("window_start", windowStart);
        summary.put("window_end", windowEnd);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("links", links);
        result.put("timeline", timeline);
        result.put("summary", summary);
        return result;
    }

    private String normalizeCategoryLabel(Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank() || GENERIC_SOURCE_CATEGORIES.contains(text) || text.matches("\\d+")) {
            return null;
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "finance" -> "财经";
            case "politics" -> "政治";
            case "technology" -> "科技";
            case "sports" -> "体育";
            case "culture", "娱乐", "影视", "音乐" -> "文娱";
            case "society", "社会新闻", "民生" -> "社会";
            case "general", "综合类", "新闻" -> "综合";
            default -> CATEGORY_LABELS.contains(text) ? text : null;
        };
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
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
        } catch (DateTimeParseException ignored) {
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
