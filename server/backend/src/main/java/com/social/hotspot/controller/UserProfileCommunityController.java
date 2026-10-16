// 声明包名 com.social.hotspot.controller.user。
package com.social.hotspot.controller;

// 引入统一接口响应对象。
import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.auth.AuthService;
import com.social.hotspot.exception.AuthException;
import jakarta.servlet.http.HttpServletRequest;
// 引入互动与投稿业务接口。
import com.social.hotspot.service.community.CommunityService;
// 引入个人资料接口。
import com.social.hotspot.service.user.UserProfileService;
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
// 引入 JSON 控制器注解。
import org.springframework.web.bind.annotation.RestController;

// 引入列表，保存多条数据。
import java.util.List;
// 引入键值映射，按字段名存取数据。
import java.util.Map;

// 注册控制器，返回对象由 Spring 转为 JSON。
@RestController
// 设置控制器统一接口路径前缀。
@RequestMapping("/api/front")
/** 前台业务接口：提供个人资料、用户互动和内容投稿接口。 */
// 定义前台业务控制器类。
public class UserProfileCommunityController {
    // 保存个人资料服务。
    private final UserProfileService userProfileService;
    // 保存互动与投稿服务。
    private final CommunityService communityService;

    // 构造前台业务控制器，接收所需组件并保存依赖。
    public UserProfileCommunityController(UserProfileService userProfileService, CommunityService communityService) {
        // 把传入的个人资料服务保存到成员字段。
        this.userProfileService = userProfileService;
        // 把传入的互动与投稿服务保存到成员字段。
        this.communityService = communityService;
    // 结束当前方法。
    }

    // 将 GET 请求 /profile 交给下面的方法。
    @GetMapping("/profile")
    // 只查询认证账号的个人资料。
    public ApiResponse<Map<String, Object>> profile(
            HttpServletRequest request) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(userProfileService.profile(String.valueOf(AuthService.current(request).get("username"))));
    // 结束当前方法或多行参数定义。
    }

    // 将 PUT 请求 /profile/{username} 交给下面的方法。
    @PutMapping("/profile/{username}")
    // 更新个人资料并保留原角色与状态。
    public ApiResponse<Map<String, Object>> updateProfile(
            // 绑定参数 username，按注解名称从 SQL 映射或 HTTP 请求中取值。
            @PathVariable("username") String username,
            // 将 JSON 请求体转换为键值映射，作为业务输入。
            @RequestBody Map<String, Object> item, HttpServletRequest request) {
        if (!username.equals(AuthService.current(request).get("username"))) throw new AuthException(403, "只能修改本人资料。");
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(userProfileService.updateProfile(username, item));
    // 结束当前方法或多行参数定义。
    }

    // 将 GET 请求 /interactions 交给下面的方法。
    @GetMapping("/interactions")
    // 查询近期互动。
    public ApiResponse<List<Map<String, Object>>> interactions(
            HttpServletRequest request) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(communityService.userInteractions(String.valueOf(AuthService.current(request).get("username")), 30));
    // 结束当前方法或多行参数定义。
    }

    // 将 POST 请求 /interactions 交给下面的方法。
    @PostMapping("/interactions")
    // 补充用户信息和默认字段后保存互动。
    public ApiResponse<Map<String, Object>> addInteraction(@RequestBody Map<String, Object> item, HttpServletRequest request) {
        item.put("username", AuthService.current(request).get("username"));
        item.put("user_id", AuthService.current(request).get("id"));
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(communityService.addInteraction(item));
    // 结束当前方法。
    }

    // 将 GET 请求 /submissions 交给下面的方法。
    @GetMapping("/submissions")
    // 查询近期投稿。
    public ApiResponse<List<Map<String, Object>>> submissions(
            HttpServletRequest request) {
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(communityService.userSubmissions(String.valueOf(AuthService.current(request).get("username")), 30));
    // 结束当前方法或多行参数定义。
    }

    // 将 POST 请求 /submissions 交给下面的方法。
    @PostMapping("/submissions")
    // 补充用户信息后保存投稿。
    public ApiResponse<Map<String, Object>> addSubmission(@RequestBody Map<String, Object> item, HttpServletRequest request) {
        item.put("username", AuthService.current(request).get("username"));
        item.put("user_id", AuthService.current(request).get("id"));
        // 将结果包装为统一成功响应。
        return ApiResponse.ok(communityService.addSubmission(item));
    // 结束当前方法。
    }
// 结束类定义。
}
