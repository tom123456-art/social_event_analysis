// 声明包名 com.social.hotspot.service.analysis。
package com.social.hotspot.service.analysis;

// 引入列表，保存多条数据。
import java.util.List;
// 引入键值映射，按字段名存取数据。
import java.util.Map;

/** 分析服务接口：定义前端分析页面需要的数据能力。 */
// 定义分析业务接口，约定调用方式。
public interface AnalyticsService {
    // 查询可展示的事件列表。
    List<Map<String, Object>> events();

    // 组装热点概览并利用 Redis 缓存。
    Map<String, Object> dashboard(String eventId);

    // 按平台、类别与时间分页查询原始内容，核查统计依据。
    Map<String, Object> propagationEvidence(String eventId, String platform, String category, String hour, int page);

    // 查询日期范围内高热度内容。
    List<Map<String, Object>> trendContentRankByRange(String eventId, String startDate, String endDate);

    // 查询关键词分析内容样本。
    List<Map<String, Object>> keywordAnalysis(String eventId);

    // 查询情感样本。
    List<Map<String, Object>> sentimentAnalysis(String eventId);

    // 按筛选条件分页查询内容。
    Map<String, Object> contentPage(String eventId, String query, String platform,
                                    // 声明pageSize参数。
                                    String sentiment, String category, int page, int pageSize);

    // 读取后台事件、主题与批次概览。
    Map<String, Object> adminOverview();

    // 查询 ETL 批次列表。
    List<Map<String, Object>> batches(int limit);

    // 按批次查询任务日志。
    List<Map<String, Object>> taskLogs(String batchId);
// 结束接口定义。
}
