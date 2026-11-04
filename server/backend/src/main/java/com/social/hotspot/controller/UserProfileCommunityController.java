package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import com.social.hotspot.service.community.CommunityService;
import com.social.hotspot.service.user.UserProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

// 注册控制器，返回对象由 Spring 转为 JSON。
@RestController
// 设置控制器统一接口路径前缀。
@RequestMapping("/api/front")
/** 前台业务接口：提供个人资料、用户互动和内容投稿接口。 */
// 定义前台业务控制器类。
public class UserProfileCommunityController {
    // 集中保存个人资料服务、互动与投稿服务。
    private final UserProfileService userProfileService;
    private final CommunityService communityService;

    // 构造前台业务控制器，接收所需组件并保存依赖。
    public UserProfileCommunityController(UserProfileService userProfileService, CommunityService communityService) {
        this.userProfileService = userProfileService;
        this.communityService = communityService;
    }

    // 将 GET 请求 /profile 交给下面的方法。
    @GetMapping("/profile")
    // 只查询认证账号的个人资料。
    public ApiResponse<Map<String, Object>> profile(
            HttpServletRequest request) {
        return ApiResponse.ok(userProfileService.profile(String.valueOf(AuthService.current(request).get("username"))));
    }

    // 本人资料查询和更新使用同一地址，身份来自认证信息。
    @PutMapping("/profile")
    // 只更新允许编辑的资料字段。
    public ApiResponse<Map<String, Object>> updateProfile(
            // 将 JSON 请求体转换为键值映射，作为业务输入。
            @Valid @RequestBody ProfileUpdateRequest item, HttpServletRequest request) {
        String username = String.valueOf(AuthService.current(request).get("username"));
        return ApiResponse.ok(userProfileService.updateProfile(username, item.fields()));
    }

    // 将 GET 请求 /interactions 交给下面的方法。
    @GetMapping("/interactions")
    // 查询近期互动。
    public ApiResponse<List<Map<String, Object>>> interactions(
            HttpServletRequest request) {
        return ApiResponse.ok(communityService.userInteractions(String.valueOf(AuthService.current(request).get("username")), 30));
    }

    // 将 POST 请求 /interactions 交给下面的方法。
    @PostMapping("/interactions")
    // 补充用户信息和默认字段后保存互动。
    public ApiResponse<Map<String, Object>> addInteraction(@RequestBody Map<String, Object> item, HttpServletRequest request) {
        item.put("username", AuthService.current(request).get("username"));
        item.put("user_id", AuthService.current(request).get("id"));
        return ApiResponse.ok(communityService.addInteraction(item));
    }

    // 将 GET 请求 /submissions 交给下面的方法。
    @GetMapping("/submissions")
    // 查询近期投稿。
    public ApiResponse<List<Map<String, Object>>> submissions(
            HttpServletRequest request) {
        return ApiResponse.ok(communityService.userSubmissions(String.valueOf(AuthService.current(request).get("username")), 30));
    }

    // 将 POST 请求 /submissions 交给下面的方法。
    @PostMapping("/submissions")
    // 补充用户信息后保存投稿。
    public ApiResponse<Map<String, Object>> addSubmission(@RequestBody Map<String, Object> item, HttpServletRequest request) {
        item.put("username", AuthService.current(request).get("username"));
        item.put("user_id", AuthService.current(request).get("id"));
        return ApiResponse.ok(communityService.addSubmission(item));
    }
}
