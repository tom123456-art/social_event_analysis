package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.community.CommunityService;
import com.social.hotspot.service.user.UserManagementService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

// 注册控制器，返回对象由 Spring 转为 JSON。
@RestController
// 设置控制器统一接口路径前缀。
@RequestMapping("/api/admin")
/** 后台业务接口：提供用户、人工标注和互动记录的管理接口。 */
// 定义后台业务控制器类。
public class AdminUserCommunityController {
    // 集中保存用户管理服务、互动与投稿服务。
    private final UserManagementService userManagementService;
    private final CommunityService communityService;

    // 构造后台业务控制器，接收所需组件并保存依赖。
    public AdminUserCommunityController(UserManagementService userManagementService, CommunityService communityService) {
        this.userManagementService = userManagementService;
        this.communityService = communityService;
    }

    // 将 GET 请求 /users 交给下面的方法。
    @GetMapping("/users")
    // 查询用户列表。
    public ApiResponse<List<Map<String, Object>>> users() {
        return ApiResponse.ok(userManagementService.users());
    }

    // 将 POST 请求 /users 交给下面的方法。
    @PostMapping("/users")
    // 新增用户。
    public ApiResponse<Map<String, Object>> addUser(@RequestBody Map<String, Object> item) {
        return ApiResponse.ok(userManagementService.saveUser(item));
    }

    // 将 PUT 请求 /users/{id} 交给下面的方法。
    @PutMapping("/users/{id}")
    // 更新用户资料。
    public ApiResponse<Map<String, Object>> updateUser(
            // 绑定参数 id，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @PathVariable("id") Long id,
            // 将 JSON 请求体转换为键值映射，作为业务输入。
            @RequestBody Map<String, Object> item) {
        return ApiResponse.ok(userManagementService.updateUser(id, item));
    }

    // 将 DELETE 请求 /users/{id} 交给下面的方法。
    @DeleteMapping("/users/{id}")
    // 删除用户。
    public ApiResponse<Boolean> deleteUser(@PathVariable("id") Long id) {
        return ApiResponse.ok(userManagementService.deleteUser(id));
    }

    // 将 GET 请求 /interactions 交给下面的方法。
    @GetMapping("/interactions")
    // 查询近期互动。
    public ApiResponse<List<Map<String, Object>>> interactions(
            // 绑定参数 name = "limit", defaultValue = "80"，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "limit", defaultValue = "80") int limit) {
        return ApiResponse.ok(communityService.interactions(Math.min(limit, 200)));
    }

    // 将 DELETE 请求 /interactions/{id} 交给下面的方法。
    @DeleteMapping("/interactions/{id}")
    // 删除指定互动。
    public ApiResponse<Boolean> deleteInteraction(@PathVariable("id") Long id) {
        return ApiResponse.ok(communityService.deleteInteraction(id));
    }

    // 将 GET 请求 /submissions 交给下面的方法。
    @GetMapping("/submissions")
    // 查询近期投稿。
    public ApiResponse<List<Map<String, Object>>> submissions(
            // 绑定参数 name = "limit", defaultValue = "80"，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "limit", defaultValue = "80") int limit) {
        return ApiResponse.ok(communityService.submissions(Math.min(limit, 200)));
    }

    // 将 PUT 请求 /submissions/{id}/status 交给下面的方法。
    @PutMapping("/submissions/{id}/status")
    // 更新投稿审核状态。
    public ApiResponse<Boolean> updateSubmissionStatus(
            // 绑定参数 id，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @PathVariable("id") Long id,
            // 将 JSON 请求体转换为键值映射，作为业务输入。
            @RequestBody Map<String, Object> item) {
        // 读取处理状态，缺失时使用默认值。
        String status = String.valueOf(item.getOrDefault("status", "PENDING"));
        return ApiResponse.ok(communityService.updateSubmissionStatus(id, status));
    }

    // 将 DELETE 请求 /submissions/{id} 交给下面的方法。
    @DeleteMapping("/submissions/{id}")
    // 删除投稿。
    public ApiResponse<Boolean> deleteSubmission(@PathVariable("id") Long id) {
        return ApiResponse.ok(communityService.deleteSubmission(id));
    }

    // 将 GET 请求 /contents 交给下面的方法。
    @GetMapping("/contents")
    // 查询指定事件内容。
    public ApiResponse<List<Map<String, Object>>> contents(
            // 绑定参数 name = "eventId", defaultValue = "public_rss_latest"，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "eventId", defaultValue = "public_rss_latest") String eventId,
            // 绑定参数 name = "limit", defaultValue = "100"，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return ApiResponse.ok(communityService.contents(eventId, Math.min(limit, 300)));
    }
}
