package com.social.hotspot.service;

import java.util.List;
import java.util.Map;

/** 分析服务接口：定义前端分析页面需要的数据能力。 */
public interface AnalyticsService {
    List<Map<String, Object>> events();

    Map<String, Object> dashboard(String eventId);

    Map<String, Object> propagationEvidence(String eventId, String platform, String category, String hour, int page);

    List<Map<String, Object>> trendContentRankByRange(String eventId, String startDate, String endDate);

    List<Map<String, Object>> keywordAnalysis(String eventId);

    List<Map<String, Object>> sentimentAnalysis(String eventId);

    Map<String, Object> contentPage(String eventId, String query, String platform,
                                    String sentiment, String category, int page, int pageSize);

    Map<String, Object> adminOverview();

    List<Map<String, Object>> batches(int limit);

    List<Map<String, Object>> taskLogs(String batchId);
}
