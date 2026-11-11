package com.social.hotspot.service.analysis.impl;

import com.social.hotspot.service.analysis.ScreenService;

import com.social.hotspot.common.cache.RedisCache;
import static com.social.hotspot.common.cache.CacheKeys.ANALYSIS_TTL;
import com.social.hotspot.mapper.ScreenMapper;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.social.hotspot.common.cache.CacheKeys.AnalysisPage.SCREEN;
import static com.social.hotspot.service.analysis.AnalysisResultAssembler.*;

/** 数据大屏业务实现：查询各板块统计并组装展示数据，使用大屏独立缓存供页面和助手复用。 */
@Service
public class ScreenServiceImpl implements ScreenService {
    private final ScreenMapper mapper;
    private final RedisCache cache;

    public ScreenServiceImpl(ScreenMapper mapper, RedisCache cache) {
        this.mapper = mapper;
        this.cache = cache;
    }

    @Override
    public Map<String, Object> screen(String eventId) {
        // 数据大屏 / 全页：只读取大屏缓存；AI 助手也从这份跨板块摘要中提取上下文。
        Map<String, Object> cached = cache.getMap(SCREEN.key(eventId));
        if (cached != null) return cached;
        Map<String, Object> data = new LinkedHashMap<>();
        // 五源热度矩阵：查询并合并平台汇总；下方跨平台时序复用同一份最早发布时间。
        List<Map<String, Object>> timeline = aggregatePlatformTimeline(mapper.platformTimeline(eventId));
        data.put("platformTimeline", timeline);
        // 传播热度脉冲 / 顶部指标：查询内容量和总热度，并校准平台数。
        data.put("overview", normalizeOverview(mapper.overview(eventId), timeline));
        // 传播热度脉冲：查询时序并按标准平台聚合，下方峰值传播强度复用它计算峰值。
        data.put("heatTrend", aggregateHeatTrend(mapper.heatTrend(eventId)));
        // 高热内容排行：查询前 20 条高热内容并规范展示字段。
        List<Map<String, Object>> contentRank = enrichContentRows(mapper.contentRank(eventId, 20));
        data.put("contentRank", contentRank);
        // 数据质量概览：查询最近一个批次的原始数、有效数、状态和完成时间。
        data.put("latestBatch", mapper.latestBatch(eventId));
        // 关键词情报矩阵：查询词频前 30 的关键词及其平台信息。
        data.put("keywordRank", mapper.keywordRank(eventId, 30));
        // 内容流：每个原始类别最多取 300 条最新内容，前端按发布时间组织滚动列表。
        List<Map<String, Object>> contents = enrichContentRows(mapper.realPublicContents(eventId, 300));
        data.put("realPublicContents", contents);
        // 热点类型构成：按样本累计各类别的内容量和热度，样本为空时使用高热内容兜底。
        data.put("categoryRank", categoryRank(contents.isEmpty() ? contentRank : contents));
        // 平台情绪结构：按时间、标准平台和情感标签累计样本数。
        data.put("sentimentTrend", aggregateSentimentTrend(mapper.sentimentTrend(eventId)));
        // 数据大屏 / 全页：将本次组装结果缓存 30 分钟，再返回给调用方。
        cache.put(SCREEN.key(eventId), data, ANALYSIS_TTL);
        return data;
    }
}
