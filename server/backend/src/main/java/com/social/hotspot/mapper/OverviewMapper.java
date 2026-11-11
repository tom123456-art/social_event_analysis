package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** 热点门户数据查询，SQL 位于同名 XML 映射文件。 */
@Mapper
public interface OverviewMapper {
    // 热点门户 / 平台汇总：查询各平台最早发布时间、内容量和热度。
    List<Map<String, Object>> platformTimeline(@Param("eventId") String eventId);

    // 热点门户 / 顶部指标：查询事件内容量与热度概览。
    Map<String, Object> overview(@Param("eventId") String eventId);

    // 热点门户 / 内容列表：按类别各取最多 limit 条最新内容。
    List<Map<String, Object>> realPublicContents(@Param("eventId") String eventId, @Param("limit") int limit);

    // 热点门户 / 批次信息：查询当前事件的最新 ETL 批次。
    Map<String, Object> latestBatch(@Param("eventId") String eventId);

    // 热点门户 / 来源贡献排行：查询各平台内容量与累计热度。
    List<Map<String, Object>> interaction(@Param("eventId") String eventId);

    // 热点门户 / 高热内容：按排名取前 limit 条内容。
    List<Map<String, Object>> contentRank(@Param("eventId") String eventId, @Param("limit") int limit);

    // 热点门户 / 关键词排行：按词频取前 limit 条关键词。
    List<Map<String, Object>> keywordRank(@Param("eventId") String eventId, @Param("limit") int limit);
}
