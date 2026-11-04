package com.social.hotspot.security;

import com.social.hotspot.service.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.Map;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    // 通过认证服务校验会话令牌。
    private final AuthService auth;
    // 构造拦截器并注入认证服务。
    public AuthInterceptor(AuthService auth) { this.auth = auth; }

    // 业务接口统一验证登录状态，不区分角色。
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 放行跨域预检请求，不要求携带认证令牌。
        if ("OPTIONS".equals(request.getMethod())) return true;
        // 校验令牌，并把数据库中的账号身份提供给控制器。
        Map<String, Object> user = auth.authenticate(request.getHeader("Authorization"));
        // 将认证主体写入请求，供控制器和业务服务读取。
        request.setAttribute(AuthService.PRINCIPAL, user);
        // 认证通过后继续处理。
        return true;
    }
}
