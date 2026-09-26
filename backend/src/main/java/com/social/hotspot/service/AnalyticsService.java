package com.social.hotspot.service;

import java.util.List;
import java.util.Map;

public interface AnalyticsService {
    List<Map<String, Object>> events();

    Map<String, Object> dashboard(String eventId);

    List<Map<String, Object>> keywordAnalysis(String eventId);

    List<Map<String, Object>> sentimentAnalysis(String eventId);

    List<Map<String, Object>> topicKeyContents(String eventId, String topicId);

    Map<String, Object> adminOverview();

    List<Map<String, Object>> batches(int limit);

    List<Map<String, Object>> taskLogs(String batchId);
}
