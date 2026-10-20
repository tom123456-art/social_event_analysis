package com.social.hotspot.security;

import com.social.hotspot.exception.AuthException;
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

    // 在进入受保护控制器前完成身份认证和管理员授权。
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 放行跨域预检请求，不要求携带认证令牌。
        if ("OPTIONS".equals(request.getMethod())) return true;
        // 校验 Authorization 中的令牌并读取用户资料；去掉部署上下文前缀，得到应用内接口路径。
        Map<String, Object> user = auth.authenticate(request.getHeader("Authorization"));
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // 管理接口和 ETL 接口仅允许管理员访问。
        if ((path.startsWith("/api/admin/") || path.startsWith("/api/etl/")) && !"admin".equals(user.get("role")))
            throw new AuthException(403, "没有执行此操作的权限。");
        // 将认证主体写入请求，供控制器和业务服务读取。
        request.setAttribute(AuthService.PRINCIPAL, user);
        // 身份和权限均通过后继续请求处理。
        return true;
    }
}
