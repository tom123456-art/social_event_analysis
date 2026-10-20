package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.exception.AuthException;
import com.social.hotspot.service.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    // 注入认证业务服务。
    private final AuthService auth;
    // 按客户端地址记录短时间窗口内的认证请求次数。
    private final Map<String, Attempt> attempts = new HashMap<>();
    // 保存限流窗口起始时间和已请求次数。
    private record Attempt(long started, int count) {}
    // 创建认证控制器并保存业务服务引用。
    public AuthController(AuthService auth) { this.auth = auth; }

    // 接收注册请求并执行地址级限流。
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, Object> item, HttpServletRequest request) {
        // 在调用注册业务前限制异常高频请求。
        throttle(request.getRemoteAddr());
        // 返回注册后的用户信息。
        return ApiResponse.ok(auth.register(item));
    }
    // 接收登录请求并执行地址级限流。
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> item, HttpServletRequest request) {
        // 在校验账号密码前限制异常高频请求。
        throttle(request.getRemoteAddr());
        // 返回登录令牌和用户信息。
        return ApiResponse.ok(auth.login(item));
    }
    // 查询当前请求对应的登录用户。
    @GetMapping("/me") public ApiResponse<Map<String, Object>> me(HttpServletRequest request) { return ApiResponse.ok(AuthService.current(request)); }
    @PostMapping("/logout") public ApiResponse<Boolean> logout(HttpServletRequest request) {
        // 将 Authorization 请求头交给认证服务失效处理。
        auth.logout(request.getHeader("Authorization")); return ApiResponse.ok(true);
    }
    // 修改当前登录用户的密码。
    @PutMapping("/password") public ApiResponse<Boolean> password(@RequestBody Map<String, Object> item, HttpServletRequest request) {
        // 密码操作同样受到地址级限流保护。
        throttle(request.getRemoteAddr());
        // 从当前登录会话读取用户 ID 并执行密码修改。
        auth.changePassword(((Number) AuthService.current(request).get("id")).longValue(), item); return ApiResponse.ok(true);
    }
    // 清理过期记录并拒绝超过阈值的认证请求。
    private synchronized void throttle(String ip) {
        // 读取当前毫秒时间；删除已经超过一分钟窗口的客户端记录；读取当前客户端记录，不存在时创建新窗口。
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(entry -> now - entry.getValue().started() >= 60000);
        Attempt previous = attempts.getOrDefault(ip, new Attempt(now, 0));
        // 限制单地址频率，并限制内存中客户端数量。
        if (previous.count() >= 20 || (!attempts.containsKey(ip) && attempts.size() >= 10000)) throw new AuthException(429, "操作过于频繁，请稍后再试。");
        // 写回递增后的请求次数。
        attempts.put(ip, new Attempt(previous.started(), previous.count() + 1));
    }
}
    // 注销当前请求携带的令牌。
