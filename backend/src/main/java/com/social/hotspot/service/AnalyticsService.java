package com.social.hotspot.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface AnalyticsService {
    List<Map<String, Object>> events();

    Map<String, Object> dashboard(String eventId);

    Map<String, Object> adminOverview();

    List<Map<String, Object>> batches(int limit);

    List<Map<String, Object>> taskLogs(String batchId);

    Map<String, Object> runLocalCsvEtl(Path rawCsvPath, String requestedEventId) throws Exception;

    void clearReplacedDatasetHistory(String eventId, String currentBatchId);
}
