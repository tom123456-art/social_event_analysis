// 声明包名 com.social.hotspot.controller.user。
package com.social.hotspot.controller;

// 引入统一接口响应对象。
import com.social.hotspot.common.ApiResponse;
// 引入互动与投稿业务接口。
import com.social.hotspot.service.community.CommunityService;
// 引入用户管理接口。
import com.social.hotspot.service.user.UserManagementService;
// 引入 DELETE 路由注解。
import org.springframework.web.bind.annotation.DeleteMapping;
// 引入 GET 路由注解。
import org.springframework.web.bind.annotation.GetMapping;
// 引入路径参数绑定注解。
import org.springframework.web.bind.annotation.PathVariable;
// 引入 POST 路由注解。
import org.springframework.web.bind.annotation.PostMapping;
// 引入 PUT 路由注解。
import org.springframework.web.bind.annotation.PutMapping;
// 引入请求体绑定注解。
import org.springframework.web.bind.annotation.RequestBody;
// 引入路径映射注解。
import org.springframework.web.bind.annotation.RequestMapping;
// 引入查询参数绑定注解。
import org.springframework.web.bind.annotation.RequestParam;
// 引入 JSON 控制器注解。
import org.springframework.web.bind.annotation.RestController;

// 引入列表，保存多条数据。
import java.util.List;
// 引入键值映射，按字段名存取数据。
import java.util.Map;

// 注册控制器，返回对象由 Spring 转为 JSON。
@RestController
// 设置控制器统一接口路径前缀。
@RequestMapping("/api/admin")
/** 后台业务接口：提供用户、人工标注和互动记录的管理接口。 */
// 定义后台业务控制器类。
public class AdminUserCommunityController {
    // 保存用户管理服务。
    private final UserManagementService userManagementService;
    // 保存互动与投稿服务。
    private final CommunityService communityService;

    // 构造后台业务控制器，接收所需组件并保存依赖。
    public AdminUserCommunityController(UserManagementService userManagementService, CommunityService communityService) {
        // 把传入的用户管理服务保存到成员字段。
        this.userManagementService = userManagementService;
        // 把传入的互动与投稿服务保存到成员字段。
        this.communityService = communityService;
    // 结束当前方法。
    }

    // 将 GET 请求 /users 交给下面的方法。
    @GetMapping("/users")
    // 查询用户列表。
    public ApiResponse<List<Map<String, Object>>> users() {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(userManagementService.users());
    // 结束当前方法。
    }

    // 将 POST 请求 /users 交给下面的方法。
    @PostMapping("/users")
    // 新增用户。
    public ApiResponse<Map<String, Object>> addUser(@RequestBody Map<String, Object> item) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(userManagementService.saveUser(item));
    // 结束当前方法。
    }

    // 将 PUT 请求 /users/{id} 交给下面的方法。
    @PutMapping("/users/{id}")
    // 更新用户资料。
    public ApiResponse<Map<String, Object>> updateUser(
            // 绑定参数 id，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @PathVariable("id") Long id,
            // 将 JSON 请求体转换为键值映射，作为业务输入。
            @RequestBody Map<String, Object> item) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(userManagementService.updateUser(id, item));
    // 结束当前方法或多行参数定义。
    }

    // 将 DELETE 请求 /users/{id} 交给下面的方法。
    @DeleteMapping("/users/{id}")
    // 删除用户。
    public ApiResponse<Boolean> deleteUser(@PathVariable("id") Long id) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(userManagementService.deleteUser(id));
    // 结束当前方法。
    }

    // 将 GET 请求 /interactions 交给下面的方法。
    @GetMapping("/interactions")
    // 查询近期互动。
    public ApiResponse<List<Map<String, Object>>> interactions(
            // 绑定参数 name = "limit", defaultValue = "80"，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "limit", defaultValue = "80") int limit) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(communityService.interactions(Math.min(limit, 200)));
    // 结束当前方法或多行参数定义。
    }

    // 将 DELETE 请求 /interactions/{id} 交给下面的方法。
    @DeleteMapping("/interactions/{id}")
    // 删除指定互动。
    public ApiResponse<Boolean> deleteInteraction(@PathVariable("id") Long id) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(communityService.deleteInteraction(id));
    // 结束当前方法。
    }

    // 将 GET 请求 /submissions 交给下面的方法。
    @GetMapping("/submissions")
    // 查询近期投稿。
    public ApiResponse<List<Map<String, Object>>> submissions(
            // 绑定参数 name = "limit", defaultValue = "80"，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "limit", defaultValue = "80") int limit) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(communityService.submissions(Math.min(limit, 200)));
    // 结束当前方法或多行参数定义。
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
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(communityService.updateSubmissionStatus(id, status));
    // 结束当前方法或多行参数定义。
    }

    // 将 DELETE 请求 /submissions/{id} 交给下面的方法。
    @DeleteMapping("/submissions/{id}")
    // 删除投稿。
    public ApiResponse<Boolean> deleteSubmission(@PathVariable("id") Long id) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(communityService.deleteSubmission(id));
    // 结束当前方法。
    }

    // 将 GET 请求 /contents 交给下面的方法。
    @GetMapping("/contents")
    // 查询指定事件内容。
    public ApiResponse<List<Map<String, Object>>> contents(
            // 绑定参数 name = "eventId", defaultValue = "public_rss_latest"，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "eventId", defaultValue = "public_rss_latest") String eventId,
            // 绑定参数 name = "limit", defaultValue = "100"，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(communityService.contents(eventId, Math.min(limit, 300)));
    // 结束当前方法或多行参数定义。
    }
// 结束类定义。
}
