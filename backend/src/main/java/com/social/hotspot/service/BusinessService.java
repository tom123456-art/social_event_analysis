package com.social.hotspot.service;

import java.util.List;
import java.util.Map;

public interface BusinessService {
    List<Map<String, Object>> users();

    Map<String, Object> saveUser(Map<String, Object> item);

    Map<String, Object> updateUser(Long id, Map<String, Object> item);

    boolean deleteUser(Long id);

    Map<String, Object> profile(String username);

    Map<String, Object> updateProfile(String username, Map<String, Object> item);

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
