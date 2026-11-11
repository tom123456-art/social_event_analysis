package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** 后台首页数据查询，SQL 位于同名 XML 映射文件。 */
@Mapper
public interface AdminHomeMapper {
    // 后台首页 / 顶部指标：统计事件容器数量。
    Map<String, Object> eventCount();

    // 后台首页 / 顶部指标：按标题去重统计主题数量。
    Map<String, Object> topicCount(@Param("eventId") String eventId);

    // 后台首页 / 批次列表：按开始时间倒序查询批次。
    List<Map<String, Object>> batches(@Param("limit") int limit);

    // 后台首页 / 关键词排行：按词频取前 limit 条关键词。
    List<Map<String, Object>> keywordRank(@Param("eventId") String eventId, @Param("limit") int limit);

    // 后台首页 / 平台汇总：查询各平台最早发布时间、内容量和热度。
    List<Map<String, Object>> platformTimeline(@Param("eventId") String eventId);

    // 后台首页 / 高热内容：按排名取前 limit 条内容。
    List<Map<String, Object>> contentRank(@Param("eventId") String eventId, @Param("limit") int limit);

    // 后台首页 / 数据质量巡检：查询内容、噪声和重复统计。
    List<Map<String, Object>> noiseSummary(@Param("eventId") String eventId);
}
