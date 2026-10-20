package com.social.hotspot.service.community;

import com.social.hotspot.mapper.CommunityMapper;
import com.social.hotspot.service.user.UserProfileService;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
// 定义互动与投稿业务服务类。
public class CommunityService {
    // 定义并初始化统一事件标识；保存数据库映射接口；保存个人资料服务。
    private static final String CANONICAL_EVENT_ID = "public_rss_latest";
    private final CommunityMapper mapper;
    private final UserProfileService userProfileService;
    // 构造互动与投稿业务服务，接收所需组件并保存依赖。
    public CommunityService(CommunityMapper mapper, UserProfileService userProfileService) { this.mapper = mapper; this.userProfileService = userProfileService; }
    // 查询近期互动。
    public List<Map<String, Object>> interactions(int limit) { return mapper.interactions(limit); }
    // 查询指定用户互动。
    public List<Map<String, Object>> userInteractions(String username, int limit) { return mapper.userInteractions(username, limit); }
    // 补充用户信息和默认字段后保存互动。
    public Map<String, Object> addInteraction(Map<String, Object> item) {
        // 读取用户名，缺少时用 student，再查询已注册账号的用户资料，不自动创建账号；补充用户、默认事件与评论类型，缺少情感时按关键词判断，保存互动后返回记录。
        String username = text(item.getOrDefault("username", "student")); Map<String, Object> user = userProfileService.profile(username);
        item.put("user_id", user.get("id")); item.put("username", username); item.putIfAbsent("event_id", CANONICAL_EVENT_ID); item.putIfAbsent("action_type", "comment"); item.putIfAbsent("sentiment_label", sentiment(text(item.get("comment_text")))); mapper.insertInteraction(item); return item;
    }
    // 删除指定互动；数据库受影响行数大于零才视为删除或更新成功。
    public boolean deleteInteraction(Long id) { return mapper.deleteInteraction(id) > 0; }
    // 查询近期投稿。
    public List<Map<String, Object>> submissions(int limit) { return mapper.submissions(limit); }
    // 查询指定用户投稿。
    public List<Map<String, Object>> userSubmissions(String username, int limit) { return mapper.userSubmissions(username, limit); }
    // 补充用户信息后保存投稿。
    public Map<String, Object> addSubmission(Map<String, Object> item) {
        // 读取用户名，缺少时用 student，再查询已注册账号的用户资料，不自动创建账号；补充投稿用户、默认事件与 FRONT 平台，写入数据库后返回投稿记录。
        String username = text(item.getOrDefault("username", "student")); Map<String, Object> user = userProfileService.profile(username);
        item.put("user_id", user.get("id")); item.put("username", username); item.putIfAbsent("event_id", CANONICAL_EVENT_ID); item.putIfAbsent("platform", "FRONT"); mapper.insertSubmission(item); return item;
    }
    // 更新投稿审核状态；数据库受影响行数大于零才视为删除或更新成功。
    public boolean updateSubmissionStatus(Long id, String status) { return mapper.updateSubmissionStatus(id, status) > 0; }
    // 删除投稿；数据库受影响行数大于零才视为删除或更新成功。
    public boolean deleteSubmission(Long id) { return mapper.deleteSubmission(id) > 0; }
    // 查询指定事件内容。
    public List<Map<String, Object>> contents(String eventId, int limit) { return mapper.contents(eventId, limit); }
    // 根据评论中的关键词确定情感标签。
    private String sentiment(String text) {
        // 手动评论命中负面词时返回 negative。
        if (text.contains("质疑") || text.contains("争议") || text.contains("失望") || text.contains("愤怒")) return "negative";
        // 手动评论命中正面词时返回 positive。
        if (text.contains("支持") || text.contains("开心") || text.contains("祝贺") || text.contains("精彩")) return "positive";
        return "neutral";
    }
    // 空值转为空字符串，非空对象转为文本。
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
