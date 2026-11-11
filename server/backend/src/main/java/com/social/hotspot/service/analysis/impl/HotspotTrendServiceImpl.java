package com.social.hotspot.service.analysis.impl;

import com.social.hotspot.service.analysis.HotspotTrendService;

import com.social.hotspot.common.cache.RedisCache;
import static com.social.hotspot.common.cache.CacheKeys.ANALYSIS_TTL;
import com.social.hotspot.mapper.HotspotTrendMapper;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.social.hotspot.common.cache.CacheKeys.AnalysisPage.HOTSPOT_TREND;
import static com.social.hotspot.service.analysis.AnalysisResultAssembler.enrichContentRows;

/** 热点趋势：日度图表共用一份数据，热门事件按选定日期单独查询。 */
@Service
public class HotspotTrendServiceImpl implements HotspotTrendService {
    private final HotspotTrendMapper mapper;
    private final RedisCache cache;

    public HotspotTrendServiceImpl(HotspotTrendMapper mapper, RedisCache cache) {
        this.mapper = mapper;
        this.cache = cache;
    }

    /** 日度图表入口：后端提供统计明细并缓存，日期筛选、指标和峰值计算由前端完成。 */
    @Override
    public Map<String, Object> hotspotTrend(String eventId) {
        // 热点趋势 / 全页：只读取趋势页缓存，未命中时查询日度统计。
        Map<String, Object> cached = cache.getMap(HOTSPOT_TREND.key(eventId));
        if (cached != null) return cached;
        Map<String, Object> data = new LinkedHashMap<>();

        // 顶部指标、左上互动热度趋势、左下平台热度峰值：共用平台日度数据，前端按日期筛选后计算。
        data.put("platformDailyHeat", mapper.platformDailyHeat(eventId));
        // 右上类别热度趋势：查询平台每日类别热度，前端在总体雷达图和单平台折线图之间切换。
        data.put("platformCategoryHeat", mapper.platformCategoryHeat(eventId));

        // 热点趋势 / 日度图表：缓存并返回两个字段，不查询门户、扩散或大屏数据。
        cache.put(HOTSPOT_TREND.key(eventId), data, ANALYSIS_TTL);
        return data;
    }

    @Override
    public List<Map<String, Object>> trendContentRankByRange(String eventId, String startDate, String endDate) {
        // 右下热门事件前三名：缺少起止日期或开始日期晚于结束日期时不查询。
        if (startDate == null || endDate == null || startDate.isBlank() || endDate.isBlank()
                || startDate.compareTo(endDate) > 0) return List.of();
        // 按日期直接查最多 3000 条候选内容并补齐展示字段，前端再按平台各取前三名；不读取页面缓存。
        return enrichContentRows(mapper.trendContentRankByRange(eventId, startDate, endDate, 3000));
    }
}
