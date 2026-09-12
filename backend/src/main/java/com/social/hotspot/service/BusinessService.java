package com.social.hotspot.service;

import com.social.hotspot.mapper.BusinessMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class BusinessService {
    private static final String CANONICAL_EVENT_ID = "public_rss_latest";
    private final BusinessMapper mapper;

    public BusinessService(BusinessMapper mapper) {
        this.mapper = mapper;
    }

    public List<Map<String, Object>> users() {
        return mapper.users();
    }

    public Map<String, Object> saveUser(Map<String, Object> item) {
        item.putIfAbsent("role", "USER");
        item.putIfAbsent("status", "ENABLED");
        mapper.insertUser(item);
        return item;
    }

    public Map<String, Object> updateUser(Long id, Map<String, Object> item) {
        item.putIfAbsent("role", "USER");
        item.putIfAbsent("status", "ENABLED");
        mapper.updateUser(id, item);
        return mapper.userById(id);
    }

    public boolean deleteUser(Long id) {
        return mapper.deleteUser(id) > 0;
    }

    public Map<String, Object> profile(String username) {
        Map<String, Object> user = mapper.userByUsername(username);
        if (user != null) {
            return user;
        }
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("username", username);
        item.put("nickname", username);
        item.put("role", "USER");
        item.put("status", "ENABLED");
        mapper.insertUser(item);
        return item;
    }

    public Map<String, Object> updateProfile(String username, Map<String, Object> item) {
        Map<String, Object> user = profile(username);
        item.put("role", user.getOrDefault("role", "USER"));
        item.put("status", user.getOrDefault("status", "ENABLED"));
        mapper.updateUser(number(user.get("id")).longValue(), item);
        return mapper.userByUsername(username);
    }

    public List<Map<String, Object>> interactions(int limit) {
        return mapper.interactions(limit);
    }

    public List<Map<String, Object>> userInteractions(String username, int limit) {
        return mapper.userInteractions(username, limit);
    }

    public Map<String, Object> addInteraction(Map<String, Object> item) {
        String username = text(item.getOrDefault("username", "student"));
        Map<String, Object> user = profile(username);
        item.put("user_id", user.get("id"));
        item.put("username", username);
        item.putIfAbsent("event_id", CANONICAL_EVENT_ID);
        item.putIfAbsent("action_type", "comment");
        item.putIfAbsent("sentiment_label", sentiment(text(item.get("comment_text"))));
        mapper.insertInteraction(item);
        return item;
    }

    public boolean deleteInteraction(Long id) {
        return mapper.deleteInteraction(id) > 0;
    }

    public List<Map<String, Object>> submissions(int limit) {
        return mapper.submissions(limit);
    }

    public List<Map<String, Object>> userSubmissions(String username, int limit) {
        return mapper.userSubmissions(username, limit);
    }

    public Map<String, Object> addSubmission(Map<String, Object> item) {
        String username = text(item.getOrDefault("username", "student"));
        Map<String, Object> user = profile(username);
        item.put("user_id", user.get("id"));
        item.put("username", username);
        item.putIfAbsent("event_id", CANONICAL_EVENT_ID);
        item.putIfAbsent("platform", "FRONT");
        mapper.insertSubmission(item);
        return item;
    }

    public boolean updateSubmissionStatus(Long id, String status) {
        return mapper.updateSubmissionStatus(id, status) > 0;
    }

    public boolean deleteSubmission(Long id) {
        return mapper.deleteSubmission(id) > 0;
    }

    public List<Map<String, Object>> contents(String eventId, int limit) {
        return mapper.contents(eventId, limit);
    }

    private String sentiment(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (value.contains("质疑") || value.contains("争议") || value.contains("失望") || value.contains("愤怒")) {
            return "negative";
        }
        if (value.contains("支持") || value.contains("开心") || value.contains("祝贺") || value.contains("精彩")) {
            return "positive";
        }
        return "neutral";
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        return Long.parseLong(String.valueOf(value));
    }
}
