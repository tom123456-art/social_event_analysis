package com.social.hotspot.service.impl;

import com.social.hotspot.mapper.CommunityMapper;
import com.social.hotspot.service.CommunityService;
import com.social.hotspot.service.UserProfileService;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
public class CommunityServiceImpl implements CommunityService {
    private static final String CANONICAL_EVENT_ID = "public_rss_latest";
    private final CommunityMapper mapper;
    private final UserProfileService userProfileService;
    public CommunityServiceImpl(CommunityMapper mapper, UserProfileService userProfileService) { this.mapper = mapper; this.userProfileService = userProfileService; }
    @Override public List<Map<String, Object>> interactions(int limit) { return mapper.interactions(limit); }
    @Override public List<Map<String, Object>> userInteractions(String username, int limit) { return mapper.userInteractions(username, limit); }
    @Override public Map<String, Object> addInteraction(Map<String, Object> item) {
        String username = text(item.getOrDefault("username", "student")); Map<String, Object> user = userProfileService.profile(username);
        item.put("user_id", user.get("id")); item.put("username", username); item.putIfAbsent("event_id", CANONICAL_EVENT_ID); item.putIfAbsent("action_type", "comment"); item.putIfAbsent("sentiment_label", sentiment(text(item.get("comment_text")))); mapper.insertInteraction(item); return item;
    }
    @Override public boolean deleteInteraction(Long id) { return mapper.deleteInteraction(id) > 0; }
    @Override public List<Map<String, Object>> submissions(int limit) { return mapper.submissions(limit); }
    @Override public List<Map<String, Object>> userSubmissions(String username, int limit) { return mapper.userSubmissions(username, limit); }
    @Override public Map<String, Object> addSubmission(Map<String, Object> item) {
        String username = text(item.getOrDefault("username", "student")); Map<String, Object> user = userProfileService.profile(username);
        item.put("user_id", user.get("id")); item.put("username", username); item.putIfAbsent("event_id", CANONICAL_EVENT_ID); item.putIfAbsent("platform", "FRONT"); mapper.insertSubmission(item); return item;
    }
    @Override public boolean updateSubmissionStatus(Long id, String status) { return mapper.updateSubmissionStatus(id, status) > 0; }
    @Override public boolean deleteSubmission(Long id) { return mapper.deleteSubmission(id) > 0; }
    @Override public List<Map<String, Object>> contents(String eventId, int limit) { return mapper.contents(eventId, limit); }
    private String sentiment(String text) {
        if (text.contains("质疑") || text.contains("争议") || text.contains("失望") || text.contains("愤怒")) return "negative";
        if (text.contains("支持") || text.contains("开心") || text.contains("祝贺") || text.contains("精彩")) return "positive";
        return "neutral";
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
