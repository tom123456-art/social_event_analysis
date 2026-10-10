package com.social.hotspot.service;

import java.util.List;
import java.util.Map;

public interface CommunityService {
    List<Map<String, Object>> interactions(int limit);
    List<Map<String, Object>> userInteractions(String username, int limit);
    Map<String, Object> addInteraction(Map<String, Object> item);
    boolean deleteInteraction(Long id);
    List<Map<String, Object>> submissions(int limit);
    List<Map<String, Object>> userSubmissions(String username, int limit);
    Map<String, Object> addSubmission(Map<String, Object> item);
    boolean updateSubmissionStatus(Long id, String status);
    boolean deleteSubmission(Long id);
    List<Map<String, Object>> contents(String eventId, int limit);
}
