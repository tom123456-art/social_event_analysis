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
    List<Map<String, Object>> trendHeatTrend(@Param("eventId") String eventId);

    List<Map<String, Object>> trendPlatformSummary(@Param("eventId") String eventId);

    List<Map<String, Object>> trendContentRank(@Param("eventId") String eventId, @Param("limit") int limit);

    List<Map<String, Object>> platformCategoryHeat(@Param("eventId") String eventId);

    List<Map<String, Object>> platformCategoryHourlyHeat(@Param("eventId") String eventId);

    List<Map<String, Object>> platformDailyHeat(@Param("eventId") String eventId);

    List<Map<String, Object>> topicTrend(@Param("eventId") String eventId);

    List<Map<String, Object>> topicSummary(@Param("eventId") String eventId, @Param("limit") int limit);

    List<Map<String, Object>> topicKeyContents(@Param("eventId") String eventId, @Param("topicId") String topicId);

    List<Map<String, Object>> interaction(@Param("eventId") String eventId);

    List<Map<String, Object>> keywordRank(@Param("eventId") String eventId, @Param("limit") int limit);

    List<Map<String, Object>> sentimentTrend(@Param("eventId") String eventId);

    List<Map<String, Object>> sentimentAnalysisRows(@Param("eventId") String eventId);

    List<Map<String, Object>> contentRank(@Param("eventId") String eventId, @Param("limit") int limit);

    List<Map<String, Object>> realPublicContents(@Param("eventId") String eventId, @Param("limit") int limit);

    List<Map<String, Object>> keywordAnalysisRows(@Param("eventId") String eventId, @Param("limit") int limit);

    Map<String, Object> topicCount(@Param("eventId") String eventId);

    List<Map<String, Object>> topicRank(@Param("eventId") String eventId, @Param("limit") int limit);
    List<Map<String, Object>> propagationLinks(@Param("eventId") String eventId);

    List<Map<String, Object>> topicPropagationLinks(@Param("eventId") String eventId);

    Map<String, Object> latestBatch(@Param("eventId") String eventId);

    List<Map<String, Object>> noiseSummary(@Param("eventId") String eventId);

    List<Map<String, Object>> batches(@Param("limit") int limit);

    List<Map<String, Object>> taskLogs(@Param("batchId") String batchId);

    Map<String, Object> eventCount();
}
