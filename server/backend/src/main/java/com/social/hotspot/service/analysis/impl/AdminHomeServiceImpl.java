package com.social.hotspot.service.analysis.impl;

import com.social.hotspot.service.analysis.AdminHomeService;

import com.social.hotspot.common.cache.RedisCache;
import static com.social.hotspot.common.cache.CacheKeys.ANALYSIS_TTL;
import com.social.hotspot.mapper.AdminHomeMapper;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;
import static com.social.hotspot.common.cache.CacheKeys.AnalysisPage.ADMIN_HOME;
import static com.social.hotspot.service.analysis.AnalysisResultAssembler.*;

/** 后台首页业务实现：批次和数量指标直接查库，四组分析图表独立使用页面缓存。 */
@Service
public class AdminHomeServiceImpl implements AdminHomeService {
    private final AdminHomeMapper mapper;
    private final RedisCache cache;

    public AdminHomeServiceImpl(AdminHomeMapper mapper, RedisCache cache) {
        this.mapper = mapper;
        this.cache = cache;
    }

    /** 组装后台首页数据，避免图表缓存导致最新批次状态和事件、主题数量滞后。 */
    @Override
    public Map<String, Object> adminOverview(String eventId) {
        Map<String, Object> data = new LinkedHashMap<>();
        // 顶部指标：事件数、去重标题的主题数保持直接查库，不因图表缓存而滞后。
        data.put("eventCount", mapper.eventCount());
        data.put("topicCount", mapper.topicCount(eventId));
        // 数据来源说明、ETL成功率、最近处理批次：共用最近 20 个批次，前端以首项读取最新状态。
        data.put("batches", mapper.batches(20));

        // 后台首页 / 分析图表：只有下面四组分析结果使用缓存，批次状态仍然每次查询。
        Map<String, Object> charts = cache.getMap(ADMIN_HOME.key(eventId));
        if (charts == null) {
            charts = new LinkedHashMap<>();
            // 顶部关键词结果：查询前 30 条关键词，前端显示返回条数。
            charts.put("keywordRank", mapper.keywordRank(eventId, 30));
            // 平台热度排行：按标准平台合并全量时间线中的内容量和热度。
            charts.put("platformTimeline", aggregatePlatformTimeline(mapper.platformTimeline(eventId)));
            // 高热内容审核：读取前 20 条高热内容并补齐展示字段，前端独立分页。
            charts.put("contentRank", enrichContentRows(mapper.contentRank(eventId, 20)));
            // 数据质量巡检：按平台累计内容数、噪声数和重复数。
            charts.put("noiseSummary", aggregateNoise(mapper.noiseSummary(eventId)));
            cache.put(ADMIN_HOME.key(eventId), charts, ANALYSIS_TTL);
        }
        data.putAll(charts);
        return data;
    }
}
