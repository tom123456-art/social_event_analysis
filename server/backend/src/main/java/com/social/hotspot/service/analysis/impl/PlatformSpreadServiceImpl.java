package com.social.hotspot.service.analysis.impl;

import com.social.hotspot.service.analysis.PlatformSpreadService;

import com.social.hotspot.common.cache.RedisCache;
import static com.social.hotspot.common.cache.CacheKeys.ANALYSIS_TTL;
import com.social.hotspot.mapper.PlatformSpreadMapper;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.LocalDate;
import com.social.hotspot.service.analysis.DailyCategoryResponses;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.social.hotspot.common.cache.CacheKeys.AnalysisPage.PLATFORM_SPREAD;
import static com.social.hotspot.service.analysis.AnalysisResultAssembler.*;
import static com.social.hotspot.service.analysis.AnalysisValues.normalizeCategoryLabel;
import static com.social.hotspot.service.analysis.AnalysisValues.timestampValue;
import static com.social.hotspot.service.analysis.CategoryPropagationAnalyzer.*;

/** 平台扩散：四个板块共用近期统计，证据弹框按筛选条件直接查库。 */
@Service
public class PlatformSpreadServiceImpl implements PlatformSpreadService {
    private final PlatformSpreadMapper mapper;
    private final RedisCache cache;

    public PlatformSpreadServiceImpl(PlatformSpreadMapper mapper, RedisCache cache) {
        this.mapper = mapper;
        this.cache = cache;
    }

    /** 扩散图表入口：先读本页缓存，未命中时查库并计算近期平台汇总、类别首达关系与延迟。 */
    @Override
    public Map<String, Object> platformSpread(String eventId) {
        // 平台扩散 / 全页：命中扩散页缓存时直接返回。
        Map<String, Object> cached = cache.getMap(PLATFORM_SPREAD.key(eventId));
        if (cached != null) return cached;
        Map<String, Object> data = new LinkedHashMap<>();

        // 左上扩散网络：查询全量平台时间线作为兼容来源；日度热度汇总供网络节点和右上漏斗共用。
        data.put("platformTimeline", aggregatePlatformTimeline(mapper.platformTimeline(eventId)));
        List<Map<String, Object>> dailyHeat = mapper.platformDailyHeat(eventId);
        List<Map<String, Object>> recentPlatforms = aggregateRecentPlatformTimeline(dailyHeat);
        // 左上网络连线：查询分钟类别数据，保留以数据最新日期为基准的七个自然日，再计算类别首达先后。
        List<Map<String, Object>> categoryRows = recentRows(mapper.platformCategoryHourlyHeat(eventId), "time_bucket", 7);
        Map<String, Object> propagation = analyzeCategoryPropagation(categoryRows);
        data.put("categoryDailyResponses", DailyCategoryResponses.analyze(categoryRows));
        data.put("categoryPropagationLinks", propagation.get("links"));
        // 右上平台热度漏斗：写入近期平台累计热度，左上网络也用它决定节点大小。
        data.put("recentPlatformTimeline", recentPlatforms);
        // 左下类别热度响应时间线：写入首达时间和相对最早平台的分钟延迟。
        data.put("categoryPropagationTimeline", propagation.get("timeline"));
        // 右下类别扩散结论摘要：写入窗口、领先平台、关系数量等统计。
        data.put("categoryPropagationSummary", propagation.get("summary"));
        // 查看依据 / 累计热度：弹框复用每日明细，不为日度数据另发请求。
        data.put("platformDailyHeat", dailyHeat);

        // 平台扩散 / 全页：缓存本页图表结果；证据弹框仍由独立方法直接查库。
        cache.put(PLATFORM_SPREAD.key(eventId), data, ANALYSIS_TTL);
        return data;
    }

    @Override
    public Map<String, Object> propagationEvidence(String eventId, String platform, String category, String hour, int page) {
        return propagationEvidence(eventId, platform, category, hour, page, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> propagationEvidence(String eventId, String platform, String category, String hour, int page, String date) {
        // 结论摘要 / 查看依据：先校验五个新闻平台及可选的标准类别。
        if (!SPREAD_PLATFORMS.contains(platform)) throw new IllegalArgumentException("无效的平台");
        String label = category == null || category.isBlank() ? null : normalizeCategoryLabel(category);
        if (category != null && !category.isBlank() && label == null) throw new IllegalArgumentException("无效的类别");
        // 证据明细不走 Redis，直接查询分钟数据并重新计算与页面同口径的七自然日窗口。
        List<Map<String, Object>> rows = recentRows(mapper.platformCategoryHourlyHeat(eventId), "time_bucket", 7);
        Map<String, Object> analysis = analyzeCategoryPropagation(rows);
        Map<String, Object> summary = (Map<String, Object>) analysis.get("summary");
        LocalDateTime start = (LocalDateTime) summary.get("window_start");
        LocalDateTime end = (LocalDateTime) summary.get("window_end");
        if (start == null || end == null) return Map.of("items", List.of(), "total", 0);
        // 将窗口对齐到首日零点和末日次日零点，查询区间左闭右开。
        start = start.toLocalDate().atStartOfDay();
        end = end.toLocalDate().plusDays(1).atStartOfDay();
        if (date != null && !date.isBlank()) {
            LocalDate selected;
            try { selected = LocalDate.parse(date); }
            catch (java.time.format.DateTimeParseException ex) { throw new IllegalArgumentException("无效的统计日期"); }
            LocalDate lastDay = end.toLocalDate().minusDays(1);
            if (selected.isBefore(lastDay.minusDays(6)) || selected.isAfter(lastDay)) {
                throw new IllegalArgumentException("统计日期不在最近七天内");
            }
            start = selected.atStartOfDay();
            end = start.plusDays(1);
            analysis = analyzeCategoryPropagation(DailyCategoryResponses.rowsForDay(rows, selected));
            Map<String, Object> dailySummary = (Map<String, Object>) analysis.get("summary");
            dailySummary.put("window_start", start);
            dailySummary.put("window_end", end.minusMinutes(1));
        }
        if (hour != null && !hour.isBlank()) {
            // hour 参数保留兼容名称，实际选择窗口内的一分钟。
            LocalDateTime selected = timestampValue(hour);
            if (selected.equals(LocalDateTime.MAX) || selected.isBefore(start) || !selected.isBefore(end)
                    || selected.getSecond() != 0 || selected.getNano() != 0) {
                throw new IllegalArgumentException("无效的统计分钟");
            }
            start = selected;
            end = selected.plusMinutes(1);
        }
        // 参与计算的新闻：每页 20 条，列表和总数使用相同的平台、类别、时间条件。
        int safePage = Math.min(100000, Math.max(1, page));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", enrichContentRows(mapper.propagationContents(eventId, platform, label, start, end, (safePage - 1) * 20, 20)));
        result.put("total", mapper.propagationContentCount(eventId, platform, label, start, end));
        result.put("page", safePage);
        result.put("page_size", 20);
        // 计算依据：同时返回查询窗口及逐分钟分析，供弹框展示公式、首达记录和关系明细。
        result.put("window_start", start);
        result.put("window_end", end);
        result.put("analysis", analysis);
        return result;
    }
}
