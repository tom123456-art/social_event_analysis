package com.social.hotspot.service.analysis;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.social.hotspot.service.analysis.AnalysisValues.timestampValue;
import static com.social.hotspot.service.analysis.CategoryPropagationAnalyzer.*;

/** Daily responses use daily peaks; weekly graphs and conclusions keep their weekly scope. */
public final class DailyCategoryResponses {
    private DailyCategoryResponses() {}

    public static List<Map<String, Object>> rowsForDay(List<Map<String, Object>> rows, LocalDate date) {
        return rows.stream().filter(row -> {
            LocalDateTime time = timestampValue(row.get("time_bucket"));
            return !time.equals(LocalDateTime.MAX) && time.toLocalDate().equals(date);
        }).toList();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> analyze(List<Map<String, Object>> rows) {
        LocalDate latest = rows.stream().map(row -> timestampValue(row.get("time_bucket")))
                .filter(time -> !time.equals(LocalDateTime.MAX)).max(LocalDateTime::compareTo)
                .map(LocalDateTime::toLocalDate).orElse(null);
        if (latest == null) return List.of();
        List<Map<String, Object>> days = new ArrayList<>();
        for (LocalDate date = latest.minusDays(6); !date.isAfter(latest); date = date.plusDays(1)) {
            Map<String, Object> analysis = analyzeCategoryPropagation(rowsForDay(rows, date));
            List<Map<String, Object>> timeline = (List<Map<String, Object>>) analysis.get("timeline");
            List<Map<String, Object>> activations = (List<Map<String, Object>>) analysis.get("activations");
            List<Map<String, Object>> observations = (List<Map<String, Object>>) analysis.get("hourly");
            List<Map<String, Object>> platforms = new ArrayList<>();
            for (String platform : List.of("TENCENT_NEWS", "NETEASE_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER")) {
                Map<String, Object> first = timeline.stream().filter(row -> platform.equals(row.get("platform")))
                        .findFirst().orElse(null);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("platform", platform);
                if (first == null) {
                    item.put("status", observations.stream().anyMatch(row -> platform.equals(row.get("platform")))
                            ? "below_threshold" : "no_data");
                } else {
                    item.putAll(first);
                    item.put("status", "activated");
                    item.put("lead_categories", activations.stream()
                            .filter(row -> platform.equals(row.get("platform"))
                                    && first.get("first_hot_time").equals(row.get("first_hot_time")))
                            .map(row -> row.get("category")).toList());
                }
                platforms.add(item);
            }
            days.add(Map.of("date", date.toString(), "platforms", platforms));
        }
        return days;
    }
}
