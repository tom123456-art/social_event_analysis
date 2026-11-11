package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** 数据大屏数据查询，SQL 位于同名 XML 映射文件。 */
@Mapper
public interface ScreenMapper {
    // 数据大屏 / 平台汇总：查询各平台最早发布时间、内容量和热度。
    List<Map<String, Object>> platformTimeline(@Param("eventId") String eventId);

    // 数据大屏 / 顶部指标：查询事件内容量与热度概览。
    Map<String, Object> overview(@Param("eventId") String eventId);

    // 数据大屏 / 批次信息：查询当前事件的最新 ETL 批次。
    Map<String, Object> latestBatch(@Param("eventId") String eventId);

    // 数据大屏 / 内容列表：按类别各取最多 limit 条最新内容。
    List<Map<String, Object>> realPublicContents(@Param("eventId") String eventId, @Param("limit") int limit);

    // 数据大屏 / 高热内容：按排名取前 limit 条内容。
    List<Map<String, Object>> contentRank(@Param("eventId") String eventId, @Param("limit") int limit);

    // 数据大屏 / 关键词排行：按词频取前 limit 条关键词。
    List<Map<String, Object>> keywordRank(@Param("eventId") String eventId, @Param("limit") int limit);

    // 数据大屏 / 传播热度脉冲：查询按时间和平台汇总的热度。
    List<Map<String, Object>> heatTrend(@Param("eventId") String eventId);

    // 数据大屏 / 平台情绪结构：查询按时间、平台和标签汇总的情感样本数。
    List<Map<String, Object>> sentimentTrend(@Param("eventId") String eventId);
}
