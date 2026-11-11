package com.social.hotspot.service.analysis;

import java.util.List;
import java.util.Map;

/** 热点趋势业务接口，实现位于 HotspotTrendServiceImpl。 */
public interface HotspotTrendService {
    /** 热点趋势：返回平台日度热度和类别热度，供前端筛选日期并生成指标、趋势与峰值图。 */
    Map<String, Object> hotspotTrend(String eventId);

    /** 热点趋势 / 热门事件前三名：查询所选日期内的高热内容候选，前端再按平台取前三。 */
    List<Map<String, Object>> trendContentRankByRange(String eventId, String startDate, String endDate);
}
