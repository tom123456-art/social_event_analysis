package com.social.hotspot.service.analysis;
import static com.social.hotspot.service.analysis.AnalysisValues.addLong;
import static com.social.hotspot.service.analysis.AnalysisValues.doubleValue;
import static com.social.hotspot.service.analysis.AnalysisValues.longValue;
import static com.social.hotspot.service.analysis.AnalysisValues.normalizeCategoryLabel;
import static com.social.hotspot.service.analysis.AnalysisValues.normalizePlatform;
import static com.social.hotspot.service.analysis.AnalysisValues.timestampValue;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CategoryPropagationAnalyzer {
    private CategoryPropagationAnalyzer() {}
    public static final Set<String> SPREAD_PLATFORMS = Set.of("TENCENT_NEWS", "NETEASE_NEWS", "SOHU_NEWS", "SINA_NEWS", "THE_PAPER");
    private record CategoryActivation(String category, String platform, LocalDateTime firstHotTime,
                                      LocalDateTime peakTime, double peakAttention) {
    }
    public static Map<String, Object> analyzeCategoryPropagation(List<Map<String, Object>> rows) {
        // 创建类别、平台、时间三级映射；创建小时计算依据列表。
        Map<String, Map<String, Map<LocalDateTime, Double>>> series = new HashMap<>();
        List<Map<String, Object>> hourlyEvidence = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            // 统一平台代码并兼容旧值，不虚构发布平台；规范类别，排除泛化类别与无效数字；兼容日期格式，无法解析时用最大时间标记无效值。
            String platform = normalizePlatform(row.get("platform"));
            String category = normalizeCategoryLabel(row.get("category"));
            LocalDateTime time = timestampValue(row.get("time_bucket"));
            // 只接受参与分析的固定新闻平台代码。
            if (!SPREAD_PLATFORMS.contains(platform) || category == null || time.equals(LocalDateTime.MAX)) {
                // 跳过当前元素，继续下一轮。
                continue;
            }
            // 安全转为小数，空值和格式错误按零处理；安全转为小数，空值和格式错误按零处理；读取类别平均相对热度，异常负值按零处理。
            double count = doubleValue(row.get("content_count"));
            double platformCount = doubleValue(row.get("platform_content_count"));
            double averageHeat = Math.max(0D, doubleValue(row.get("average_relative_heat_index")));
            // 计算类别内容占平台内容的百分比，平台内容量为零时取零以免除零；类别占比乘平均相对热度再开平方，得到类别关注指数；创建小时计算依据，复制原行。
            double coverageShare = platformCount <= 0D ? 0D : count / platformCount * 100D;
            double attention = Math.sqrt(Math.max(0D, coverageShare) * averageHeat);
            Map<String, Object> evidence = new LinkedHashMap<>(row);
            // 组装小时计算依据，包含：内容类别、平台代码、类别内容占比。
            evidence.put("category", category);
            evidence.put("platform", platform);
            evidence.put("coverage_share", coverageShare);
            // 向小时计算依据写入类别关注指数；把小时计算依据加入小时计算依据列表；分组第一次出现时创建容器，已有则复用并继续汇总。
            evidence.put("attention", attention);
            hourlyEvidence.add(evidence);
            series.computeIfAbsent(category, ignored -> new HashMap<>())
                    // 分组第一次出现时创建容器，已有则复用并继续汇总；合并同时间的数据，Math::max 表示保留较大值。
                    .computeIfAbsent(platform, ignored -> new HashMap<>())
                    .merge(time, attention, Math::max);
        }

        // 创建各平台的类别首达记录。
        Map<String, List<CategoryActivation>> platformActivations = new HashMap<>();
        // 遍历各类别的热度序列。
        for (Map.Entry<String, Map<String, Map<LocalDateTime, Double>>> categoryEntry : series.entrySet()) {
            // 遍历当前类别的各平台热度序列。
            for (Map.Entry<String, Map<LocalDateTime, Double>> platformEntry : categoryEntry.getValue().entrySet()) {
                // 将当前平台的时间与关注指数映射转换为数据流。
                List<Map.Entry<LocalDateTime, Double>> points = platformEntry.getValue().entrySet().stream()
                        // 按时间键升序排列，为寻找最早达标点准备有序序列。
                        .sorted(Map.Entry.comparingByKey())
                        .toList();
                // 将时间点列表转换为数据流，查找关注指数峰值。
                Map.Entry<LocalDateTime, Double> peak = points.stream()
                        // 取关注指数最高的时间点；没有匹配结果时使用空值。
                        .max(Map.Entry.comparingByValue())
                        .orElse(null);
                // 峰值不存在或低于 35 的序列，不参加首达关系分析。
                if (peak == null || peak.getValue() < 35D) {
                    // 跳过当前元素，继续下一轮。
                    continue;
                }
                // 取 35 与序列峰值的 70% 中较大者作为首达阈值，不是环比增幅；将时间点列表转换为数据流，查找首次达标记录。
                double activationThreshold = Math.max(35D, peak.getValue() * 0.7D);
                Map.Entry<LocalDateTime, Double> firstHot = points.stream()
                        // 只保留关注指数达到首达阈值的时间点；取筛选后第一条，按时间排序后就是最早达标点；没有满足阈值的记录时使用峰值记录。
                        .filter(point -> point.getValue() >= activationThreshold)
                        .findFirst()
                        .orElse(peak);
                // 创建首达记录。
                CategoryActivation activation = new CategoryActivation(
                        // 保存类别、平台、首达时间、峰值时间及峰值关注指数。
                        categoryEntry.getKey(), platformEntry.getKey(), firstHot.getKey(), peak.getKey(), peak.getValue());
                // 分组第一次出现时创建容器，已有则复用并继续汇总。
                platformActivations.computeIfAbsent(platformEntry.getKey(), ignored -> new ArrayList<>()).add(activation);
            }
        }

        // 创建平台对关系映射；创建逐类别先后关系列表。
        Map<String, Map<String, Object>> linkMap = new LinkedHashMap<>();
        List<Map<String, Object>> relations = new ArrayList<>();
        // 遍历各类别的热度序列。
        for (Map.Entry<String, Map<String, Map<LocalDateTime, Double>>> categoryEntry : series.entrySet()) {
            // 创建首达记录列表。
            List<CategoryActivation> activations = new ArrayList<>();
            // 遍历当前类别下的平台代码。
            for (String platform : categoryEntry.getValue().keySet()) {
                // 取得当前平台首达列表，缺少时用空列表，再寻找当前类别。
                platformActivations.getOrDefault(platform, List.of()).stream()
                        // 只保留当前类别的首达记录；取筛选后第一条，按时间排序后就是最早达标点；只有结果存在才添加。
                        .filter(item -> item.category().equals(categoryEntry.getKey()))
                        .findFirst()
                        .ifPresent(activations::add);
            }
            // 按首达时间升序排序，同一时间按平台代码排序。
            activations.sort(Comparator.comparing(CategoryActivation::firstHotTime).thenComparing(CategoryActivation::platform));
            // 从第二条首达记录开始，依次比较相邻平台。
            for (int index = 1; index < activations.size(); index++) {
                // 读取较早平台或源文件；读取分组汇总记录；计算相邻平台达到同类别阈值的分钟差，仅表示统计先后。
                CategoryActivation source = activations.get(index - 1);
                CategoryActivation target = activations.get(index);
                long lagMinutes = ChronoUnit.MINUTES.between(source.firstHotTime(), target.firstHotTime());
                // 排除同时达标、逆序或超过 72 小时的关系。
                if (lagMinutes <= 0 || lagMinutes > 72 * 60L) {
                    // 跳过当前元素，继续下一轮。
                    continue;
                }
                // 新增一条类别先后关系，先保存类别与较早达标平台。
                relations.add(Map.of("category", source.category(), "source_platform", source.platform(),
                        // 写入较晚达标平台和较早平台的首达时间；写入较晚平台首达时间和两个平台的分钟差。
                        "target_platform", target.platform(), "source_time", source.firstHotTime(),
                        "target_time", target.firstHotTime(), "lag_minutes", lagMinutes));
                // 拼接较早达标平台和较晚达标平台，生成平台对分组键。
                String key = source.platform() + "::" + target.platform();
                // 获取平台对汇总关系，分组不存在时创建记录。
                Map<String, Object> link = linkMap.computeIfAbsent(key, ignored -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    // 组装当前记录，包含：较早达标平台、较晚达标平台、类别先后关系数量。
                    item.put("source_platform", source.platform());
                    item.put("target_platform", target.platform());
                    item.put("link_count", 0L);
                    // 组装当前记录，包含：累计时间差、类别集合。
                    item.put("lag_minutes_total", 0L);
                    item.put("categories", new java.util.LinkedHashSet<String>());
                    return item;
                });
                // 将1L按整数累加到平台对汇总关系的类别先后关系数量。
                addLong(link, "link_count", 1L);
                // 将首达分钟差按整数累加到平台对汇总关系的累计时间差。
                addLong(link, "lag_minutes_total", lagMinutes);
                // 关闭未检查类型转换的编译警告。
                @SuppressWarnings("unchecked")
                // 读取类别集合；把source.category()加入类别集合。
                Set<String> categories = (Set<String>) link.get("categories");
                categories.add(source.category());
            }
        }

        // 创建平台关系汇总列表。
        List<Map<String, Object>> links = new ArrayList<>();
        // 遍历平台对关系汇总记录。
        for (Map<String, Object> link : linkMap.values()) {
            // 安全转为长整数，空值和格式错误按零处理；安全转为长整数，空值和格式错误按零处理；用累计分钟差除以关系数，计算平均时间差并保留一位小数；无关系时为零。
            long count = longValue(link.get("link_count"));
            long lagTotal = longValue(link.get("lag_minutes_total"));
            link.put("average_lag_minutes", count == 0 ? 0D : Math.round((double) lagTotal / count * 10D) / 10D);
            // 将类别集合转换为中文顿号分隔的标签，同时移除内部集合字段；从平台对汇总关系移除累计时间差，不再保留此字段；向平台对汇总关系写入关系类型。
            link.put("category_label", String.join("、", (Set<String>) link.remove("categories")));
            link.remove("lag_minutes_total");
            link.put("relation_type", "category_heat_lead_lag");
            // 把平台对汇总关系加入平台关系汇总列表。
            links.add(link);
        }
        // 按平台对的类别先后关系数量降序排序。
        links.sort(Comparator.comparingLong((Map<String, Object> item) -> longValue(item.get("link_count"))).reversed());

        // 将各平台首达记录转换为数据流，查找最早首达时间。
        LocalDateTime earliest = platformActivations.values().stream()
                // 将多个列表展开成一个流，统一统计；提取每条类别首达记录的首达时间；取最小值以寻找最早时间。
                .flatMap(List::stream)
                .map(CategoryActivation::firstHotTime)
                .min(LocalDateTime::compareTo)
                // 没有首达时间时使用最大日期时间。
                .orElse(LocalDateTime.MAX);
        // 创建平台首达时间轴列表。
        List<Map<String, Object>> timeline = new ArrayList<>();
        // 遍历各平台的类别首达记录。
        for (Map.Entry<String, List<CategoryActivation>> entry : platformActivations.entrySet()) {
            // 将当前平台的类别首达记录转换为数据流，查找最早记录。
            CategoryActivation first = entry.getValue().stream()
                    // 取最小值以寻找最早时间；没有匹配结果时使用空值。
                    .min(Comparator.comparing(CategoryActivation::firstHotTime).thenComparing(CategoryActivation::category))
                    .orElse(null);
            // 当前平台没有首达记录时进入跳过分支。
            if (first == null) {
                // 跳过当前元素，继续下一轮。
                continue;
            }
            // 将当前平台的类别首达记录转换为数据流，查找峰值记录。
            CategoryActivation peak = entry.getValue().stream()
                    // 取峰值关注指数最高的类别首达记录；没有峰值记录时使用首达记录。
                    .max(Comparator.comparing(CategoryActivation::peakAttention))
                    .orElse(first);
            Map<String, Object> item = new LinkedHashMap<>();
            // 组装当前记录，包含：平台代码、最先达标类别、首次达到热度阈值时间。
            item.put("platform", entry.getKey());
            item.put("lead_category", first.category());
            item.put("first_hot_time", first.firstHotTime());
            // 向当前记录写入相对最早时间的分钟差；向当前记录写入类别数量；将峰值关注指数保留一位小数。
            item.put("delay_minutes", earliest.equals(LocalDateTime.MAX) ? 0L : ChronoUnit.MINUTES.between(earliest, first.firstHotTime()));
            item.put("category_count", entry.getValue().stream().map(CategoryActivation::category).distinct().count());
            item.put("peak_attention", Math.round(peak.peakAttention() * 10D) / 10D);
            // 把当前记录加入平台首达时间轴。
            timeline.add(item);
        }
        // 按相对最早首达时间的分钟差升序排列时间轴。
        timeline.sort(Comparator.comparingLong((Map<String, Object> item) -> longValue(item.get("delay_minutes")))
                // 前字段相同时继续按此字段排序。
                .thenComparing(item -> String.valueOf(item.get("platform"))));

        // 创建汇总指标；读取最早达标平台；向汇总指标写入类别数量。
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> firstPlatform = timeline.isEmpty() ? null : timeline.get(0);
        summary.put("category_count", series.size());
        // 组装汇总指标，包含：类别先后关系总数、平台数量、最先达标平台。
        summary.put("relation_count", links.stream().mapToLong(item -> longValue(item.get("link_count"))).sum());
        summary.put("platform_count", timeline.size());
        summary.put("lead_platform", firstPlatform == null ? null : firstPlatform.get("platform"));
        // 组装汇总指标，包含：最先达标类别、首次达到热度阈值时间、并列最早达标平台。
        summary.put("lead_category", firstPlatform == null ? null : firstPlatform.get("lead_category"));
        summary.put("first_hot_time", firstPlatform == null ? null : firstPlatform.get("first_hot_time"));
        summary.put("lead_platforms", timeline.stream().filter(item -> longValue(item.get("delay_minutes")) == 0L)
                // 提取平台代码并收集为列表。
                .map(item -> item.get("platform")).toList());
        // 提取并转换统计时间，查找窗口起点。
        LocalDateTime windowStart = rows.stream().map(row -> timestampValue(row.get("time_bucket")))
                // 排除无效时间后取最早时间，无有效时间时返回空值。
                .filter(value -> !value.equals(LocalDateTime.MAX)).min(LocalDateTime::compareTo).orElse(null);
        // 提取并转换统计时间，查找窗口终点。
        LocalDateTime windowEnd = rows.stream().map(row -> timestampValue(row.get("time_bucket")))
                // 排除无效时间后取最晚时间，无有效时间时返回空值。
                .filter(value -> !value.equals(LocalDateTime.MAX)).max(LocalDateTime::compareTo).orElse(null);
        // 组装汇总指标，包含：窗口开始时间、窗口结束时间。
        summary.put("window_start", windowStart);
        summary.put("window_end", windowEnd);
        Map<String, Object> result = new LinkedHashMap<>();
        // 组装处理结果，包含：平台类别先后连线、平台首达时间轴、汇总指标。
        result.put("links", links);
        result.put("timeline", timeline);
        result.put("summary", summary);
        // 合并所有平台的类别首达记录，生成明细数据流。
        List<Map<String, Object>> activations = platformActivations.values().stream().flatMap(List::stream)
                // 按指定比较器排序，确定页面数据顺序。
                .sorted(Comparator.comparing(CategoryActivation::firstHotTime).thenComparing(CategoryActivation::platform)
                        // 前字段相同时继续按此字段排序。
                        .thenComparing(CategoryActivation::category))
                // 将类别首达记录转换为字段映射。
                .map(item -> {
                    // 创建首达记录；向首达记录写入平台代码；向首达记录写入内容类别。
                    Map<String, Object> activation = new LinkedHashMap<>();
                    activation.put("platform", item.platform());
                    activation.put("category", item.category());
                    // 组装首达记录，包含：首次达到热度阈值时间、峰值时间、峰值关注指数。
                    activation.put("first_hot_time", item.firstHotTime());
                    activation.put("peak_time", item.peakTime());
                    activation.put("peak_attention", item.peakAttention());
                    // 返回首达阈值：35 与峰值的 70% 中较大者。
                    activation.put("threshold", Math.max(35D, item.peakAttention() * 0.7D));
                    // 返回首达记录。
                    return activation;
                }).toList();
        // 组装处理结果，包含：类别首达明细、小时统计依据、逐类别先后关系。
        result.put("activations", activations);
        result.put("hourly", hourlyEvidence);
        result.put("relations", relations);
        return result;
    }
}
