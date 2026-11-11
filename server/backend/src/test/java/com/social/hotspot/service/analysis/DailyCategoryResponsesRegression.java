package com.social.hotspot.service.analysis;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class DailyCategoryResponsesRegression {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        var rows = List.of(
                point("TENCENT_NEWS", "体育", "2026-09-24T09:15:59", 16),
                point("TENCENT_NEWS", "科技", "2026-09-25T10:23:38", 36),
                point("TENCENT_NEWS", "财经", "2026-09-25T10:23:59", 36),
                point("NETEASE_NEWS", "科技", "2026-09-25T10:30:00", 36),
                point("SOHU_NEWS", "科技", "2026-09-25T10:44:00", 1),
                point("TENCENT_NEWS", "科技", "2026-09-30T11:00:00", 100));
        var days = DailyCategoryResponses.analyze(rows);
        check(days.size() == 7 && days.getFirst().get("date").equals("2026-09-24")
                && days.getLast().get("date").equals("2026-09-30"), "seven exact calendar days");
        var platforms = (List<Map<String, Object>>) days.get(1).get("platforms");
        check(platforms.size() == 5, "all five platforms remain visible");
        check(platforms.get(0).get("first_hot_time").equals(LocalDateTime.parse("2026-09-25T10:23:00")), "seconds ignored without rounding hours");
        check(platforms.get(0).get("delay_minutes").equals(0L) && platforms.get(1).get("delay_minutes").equals(7L), "daily baseline and actual seven minute delay");
        check(((List<?>) platforms.get(0).get("lead_categories")).size() == 2, "same-minute category ties preserved");
        check(platforms.get(2).get("status").equals("below_threshold") && platforms.get(3).get("status").equals("no_data"), "low peak distinguished from absent data");
        var first = (List<Map<String, Object>>) days.getFirst().get("platforms");
        check(first.getFirst().get("status").equals("activated"), "daily threshold unaffected by later higher peak");
        var missing = (List<Map<String, Object>>) days.get(2).get("platforms");
        check(missing.stream().allMatch(row -> row.get("status").equals("no_data")), "missing whole day is not filled from previous day");
        check(DailyCategoryResponses.analyze(List.of()).isEmpty(), "empty dataset stays empty");
        System.out.println("PASS: seven daily rows, daily peaks, true minute lags, tied categories and missing data.");
    }

    private static Map<String, Object> point(String platform, String category, String time, double heat) {
        return Map.of("platform", platform, "category", category, "time_bucket", time,
                "content_count", 1L, "platform_content_count", 1L, "average_relative_heat_index", heat);
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
