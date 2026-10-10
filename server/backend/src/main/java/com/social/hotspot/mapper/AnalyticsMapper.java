package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
/** 分析数据访问接口：声明各类分析结果的数据库查询方法。 */
public interface AnalyticsMapper {
    List<Map<String, Object>> events();

    Map<String, Object> overview(@Param("eventId") String eventId);

    List<Map<String, Object>> heatTrend(@Param("eventId") String eventId);

    List<Map<String, Object>> platformTimeline(@Param("eventId") String eventId);

    List<Map<String, Object>> trendContentRank(@Param("eventId") String eventId, @Param("limit") int limit);

    List<Map<String, Object>> trendContentRankByRange(@Param("eventId") String eventId,
                                                       @Param("startDate") String startDate,
                                                       @Param("endDate") String endDate,
                                                       @Param("limit") int limit);

    List<Map<String, Object>> platformCategoryHeat(@Param("eventId") String eventId);

    List<Map<String, Object>> platformCategoryHourlyHeat(@Param("eventId") String eventId);

    List<Map<String, Object>> propagationContents(@Param("eventId") String eventId,
            @Param("platform") String platform, @Param("category") String category,
            @Param("start") java.time.LocalDateTime start, @Param("end") java.time.LocalDateTime end,
            @Param("offset") int offset, @Param("limit") int limit);

    long propagationContentCount(@Param("eventId") String eventId,
            @Param("platform") String platform, @Param("category") String category,
            @Param("start") java.time.LocalDateTime start, @Param("end") java.time.LocalDateTime end);

    List<Map<String, Object>> platformDailyHeat(@Param("eventId") String eventId);

    List<Map<String, Object>> interaction(@Param("eventId") String eventId);

    List<Map<String, Object>> keywordRank(@Param("eventId") String eventId, @Param("limit") int limit);

    List<Map<String, Object>> sentimentTrend(@Param("eventId") String eventId);

    List<Map<String, Object>> sentimentAnalysisRows(@Param("eventId") String eventId);

    List<Map<String, Object>> contentRank(@Param("eventId") String eventId, @Param("limit") int limit);

    List<Map<String, Object>> realPublicContents(@Param("eventId") String eventId, @Param("limit") int limit);

    List<Map<String, Object>> contentPage(@Param("eventId") String eventId,
                                           @Param("query") String query,
                                           @Param("platform") String platform,
                                           @Param("sentiment") String sentiment,
                                           @Param("category") String category,
                                           @Param("offset") int offset,
                                           @Param("pageSize") int pageSize);

    long contentCount(@Param("eventId") String eventId,
                      @Param("query") String query,
                      @Param("platform") String platform,
                      @Param("sentiment") String sentiment,
                      @Param("category") String category);

    List<Map<String, Object>> keywordAnalysisRows(@Param("eventId") String eventId, @Param("limit") int limit);

    Map<String, Object> topicCount(@Param("eventId") String eventId);

    List<Map<String, Object>> topicRank(@Param("eventId") String eventId, @Param("limit") int limit);

    Map<String, Object> latestBatch(@Param("eventId") String eventId);

    List<Map<String, Object>> noiseSummary(@Param("eventId") String eventId);

    List<Map<String, Object>> batches(@Param("limit") int limit);

    List<Map<String, Object>> taskLogs(@Param("batchId") String batchId);

    Map<String, Object> eventCount();
}
