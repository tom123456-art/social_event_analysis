package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** 热点趋势数据查询，SQL 位于同名 XML 映射文件。 */
@Mapper
public interface HotspotTrendMapper {
    // 热点趋势 / 日度热度：查询平台每日内容量与平均热度。
    List<Map<String, Object>> platformDailyHeat(@Param("eventId") String eventId);

    // 热点趋势 / 类别热度趋势：查询平台每日类别热度。
    List<Map<String, Object>> platformCategoryHeat(@Param("eventId") String eventId);

    // 热点趋势 / 热门事件前三名：按日期范围查询高热内容候选，前端再按平台取前三。
    List<Map<String, Object>> trendContentRankByRange(@Param("eventId") String eventId, @Param("startDate") String startDate, @Param("endDate") String endDate, @Param("limit") int limit);
}
