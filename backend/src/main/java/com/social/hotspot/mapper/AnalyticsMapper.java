package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface AnalyticsMapper {
    List<Map<String, Object>> events();

    Map<String, Object> overview(@Param("eventId") String eventId);

    List<Map<String, Object>> heatTrend(@Param("eventId") String eventId);

    List<Map<String, Object>> platformTimeline(@Param("eventId") String eventId);

    List<Map<String, Object>> interaction(@Param("eventId") String eventId);

    List<Map<String, Object>> keywordRank(@Param("eventId") String eventId, @Param("limit") int limit);

    List<Map<String, Object>> sentimentTrend(@Param("eventId") String eventId);

    List<Map<String, Object>> contentRank(@Param("eventId") String eventId, @Param("limit") int limit);

    List<Map<String, Object>> realPublicContents(@Param("eventId") String eventId, @Param("limit") int limit);

    Map<String, Object> topicCount(@Param("eventId") String eventId);

    List<Map<String, Object>> topicRank(@Param("eventId") String eventId, @Param("limit") int limit);
    List<Map<String, Object>> propagationLinks(@Param("eventId") String eventId);

    List<Map<String, Object>> topicPropagationLinks(@Param("eventId") String eventId);

    Map<String, Object> latestBatch(@Param("eventId") String eventId);

    List<Map<String, Object>> noiseSummary(@Param("eventId") String eventId);

    List<Map<String, Object>> batches(@Param("limit") int limit);

    List<Map<String, Object>> taskLogs(@Param("batchId") String batchId);

    int deleteTaskLogsForEventExceptBatch(@Param("eventId") String eventId, @Param("currentBatchId") String currentBatchId);

    int deleteBatchesForEventExceptBatch(@Param("eventId") String eventId, @Param("currentBatchId") String currentBatchId);

    Map<String, Object> eventCount();
}
