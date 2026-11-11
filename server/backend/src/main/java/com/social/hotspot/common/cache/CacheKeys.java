package com.social.hotspot.common.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 缓存 key 与过期策略，新增业务缓存时在这里集中定义。 */
public final class CacheKeys {
    // 页面分析结果统一缓存 30 分钟；ETL 或审核发布成功后会提前删除旧缓存。
    public static final Duration ANALYSIS_TTL = Duration.ofMinutes(30);
    // 新缓存按页面与事件隔离，避免一个页面读取其他页面的整包数据。
    private static final String ANALYSIS_PREFIX = "social-hotspot:analytics:page:v2:";
    // 保留旧版键前缀，仅用于数据更新时清理迁移前遗留的缓存。
    private static final String LEGACY_DASHBOARD_PREFIX = "social-hotspot:analytics:dashboard:binary-sentiment-v1:";

    private CacheKeys() {}

    /** 使用页面汇总缓存的五类页面；关键词、情感和内容明细不在此处缓存。 */
    public enum AnalysisPage {
        OVERVIEW, HOTSPOT_TREND, PLATFORM_SPREAD, ADMIN_HOME, SCREEN;

        // 生成“前缀 + 页面名 + 事件标识”的 Redis 键。
        public String key(String eventId) {
            // 每日响应使用独立版本，避免读到缺少逐日字段的旧缓存。
            String version = this == PLATFORM_SPREAD ? ":daily-response-v1:" : ":";
            return ANALYSIS_PREFIX + name().toLowerCase(Locale.ROOT) + version + eventKey(eventId);
        }
    }

    // ETL 或审核发布数据后，失效该事件的所有页面及迁移前的 dashboard 缓存。
    public static List<String> analysisKeys(String eventId) {
        List<String> keys = new ArrayList<>();
        for (AnalysisPage page : AnalysisPage.values()) keys.add(page.key(eventId));
        keys.add(LEGACY_DASHBOARD_PREFIX + eventKey(eventId));
        return keys;
    }

    // 缺少事件标识时使用固定占位，避免生成空的键后缀。
    private static String eventKey(String eventId) {
        return eventId == null || eventId.isBlank() ? "unknown" : eventId;
    }
}
