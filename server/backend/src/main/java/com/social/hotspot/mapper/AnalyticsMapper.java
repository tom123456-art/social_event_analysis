package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

// 让 MyBatis 创建访问代理，SQL 位于对应映射文件。
@Mapper
/** 分析数据访问接口：声明各类分析结果的数据库查询方法。 */
// 定义分析数据访问接口，约定调用方式。
public interface AnalyticsMapper {
    // 查询可展示的事件列表。
    List<Map<String, Object>> events();

    // 查询事件内容量与热度概览。
    Map<String, Object> overview(@Param("eventId") String eventId);

    // 查询按时间与平台汇总的热度。
    List<Map<String, Object>> heatTrend(@Param("eventId") String eventId);

    // 查询平台最早发布时间与汇总指标。
    List<Map<String, Object>> platformTimeline(@Param("eventId") String eventId);

    // 查询趋势页面内容样本。
    List<Map<String, Object>> trendContentRank(@Param("eventId") String eventId, @Param("limit") int limit);

    // 查询日期范围内高热度内容。
    List<Map<String, Object>> trendContentRankByRange(@Param("eventId") String eventId,
                                                       // 绑定参数 startDate，按注解名称从 SQL 映射或 HTTP 请求中取值。
                                                       @Param("startDate") String startDate,
                                                       // 绑定参数 endDate，按注解名称从 SQL 映射或 HTTP 请求中取值。
                                                       @Param("endDate") String endDate,
                                                       // 绑定参数 limit，按注解名称从 SQL 映射或 HTTP 请求中取值。
                                                       @Param("limit") int limit);

    // 查询平台日度类别热度。
    List<Map<String, Object>> platformCategoryHeat(@Param("eventId") String eventId);

    // 查询小时类别关注指数计算依据。
    List<Map<String, Object>> platformCategoryHourlyHeat(@Param("eventId") String eventId);

    // 分页查询平台类别窗口内的原始内容。
    List<Map<String, Object>> propagationContents(@Param("eventId") String eventId,
            // 绑定参数 platform；category，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @Param("platform") String platform, @Param("category") String category,
            // 绑定参数 start；end，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @Param("start") java.time.LocalDateTime start, @Param("end") java.time.LocalDateTime end,
            // 绑定参数 offset；limit，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @Param("offset") int offset, @Param("limit") int limit);

    // 统计平台类别窗口内内容数。
    long propagationContentCount(@Param("eventId") String eventId,
            // 绑定参数 platform；category，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @Param("platform") String platform, @Param("category") String category,
            // 绑定参数 start；end，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @Param("start") java.time.LocalDateTime start, @Param("end") java.time.LocalDateTime end);

    // 查询平台日度热度。
    List<Map<String, Object>> platformDailyHeat(@Param("eventId") String eventId);

    // 查询平台内容量与热度汇总。
    List<Map<String, Object>> interaction(@Param("eventId") String eventId);

    // 查询关键词频次排名。
    List<Map<String, Object>> keywordRank(@Param("eventId") String eventId, @Param("limit") int limit);

    // 查询按情感标签汇总的时序。
    List<Map<String, Object>> sentimentTrend(@Param("eventId") String eventId);

    // 查询情感分析样本。
    List<Map<String, Object>> sentimentAnalysisRows(@Param("eventId") String eventId);

    // 查询高热度内容。
    List<Map<String, Object>> contentRank(@Param("eventId") String eventId, @Param("limit") int limit);

    // 查询公开内容样本。
    List<Map<String, Object>> realPublicContents(@Param("eventId") String eventId, @Param("limit") int limit);

    // 按筛选条件分页查询内容。
    List<Map<String, Object>> contentPage(@Param("eventId") String eventId,
                                           // 绑定参数 query，按注解名称从 SQL 映射或 HTTP 请求中取值。
                                           @Param("query") String query,
                                           // 绑定参数 platform，按注解名称从 SQL 映射或 HTTP 请求中取值。
                                           @Param("platform") String platform,
                                           // 绑定参数 sentiment，按注解名称从 SQL 映射或 HTTP 请求中取值。
                                           @Param("sentiment") String sentiment,
                                           // 绑定参数 category，按注解名称从 SQL 映射或 HTTP 请求中取值。
                                           @Param("category") String category,
                                           // 绑定参数 offset，按注解名称从 SQL 映射或 HTTP 请求中取值。
                                           @Param("offset") int offset,
                                           // 绑定参数 pageSize，按注解名称从 SQL 映射或 HTTP 请求中取值。
                                           @Param("pageSize") int pageSize);

    // 统计筛选条件下的内容数。
    long contentCount(@Param("eventId") String eventId,
                      // 绑定参数 query，按注解名称从 SQL 映射或 HTTP 请求中取值。
                      @Param("query") String query,
                      // 绑定参数 platform，按注解名称从 SQL 映射或 HTTP 请求中取值。
                      @Param("platform") String platform,
                      // 绑定参数 sentiment，按注解名称从 SQL 映射或 HTTP 请求中取值。
                      @Param("sentiment") String sentiment,
                      // 绑定参数 category，按注解名称从 SQL 映射或 HTTP 请求中取值。
                      @Param("category") String category);

    // 查询关键词分析样本。
    List<Map<String, Object>> keywordAnalysisRows(@Param("eventId") String eventId, @Param("limit") int limit);

    // 统计主题数量。
    Map<String, Object> topicCount(@Param("eventId") String eventId);

    // 查询主题排名。
    List<Map<String, Object>> topicRank(@Param("eventId") String eventId, @Param("limit") int limit);

    // 查询最新批次。
    Map<String, Object> latestBatch(@Param("eventId") String eventId);

    // 查询噪声与重复记录汇总。
    List<Map<String, Object>> noiseSummary(@Param("eventId") String eventId);

    // 查询 ETL 批次列表。
    List<Map<String, Object>> batches(@Param("limit") int limit);

    // 按批次查询任务日志。
    List<Map<String, Object>> taskLogs(@Param("batchId") String batchId);

    // 统计事件数量。
    Map<String, Object> eventCount();
}
