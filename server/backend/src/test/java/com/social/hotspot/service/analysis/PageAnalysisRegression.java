package com.social.hotspot.service.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.social.hotspot.common.cache.RedisCache;
import com.social.hotspot.common.cache.CacheKeys;
import com.social.hotspot.common.cache.CacheKeys.AnalysisPage;
import com.social.hotspot.service.analysis.impl.*;
import com.social.hotspot.mapper.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/** 不连接 MySQL/Redis，验证各页面独立查询、响应、缓存、分页和证据窗口。 */
public class PageAnalysisRegression {
    private static final String EVENT = "public_rss_latest";
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 20, 0, 0);

    public static void main(String[] args) {
        pageQueriesAndCaches();
        samplesAndFilters();
        evidenceWindow();
        redisKeysAndFailureFallback();
        System.out.println("PASS: eight page services, isolated queries/cache keys, cache hits, sample fallback, date filters, pagination, evidence and Redis fallback.");
    }

    private static void pageQueriesAndCaches() {
        MemoryCache cache = new MemoryCache();
        Fixture portal = new Fixture();
        var overview = new OverviewServiceImpl(portal.mapper(OverviewMapper.class), cache);
        Map<String, Object> result = overview.overview(EVENT);
        fields(result, "overview", "realPublicContents", "latestBatch", "interaction", "categoryRank", "keywordRank");
        check(((Map<?, ?>) result.get("overview")).get("platform_count").equals(1L), "portal platform normalization");
        portal.only("platformTimeline", "overview", "realPublicContents", "latestBatch", "interaction", "keywordRank");
        check(overview.overview(EVENT) == result && portal.calls.size() == 6, "portal cache avoids every query");

        Fixture trendRows = new Fixture();
        var trend = new HotspotTrendServiceImpl(trendRows.mapper(HotspotTrendMapper.class), cache);
        Map<String, Object> trendResult = trend.hotspotTrend(EVENT);
        fields(trendResult, "platformDailyHeat", "platformCategoryHeat");
        trendRows.only("platformDailyHeat", "platformCategoryHeat");
        check(trend.hotspotTrend(EVENT) == trendResult && trendRows.calls.size() == 2, "trend has its own cache");

        Fixture spreadRows = new Fixture();
        var spread = new PlatformSpreadServiceImpl(spreadRows.mapper(PlatformSpreadMapper.class), cache);
        Map<String, Object> spreadResult = spread.platformSpread(EVENT);
        fields(spreadResult, "platformTimeline", "categoryPropagationLinks", "recentPlatformTimeline",
                "categoryPropagationTimeline", "categoryPropagationSummary", "platformDailyHeat", "categoryDailyResponses");
        spreadRows.only("platformTimeline", "platformDailyHeat", "platformCategoryHourlyHeat");
        check(spread.platformSpread(EVENT) == spreadResult && spreadRows.calls.size() == 3, "spread cache avoids hourly analysis query");

        Fixture adminRows = new Fixture();
        var admin = new AdminHomeServiceImpl(adminRows.mapper(AdminHomeMapper.class), cache);
        Map<String, Object> adminResult = admin.adminOverview(EVENT);
        fields(adminResult, "eventCount", "topicCount", "keywordRank", "batches", "platformTimeline", "contentRank", "noiseSummary");
        adminRows.only("eventCount", "topicCount", "batches", "keywordRank", "platformTimeline", "contentRank", "noiseSummary");
        adminRows.values.put("batches", List.of(Map.of("batch_id", "new-batch", "status", "RUNNING")));
        Map<String, Object> updatedAdmin = admin.adminOverview(EVENT);
        check(adminRows.calls.size() == 10, "admin cache skips graph queries but refreshes counts and batches");
        check(((List<?>) updatedAdmin.get("batches")).size() == 1, "batch state stays fresh despite chart cache");

        Fixture screenRows = new Fixture();
        var screen = new ScreenServiceImpl(screenRows.mapper(ScreenMapper.class), cache);
        Map<String, Object> screenResult = screen.screen(EVENT);
        fields(screenResult, "platformTimeline", "overview", "heatTrend", "contentRank", "latestBatch", "keywordRank",
                "realPublicContents", "categoryRank", "sentimentTrend");
        screenRows.only("platformTimeline", "overview", "heatTrend", "contentRank", "latestBatch", "keywordRank", "realPublicContents", "sentimentTrend");
        check(screen.screen(EVENT) == screenResult && screenRows.calls.size() == 8, "screen cache avoids all queries");
        check(cache.values.size() == AnalysisPage.values().length, "all five caches coexist for the same event");
        check(cache.writes == 5, "each page writes exactly one separate cache entry");
    }

    private static void samplesAndFilters() {
        Fixture empty = new Fixture();
        empty.values.put("realPublicContents", List.of());
        var fallback = new OverviewServiceImpl(empty.mapper(OverviewMapper.class), new MemoryCache()).overview(EVENT);
        check(!((List<?>) fallback.get("categoryRank")).isEmpty(), "portal category fallback uses hot contents");
        check(empty.calls.contains("contentRank"), "fallback query is local to portal");
        check(empty.arguments.get("realPublicContents").getFirst()[1].equals(300), "portal keeps per-category limit 300");
        check(empty.arguments.get("contentRank").getFirst()[1].equals(20), "portal fallback limit 20");

        Fixture keywordRows = new Fixture();
        var keywords = new KeywordAnalysisServiceImpl(keywordRows.mapper(KeywordAnalysisMapper.class));
        check(keywords.keywordAnalysis(EVENT).size() == 1, "keyword sample returned");
        keywordRows.only("keywordAnalysisRows");
        check(keywordRows.arguments.get("keywordAnalysisRows").getFirst()[1].equals(10_000), "keyword sample limit unchanged");
        Fixture sentimentRows = new Fixture();
        new SentimentAnalysisServiceImpl(sentimentRows.mapper(SentimentAnalysisMapper.class)).sentimentAnalysis(EVENT);
        sentimentRows.only("sentimentAnalysisRows");

        Fixture trendRows = new Fixture();
        var trend = new HotspotTrendServiceImpl(trendRows.mapper(HotspotTrendMapper.class), new MemoryCache());
        check(trend.trendContentRankByRange(EVENT, null, "2026-10-20").isEmpty(), "missing date rejects query");
        check(trend.trendContentRankByRange(EVENT, "2026-10-21", "2026-10-20").isEmpty(), "reversed dates reject query");
        check(trendRows.calls.isEmpty(), "invalid dates never hit mapper");
        trend.trendContentRankByRange(EVENT, "2026-10-19", "2026-10-20");
        trend.trendContentRankByRange(EVENT, "2026-10-19", "2026-10-20");
        check(trendRows.calls.size() == 2, "range query remains uncached");
        check(trendRows.arguments.get("trendContentRankByRange").getFirst()[3].equals(3000), "range limit 3000");

        Fixture contents = new Fixture();
        var page = new ContentServiceImpl(contents.mapper(ContentMapper.class)).contentPage(EVENT, "  chip  ", "全部", "", "technology", 2, 200);
        fields(page, "rows", "total", "page", "pageSize");
        Object[] list = contents.arguments.get("contentPage").getFirst();
        Object[] count = contents.arguments.get("contentCount").getFirst();
        check(Arrays.equals(Arrays.copyOf(list, 5), count), "list and count filters are identical");
        check(list[1].equals("chip") && list[2] == null && list[3] == null, "filter normalization unchanged");
        check(list[5].equals(100) && list[6].equals(100), "offset and page size cap unchanged");
        check(page.get("page").equals(2) && page.get("pageSize").equals(100), "pagination metadata unchanged");
    }

    private static void evidenceWindow() {
        Fixture rows = new Fixture();
        var service = new PlatformSpreadServiceImpl(rows.mapper(PlatformSpreadMapper.class), new MemoryCache());
        LocalDateTime selectedMinute = DAY.plusHours(10).plusMinutes(23);
        Map<String, Object> result = service.propagationEvidence(EVENT, "TENCENT_NEWS", "technology", selectedMinute.toString(), 2);
        fields(result, "items", "total", "page", "page_size", "window_start", "window_end", "analysis");
        Object[] list = rows.arguments.get("propagationContents").getFirst();
        Object[] count = rows.arguments.get("propagationContentCount").getFirst();
        check(Arrays.equals(Arrays.copyOf(list, 5), count), "evidence list/count filters match");
        check(list[2].equals("科技") && list[3].equals(selectedMinute) && list[4].equals(selectedMinute.plusMinutes(1)), "selected minute uses exact half-open minute range");
        check(list[5].equals(20) && list[6].equals(20), "evidence page 2 offset is 20");
        service.propagationEvidence(EVENT, "TENCENT_NEWS", null, null, 0);
        check(Collections.frequency(rows.calls, "platformCategoryHourlyHeat") == 2, "evidence recomputes without cache");
        Object[] full = rows.arguments.get("propagationContents").get(1);
        check(full[3].equals(DAY) && full[4].equals(DAY.plusDays(1)), "full window aligns to natural days");
        reject(() -> service.propagationEvidence(EVENT, "WEIBO", null, null, 1));
        reject(() -> service.propagationEvidence(EVENT, "TENCENT_NEWS", "not-a-category", null, 1));
        reject(() -> service.propagationEvidence(EVENT, "TENCENT_NEWS", null, DAY.plusSeconds(1).toString(), 1));
        reject(() -> service.propagationEvidence(EVENT, "TENCENT_NEWS", null, DAY.minusMinutes(1).toString(), 1));
        reject(() -> service.propagationEvidence(EVENT, "TENCENT_NEWS", null, DAY.plusDays(1).toString(), 1));
        Fixture noRows = new Fixture();
        noRows.values.put("platformCategoryHourlyHeat", List.of());
        Map<String, Object> noEvidence = new PlatformSpreadServiceImpl(noRows.mapper(PlatformSpreadMapper.class), new MemoryCache())
                .propagationEvidence(EVENT, "TENCENT_NEWS", null, null, 1);
        check(noEvidence.get("total").equals(0), "empty evidence window returns zero");
        noRows.only("platformCategoryHourlyHeat");
        service.propagationEvidence(EVENT, "TENCENT_NEWS", "科技", selectedMinute.toString(), 1, "2026-10-20");
        Object[] dated = rows.arguments.get("propagationContents").getLast();
        check(dated[3].equals(selectedMinute) && dated[4].equals(selectedMinute.plusMinutes(1)), "dated evidence retains actual minute");
        Map<String, Object> emptyDay = service.propagationEvidence(EVENT, "TENCENT_NEWS", "科技", null, 1, "2026-10-14");
        Object[] emptyRange = rows.arguments.get("propagationContents").getLast();
        check(emptyRange[3].equals(DAY.minusDays(6)) && emptyRange[4].equals(DAY.minusDays(5)), "empty day has its own exact day range");
        check(((List<?>) ((Map<?, ?>) emptyDay.get("analysis")).get("hourly")).isEmpty(), "empty day never reuses another day evidence");
        reject(() -> service.propagationEvidence(EVENT, "TENCENT_NEWS", null, null, 1, "2026-10-13"));
        reject(() -> service.propagationEvidence(EVENT, "TENCENT_NEWS", null, null, 1, "2026-10-21"));
        reject(() -> service.propagationEvidence(EVENT, "TENCENT_NEWS", null, null, 1, "2026-02-30"));
        reject(() -> service.propagationEvidence(EVENT, "TENCENT_NEWS", null, DAY.toString(), 1, "2026-10-19"));
    }

    private static void redisKeysAndFailureFallback() {
        RedisStub redis = new RedisStub();
        RedisCache cache = new RedisCache(redis, new ObjectMapper());
        for (AnalysisPage page : AnalysisPage.values()) {
            cache.put(page.key(EVENT), Map.of("page", page.name()), CacheKeys.ANALYSIS_TTL);
            check(cache.getMap(page.key(EVENT)).get("page").equals(page.name()), "page cache round trip: " + page);
        }
        check(redis.values.size() == 5 && redis.durations.stream().allMatch(Duration.ofMinutes(30)::equals), "five distinct 30-minute keys");
        cache.put(AnalysisPage.OVERVIEW.key("other-event"), Map.of("page", "other"), CacheKeys.ANALYSIS_TTL);
        cache.delete(CacheKeys.analysisKeys(EVENT));
        check(redis.deleted.size() == 6, "invalidate five pages and legacy dashboard key");
        check(redis.values.size() == 1 && cache.getMap(AnalysisPage.OVERVIEW.key("other-event")) != null, "invalidation is scoped to event");
        cache.put("test:other-module", List.of("value"), Duration.ofSeconds(45));
        check(cache.get("test:other-module", new TypeReference<List<String>>() {}).equals(List.of("value")), "generic typed JSON cache for other modules");
        check(redis.durations.getLast().equals(Duration.ofSeconds(45)), "caller supplies another module TTL");
        cache.delete("test:other-module");
        check(cache.getMap("test:missing") == null && !redis.values.containsKey("test:other-module"), "missing key and single deletion");
        cache.delete(List.of());
        check(AnalysisPage.OVERVIEW.key(null).equals(AnalysisPage.OVERVIEW.key(" ")), "blank event fallback unchanged");
        redis.fail = true;
        check(cache.getMap(AnalysisPage.OVERVIEW.key(EVENT)) == null, "Redis read failure falls back to mapper");
        cache.put(AnalysisPage.OVERVIEW.key(EVENT), Map.of(), CacheKeys.ANALYSIS_TTL);
        cache.delete(CacheKeys.analysisKeys(EVENT));
        redis.fail = false;
        redis.values.put("social-hotspot:analytics:page:v2:overview:" + EVENT, "not-json");
        check(cache.getMap(AnalysisPage.OVERVIEW.key(EVENT)) == null, "bad cached JSON falls back to mapper");
    }

    static void fields(Map<String, Object> result, String... names) {
        check(result.keySet().equals(new LinkedHashSet<>(Arrays.asList(names))), "response fields: " + result.keySet());
    }
    static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    static void reject(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid evidence parameter rejection");
    }

    static class MemoryCache extends RedisCache {
        final Map<String, Map<String, Object>> values = new HashMap<>();
        int writes;
        MemoryCache() { super(null, null); }
        @Override public Map<String, Object> getMap(String key) { return values.get(key); }
        @Override @SuppressWarnings("unchecked") public void put(String key, Object data, Duration ttl) {
            values.put(key, (Map<String, Object>) data);
            writes++;
        }
    }

    static class Fixture {
        final List<String> calls = new ArrayList<>();
        final Map<String, List<Object[]>> arguments = new HashMap<>();
        final Map<String, Object> values = new HashMap<>();
        Fixture() {
            Map<String, Object> content = Map.of("content_id", "news-1", "platform", "TENCENT_NEWS", "category", "科技",
                    "title", "芯片新闻", "clean_text", "芯片正文", "publish_time", DAY, "hot_score", 30D);
            values.put("overview", Map.of("content_count", 123L, "platform_count", 99L));
            values.put("platformTimeline", List.of(Map.of("platform", "TENCENT_NEWS", "first_publish_time", DAY, "content_count", 2L, "hot_score", 60D)));
            values.put("realPublicContents", List.of(content));
            values.put("contentRank", List.of(content));
            values.put("keywordAnalysisRows", List.of(content));
            values.put("trendContentRankByRange", List.of(content));
            values.put("sentimentAnalysisRows", List.of(content));
            values.put("propagationContents", List.of(content));
            values.put("contentPage", List.of(content));
            values.put("latestBatch", Map.of("batch_id", "batch-1"));
            values.put("eventCount", Map.of("event_count", 1L));
            values.put("topicCount", Map.of("topic_count", 1L));
            values.put("keywordRank", List.of(Map.of("keyword", "芯片", "word_count", 3L)));
            values.put("platformDailyHeat", List.of(Map.of("platform", "TENCENT_NEWS", "time_bucket", DAY, "content_count", 2L, "average_heat_index", 30D)));
            values.put("platformCategoryHourlyHeat", List.of(Map.of("platform", "TENCENT_NEWS", "category", "科技", "time_bucket", DAY,
                    "content_count", 2L, "platform_content_count", 2L, "average_relative_heat_index", 36D)));
        }
        <T> T mapper(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (proxy, method, args) -> {
                calls.add(method.getName());
                arguments.computeIfAbsent(method.getName(), ignored -> new ArrayList<>()).add(args);
                if (values.containsKey(method.getName())) return values.get(method.getName());
                return method.getReturnType() == long.class ? 1L : List.of();
            }));
        }
        void only(String... expected) { check(calls.equals(Arrays.asList(expected)), "unexpected mapper queries: " + calls); }
    }

    static class RedisStub extends StringRedisTemplate {
        final Map<String, String> values = new HashMap<>();
        final List<Duration> durations = new ArrayList<>();
        final Set<String> deleted = new HashSet<>();
        boolean fail;
        @Override @SuppressWarnings("unchecked") public ValueOperations<String, String> opsForValue() {
            return (ValueOperations<String, String>) Proxy.newProxyInstance(ValueOperations.class.getClassLoader(), new Class[]{ValueOperations.class}, (proxy, method, args) -> {
                if (fail) throw new IllegalStateException("Redis unavailable for regression");
                if (method.getName().equals("get")) return values.get(args[0]);
                if (method.getName().equals("set")) {
                    values.put((String) args[0], (String) args[1]);
                    durations.add((Duration) args[2]);
                    return null;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        }
        @Override public Long delete(Collection<String> keys) {
            if (fail) throw new IllegalStateException("Redis unavailable for regression");
            deleted.addAll(keys);
            keys.forEach(values::remove);
            return (long) keys.size();
        }
    }
}
