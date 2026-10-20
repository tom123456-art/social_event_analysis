package com.social.hotspot.service.analysis;

import java.util.List;
import java.util.Map;

public class WeiboCategoryRegression {
    public static void main(String[] args) {
        List<Map<String, Object>> rows = List.of(
                Map.of("platform", "WEIBO", "category", "体育", "title", "足球", "hot_score", 100),
                Map.of("platform", " sina_weibo ", "category", "", "title", "股票", "hot_score", 200),
                Map.of("platform", "NETEASE_NEWS", "category", "科技", "title", "芯片", "hot_score", 5));
        var enriched = AnalysisResultAssembler.enrichContentRows(rows);
        for (var row : enriched.subList(0, 2)) {
            if (!"WEIBO".equals(row.get("platform"))) throw new AssertionError("platform alias");
            for (String field : List.of("category", "source_category", "category_label")) {
                if (!"".equals(row.get(field))) throw new AssertionError("Weibo category: " + field);
            }
        }
        if (!"technology".equals(enriched.get(2).get("category"))) throw new AssertionError("news category changed");
        for (var input : List.of(rows, enriched)) {
            var rank = AnalysisResultAssembler.categoryRank(input);
            if (rank.size() != 1 || !Long.valueOf(1).equals(rank.get(0).get("content_count"))) {
                throw new AssertionError("Weibo included in category rank");
            }
        }
        System.out.println("Weibo category regression passed.");
    }
}
