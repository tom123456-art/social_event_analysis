package com.social.hotspot.service.impl;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Standalone regression checks; run main with the backend runtime classpath. */
public class CategoryPropagationRegression {
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 24, 0, 0);

    private static Map<String, Object> point(String platform, int hour, double attention) {
        return Map.of("platform", platform, "category", "technology", "time_bucket", BASE.plusHours(hour),
                "content_count", 1, "platform_content_count", 1,
                "average_relative_heat_index", attention * attention / 100);
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        AnalyticsServiceImpl service = new AnalyticsServiceImpl(null, null, null);
        Method method = AnalyticsServiceImpl.class.getDeclaredMethod("analyzeCategoryPropagation", List.class);
        method.setAccessible(true);
        Map<String, Object> empty = (Map<String, Object>) method.invoke(service, List.of());
        check(((List<?>) empty.get("timeline")).isEmpty(), "empty timeline");
        Map<String, Object> below = (Map<String, Object>) method.invoke(service,
                List.of(point("TENCENT_NEWS", 0, 34)));
        check(((List<?>) below.get("activations")).isEmpty(), "below 35 excluded");
        Map<String, Object> threshold = (Map<String, Object>) method.invoke(service,
                List.of(point("TENCENT_NEWS", 0, 41), point("TENCENT_NEWS", 1, 43), point("TENCENT_NEWS", 2, 60)));
        Map<String, Object> activation = (Map<String, Object>) ((List<?>) threshold.get("activations")).get(0);
        check(BASE.plusHours(1).equals(activation.get("first_hot_time")), "first point over 70% peak");
        check(Double.valueOf(42).equals(activation.get("threshold")), "70% peak threshold");
        Map<String, Object> tied = (Map<String, Object>) method.invoke(service,
                List.of(point("TENCENT_NEWS", 0, 35), point("NETEASE_NEWS", 0, 35)));
        Map<String, Object> summary = (Map<String, Object>) tied.get("summary");
        check(((List<?>) summary.get("lead_platforms")).size() == 2, "tied leads preserved");
        check(((List<?>) tied.get("relations")).isEmpty(), "same-hour pair has no direction");
        for (int hour : new int[]{72, 73}) {
            Map<String, Object> relation = (Map<String, Object>) method.invoke(service,
                    List.of(point("TENCENT_NEWS", 0, 50), point("NETEASE_NEWS", hour, 50)));
            check(((List<?>) relation.get("relations")).size() == (hour == 72 ? 1 : 0), "72-hour relation boundary");
        }
        System.out.println("PASS: empty input, minimum threshold, 70% peak, tied leads, same-hour exclusion, 72-hour boundary.");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
