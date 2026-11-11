package com.social.hotspot.service.analysis.impl;

import com.social.hotspot.service.analysis.OverviewService;

import com.social.hotspot.common.cache.RedisCache;
import static com.social.hotspot.common.cache.CacheKeys.ANALYSIS_TTL;
import com.social.hotspot.mapper.OverviewMapper;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.social.hotspot.common.cache.CacheKeys.AnalysisPage.OVERVIEW;
import static com.social.hotspot.service.analysis.AnalysisResultAssembler.*;

/** 热点门户：只查询和组装门户板块，不再加载其他分析页面的数据。 */
@Service
public class OverviewServiceImpl implements OverviewService {
    private final OverviewMapper mapper;
    private final RedisCache cache;

    public OverviewServiceImpl(OverviewMapper mapper, RedisCache cache) {
        this.mapper = mapper;
        this.cache = cache;
    }

    /** 门户数据入口：先读本页缓存，未命中时调用 Mapper 查库并完成字段规范、类别汇总。 */
    @Override
    public Map<String, Object> overview(String eventId) {
        // 热点门户 / 全页：命中门户缓存时直接返回，否则按下面的板块顺序查询。
        Map<String, Object> cached = cache.getMap(OVERVIEW.key(eventId));
        if (cached != null) return cached;
        Map<String, Object> data = new LinkedHashMap<>();

        // 顶部指标：查询概览，并按标准化的平台时间线重新计算采集源数。
        List<Map<String, Object>> timeline = aggregatePlatformTimeline(mapper.platformTimeline(eventId));
        data.put("overview", normalizeOverview(mapper.overview(eventId), timeline));
        // 正在被关注的内容：每个原始类别最多取 300 条最新内容，补齐平台、类别和展示字段。
        List<Map<String, Object>> contents = enrichContentRows(mapper.realPublicContents(eventId, 300));
        data.put("realPublicContents", contents);
        // 顶部同步批次、右侧数据来源：查询最近一个 ETL 批次，两个板块共用。
        data.put("latestBatch", mapper.latestBatch(eventId));
        // 来源贡献排行：查询平台内容量和累计热度，前端按内容量排序绘制柱图。
        data.put("interaction", mapper.interaction(eventId));
        // 热点分类：门户样本为空时查询前 20 条高热内容兜底，再按类别累计内容量和热度并排序。
        List<Map<String, Object>> categoryContents = contents.isEmpty()
                ? enrichContentRows(mapper.contentRank(eventId, 20)) : contents;
        data.put("categoryRank", categoryRank(categoryContents));
        // 关键词热度：查询词频前 30 的关键词，前端展示其中前 12 个。
        data.put("keywordRank", mapper.keywordRank(eventId, 30));

        // 热点门户 / 全页：只缓存并返回本页面需要的六个字段。
        cache.put(OVERVIEW.key(eventId), data, ANALYSIS_TTL);
        return data;
    }
}
