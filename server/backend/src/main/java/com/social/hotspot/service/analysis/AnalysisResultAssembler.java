package com.social.hotspot.service.analysis;
import static com.social.hotspot.service.analysis.AnalysisValues.addDouble;
import static com.social.hotspot.service.analysis.AnalysisValues.addLong;
import static com.social.hotspot.service.analysis.AnalysisValues.categoryCode;
import static com.social.hotspot.service.analysis.AnalysisValues.categoryLabel;
import static com.social.hotspot.service.analysis.AnalysisValues.doubleValue;
import static com.social.hotspot.service.analysis.AnalysisValues.GENERIC_SOURCE_CATEGORIES;
import static com.social.hotspot.service.analysis.AnalysisValues.longValue;
import static com.social.hotspot.service.analysis.AnalysisValues.normalizePlatform;
import static com.social.hotspot.service.analysis.CategoryPropagationAnalyzer.SPREAD_PLATFORMS;
import static com.social.hotspot.service.analysis.AnalysisValues.timestampValue;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AnalysisResultAssembler {
    private AnalysisResultAssembler() {}
    public static Map<String, Object> normalizeOverview(Map<String, Object> overview,
                                                    // 接收平台时间轴列表，用其中的标准平台代码重新统计平台数。
                                                    List<Map<String, Object>> timeline) {
        Map<String, Object> result = overview == null ? new LinkedHashMap<>() : new LinkedHashMap<>(overview);
        // 先规范平台代码，再去重计数；向处理结果写入平台数量。
        long platformCount = timeline.stream().map(item -> normalizePlatform(item.get("platform"))).distinct().count();
        result.put("platform_count", platformCount);
        return result;
    }

    public static List<Map<String, Object>> aggregateHeatTrend(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            // 统一平台代码并兼容旧值，不虚构发布平台；读取统计时间字段，缺失时使用空字符串。
            String platform = normalizePlatform(row.get("platform"));
            String time = String.valueOf(row.getOrDefault("time_bucket", ""));
            // 获取分组汇总记录，分组不存在时创建记录。
            Map<String, Object> target = grouped.computeIfAbsent(time + "::" + platform, key -> {
                Map<String, Object> item = new LinkedHashMap<>();
                // 组装当前记录，包含：统计时间、平台代码、内容数量。
                item.put("time_bucket", row.get("time_bucket"));
                item.put("platform", platform);
                item.put("content_count", 0L);
                // 向当前记录写入热度指标。
                item.put("hot_score", 0D);
                return item;
            });
            // 将当前行中的内容数量按整数累加到分组汇总记录的内容数量。
            addLong(target, "content_count", row.get("content_count"));
            // 将当前行中的热度指标按小数累加到分组汇总记录的热度指标。
            addDouble(target, "hot_score", row.get("hot_score"));
        }
        return grouped.values().stream()
                // 按统计时间升序排列。
                .sorted(Comparator.comparing(item -> String.valueOf(item.get("time_bucket"))))
                .toList();
    }

    public static List<Map<String, Object>> aggregatePlatformTimeline(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        // 空值。
        LocalDateTime earliest = null;
        for (Map<String, Object> row : rows) {
            // 统一平台代码并兼容旧值，不虚构发布平台；兼容日期格式，无法解析时用最大时间标记无效值。
            String platform = normalizePlatform(row.get("platform"));
            LocalDateTime firstTime = timestampValue(row.get("first_publish_time"));
            // 尚无最早时间或当前发布时间更早时，更新最早时间。
            if (earliest == null || firstTime.isBefore(earliest)) {
                // 更新最早时间，新值为当前最早时间。
                earliest = firstTime;
            }
            // 获取分组汇总记录，分组不存在时创建记录。
            Map<String, Object> target = grouped.computeIfAbsent(platform, key -> {
                Map<String, Object> item = new LinkedHashMap<>();
                // 组装当前记录，包含：平台代码、最早发布时间、内容数量。
                item.put("platform", platform);
                item.put("first_publish_time", row.get("first_publish_time"));
                item.put("content_count", 0L);
                // 向当前记录写入热度指标。
                item.put("hot_score", 0D);
                return item;
            });
            // 当前发布时间早于平台已记录时间时，更新平台最早发布时间。
            if (firstTime.isBefore(timestampValue(target.get("first_publish_time")))) {
                // 向分组汇总记录写入最早发布时间。
                target.put("first_publish_time", row.get("first_publish_time"));
            }
            // 将当前行中的内容数量按整数累加到分组汇总记录的内容数量。
            addLong(target, "content_count", row.get("content_count"));
            // 将当前行中的热度指标按小数累加到分组汇总记录的热度指标。
            addDouble(target, "hot_score", row.get("hot_score"));
        }
        // 用最早发布时间作为基准，缺失时使用当前时间。
        LocalDateTime baseTime = earliest == null ? LocalDateTime.now() : earliest;
        // 遍历分组汇总记录。
        for (Map<String, Object> item : grouped.values()) {
            // 计算平台最早发布时间相对全局最早时间的分钟差，并将负值限制为零；向当前记录写入相对最早时间的分钟差。
            long delay = Math.max(0, ChronoUnit.MINUTES.between(baseTime, timestampValue(item.get("first_publish_time"))));
            item.put("delay_minutes", delay);
        }
        return grouped.values().stream()
                // 按平台分钟延迟升序排列，最早出现或达标的平台放在前面。
                .sorted(Comparator.comparingLong(item -> longValue(item.get("delay_minutes"))))
                .toList();
    }

    public static List<Map<String, Object>> aggregateSentimentTrend(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            // 统一平台代码并兼容旧值，不虚构发布平台；读取情感标签，缺失时使用未知标签。
            String platform = normalizePlatform(row.get("platform"));
            String label = String.valueOf(row.getOrDefault("sentiment_label", "unknown"));
            // 跳过既不是正面也不是负面的情感记录。
            if (!"positive".equals(label) && !"negative".equals(label) && !"neutral".equals(label)) continue;
            // 读取统计时间字段，缺失时使用空字符串。
            String time = String.valueOf(row.getOrDefault("time_bucket", ""));
            // 获取分组汇总记录，分组不存在时创建记录。
            Map<String, Object> target = grouped.computeIfAbsent(time + "::" + platform + "::" + label, key -> {
                Map<String, Object> item = new LinkedHashMap<>();
                // 组装当前记录，包含：统计时间、平台代码、情感标签。
                item.put("time_bucket", row.get("time_bucket"));
                item.put("platform", platform);
                item.put("sentiment_label", label);
                // 向当前记录写入情感样本数。
                item.put("sentiment_count", 0L);
                return item;
            });
            // 将当前行中的情感样本数按整数累加到分组汇总记录的情感样本数。
            addLong(target, "sentiment_count", row.get("sentiment_count"));
        }
        return grouped.values().stream()
                // 按统计时间升序排列。
                .sorted(Comparator.comparing(item -> String.valueOf(item.get("time_bucket"))))
                .toList();
    }

    public static List<Map<String, Object>> aggregateNoise(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            // 统一平台代码并兼容旧值，不虚构发布平台。
            String platform = normalizePlatform(row.get("platform"));
            // 获取分组汇总记录，分组不存在时创建记录。
            Map<String, Object> target = grouped.computeIfAbsent(platform, key -> {
                Map<String, Object> item = new LinkedHashMap<>();
                // 向当前记录写入平台代码。
                item.put("platform", platform);
                // 依次处理内容量、重复数和噪声数三个字段。
                for (String field : List.of("content_count", "duplicate_count", "noise_count")) {
                    // 将当前内容量、重复数或噪声数字段初始化为长整数零。
                    item.put(field, 0L);
                }
                return item;
            });
            // 依次处理内容量、重复数和噪声数三个字段。
            for (String field : List.of("content_count", "duplicate_count", "noise_count")) {
                // 把当前行对应字段按整数累加到平台汇总记录。
                addLong(target, field, row.get(field));
            }
        }
        return grouped.values().stream()
                // 按指定字段降序排列，数值较大的记录排在前面。
                .sorted(Comparator.comparingLong((Map<String, Object> item) -> longValue(item.get("content_count"))).reversed())
                .toList();
    }

    public static List<Map<String, Object>> recentRows(List<Map<String, Object>> rows, String timeField, int days) {
        // 将记录列表转换为数据流，查找最新统计时间。
        LocalDateTime latest = rows.stream()
                // 将每条记录的指定时间字段转换为日期时间；排除解析失败后使用最大日期时间标记的记录；取最新统计时间。
                .map(row -> timestampValue(row.get(timeField)))
                .filter(value -> !value.equals(LocalDateTime.MAX))
                .max(LocalDateTime::compareTo)
                // 没有有效时间时使用最小日期时间。
                .orElse(LocalDateTime.MIN);
        // 未找到有效统计时间时进入空结果分支。
        if (latest.equals(LocalDateTime.MIN)) {
            // 返回空列表。
            return List.of();
        }
        // 从样本最新日期往前取指定自然日数，并将起点设为当天零点，不以系统当前时间为基准。
        LocalDateTime start = latest.toLocalDate().minusDays(Math.max(1, days) - 1L).atStartOfDay();
        return rows.stream()
                // 逐条判断记录时间是否位于所选日期窗口内。
                .filter(row -> {
                    // 兼容日期格式，无法解析时用最大时间标记无效值。
                    LocalDateTime time = timestampValue(row.get(timeField));
                    // 返回!time.equals(LocalDateTime.MAX) && !time.isBefore(start) && !time.isAfter(latest)。
                    return !time.equals(LocalDateTime.MAX) && !time.isBefore(start) && !time.isAfter(latest);
                })
                .toList();
    }

    public static List<Map<String, Object>> aggregateRecentPlatformTimeline(List<Map<String, Object>> rows) {
        // 以数据最新日期为基准筛选最近若干自然日；空值。
        List<Map<String, Object>> recent = recentRows(rows, "time_bucket", 7);
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        LocalDateTime earliest = null;
        // 遍历最近七个自然日的记录。
        for (Map<String, Object> row : recent) {
            // 统一平台代码并兼容旧值，不虚构发布平台。
            String platform = normalizePlatform(row.get("platform"));
            // 只接受参与分析的固定新闻平台代码。
            if (!SPREAD_PLATFORMS.contains(platform)) {
                // 跳过当前元素，继续下一轮。
                continue;
            }
            // 兼容日期格式，无法解析时用最大时间标记无效值。
            LocalDateTime day = timestampValue(row.get("time_bucket"));
            // 尚无最早日期或当前统计日期更早时，更新最早日期。
            if (earliest == null || day.isBefore(earliest)) {
                // 更新最早时间，新值为日度时间。
                earliest = day;
            }
            // 获取分组汇总记录，分组不存在时创建记录。
            Map<String, Object> target = grouped.computeIfAbsent(platform, ignored -> {
                Map<String, Object> item = new LinkedHashMap<>();
                // 组装当前记录，包含：平台代码、最早发布时间、内容数量。
                item.put("platform", platform);
                item.put("first_publish_time", row.get("time_bucket"));
                item.put("content_count", 0L);
                // 组装当前记录，包含：热度指标、按内容量加权的热度累计值。
                item.put("hot_score", 0D);
                item.put("heat_weight", 0D);
                return item;
            });
            // 当前日期早于平台已记录日期时，更新平台最早日期。
            if (day.isBefore(timestampValue(target.get("first_publish_time")))) {
                // 向分组汇总记录写入最早发布时间。
                target.put("first_publish_time", row.get("time_bucket"));
            }
            // 安全转为长整数，空值和格式错误按零处理；安全转为小数，空值和格式错误按零处理。
            long count = longValue(row.get("content_count"));
            double averageHeat = doubleValue(row.get("average_heat_index"));
            // 将数量按整数累加到分组汇总记录的内容数量。
            addLong(target, "content_count", count);
            // 累计日均相对热度乘内容量。
            addDouble(target, "hot_score", averageHeat * count);
            // 累计加权热度分子，以每日内容量为权重。
            addDouble(target, "heat_weight", averageHeat * count);
        }
        // 用最早发布时间作为基准，缺失时使用最小日期时间。
        LocalDateTime base = earliest == null ? LocalDateTime.MIN : earliest;
        // 遍历分组汇总记录。
        for (Map<String, Object> item : grouped.values()) {
            // 安全转为小数，空值和格式错误按零处理；加权热度累计值除以总内容量，得到窗口内平均相对热度；分母至少为一；向当前记录写入相对最早时间的分钟差。
            double weight = doubleValue(item.remove("heat_weight"));
            item.put("average_heat_index", weight <= 0D ? 0D : weight / Math.max(1L, longValue(item.get("content_count"))));
            item.put("delay_minutes", base.equals(LocalDateTime.MIN) ? 0L : Math.max(0L, ChronoUnit.MINUTES.between(base, timestampValue(item.get("first_publish_time")))));
        }
        return grouped.values().stream()
                // 按平台分钟延迟升序排列，最早出现或达标的平台放在前面。
                .sorted(Comparator.comparingLong(item -> longValue(item.get("delay_minutes"))))
                .toList();
    }

    public static List<Map<String, Object>> enrichContentRows(List<Map<String, Object>> rows) {
        // 创建补充字段后的内容列表。
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            // 创建当前记录，复制原行；向当前记录写入平台代码；读取原始类别并去除首尾空白。
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("platform", normalizePlatform(row.get("platform")));
            String sourceCategory = String.valueOf(row.getOrDefault("category", "")).trim();
            // 微博不判断类别，也不使用新闻的关键词兜底分类。
            boolean weibo = "WEIBO".equals(item.get("platform"));
            if (weibo) sourceCategory = "";
            boolean needsClassification = sourceCategory.isBlank() || GENERIC_SOURCE_CATEGORIES.contains(sourceCategory);
            String category = weibo ? "" : (needsClassification ? categoryLabel(row) : sourceCategory);
            item.put("source_category", sourceCategory);
            // 组装当前记录，包含：内容类别、类别中文名、正文。
            item.put("category", category.isBlank() ? "" : categoryCode(category));
            item.put("category_label", category);
            item.put("content_text", row.get("clean_text"));
            // 向当前记录写入页面来源链接；把当前记录加入补充字段后的内容列表。
            item.put("url", row.get("source_url"));
            enriched.add(item);
        }
        // 返回补充字段后的内容列表。
        return enriched;
    }

    public static List<Map<String, Object>> categoryRank(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if ("WEIBO".equals(normalizePlatform(row.get("platform")))) continue;
            // 读取类别标签，缺失时使用关键词分类结果。
            String label = String.valueOf(row.getOrDefault("category_label", categoryLabel(row)));
            // 获取分组汇总记录，分组不存在时创建记录。
            Map<String, Object> target = grouped.computeIfAbsent(label, key -> {
                Map<String, Object> item = new LinkedHashMap<>();
                // 组装当前记录，包含：内容类别、类别中文名、内容数量。
                item.put("category", categoryCode(label));
                item.put("category_label", label);
                item.put("content_count", 0L);
                // 组装当前记录，包含：热度指标、代表内容标题。
                item.put("hot_score", 0D);
                item.put("top_title", row.getOrDefault("title", row.get("clean_text")));
                return item;
            });
            // 将1按整数累加到分组汇总记录的内容数量。
            addLong(target, "content_count", 1);
            // 将当前行中的热度指标按小数累加到分组汇总记录的热度指标。
            addDouble(target, "hot_score", row.get("hot_score"));
            // 当前内容热度超过类别内最高热度时，更新代表内容。
            if (doubleValue(row.get("hot_score")) > doubleValue(target.get("top_hot_score"))) {
                // 组装分组汇总记录，包含：代表内容标题、类别内最高单篇热度。
                target.put("top_title", row.getOrDefault("title", row.get("clean_text")));
                target.put("top_hot_score", doubleValue(row.get("hot_score")));
            }
        }
        // 移除每个汇总记录的内部计算字段。
        grouped.values().forEach(item -> item.remove("top_hot_score"));
        return grouped.values().stream()
                // 按指定字段降序排列，数值较大的记录排在前面。
                .sorted(Comparator.comparingDouble((Map<String, Object> item) -> doubleValue(item.get("hot_score"))).reversed())
                .toList();
    }
}
