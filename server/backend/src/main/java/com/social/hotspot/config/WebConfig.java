package com.social.hotspot.config;
import com.social.hotspot.security.AuthInterceptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 注册配置类，启动时加载配置。
@Configuration
/** Web 配置类：统一配置跨域和 Web 层公共行为。 */
// 定义跨域配置类。
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor auth;
    public WebConfig(AuthInterceptor auth) { this.auth = auth; }
    @Override public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(auth).addPathPatterns("/api/events", "/api/events/**", "/api/admin/**", "/api/etl/**", "/api/front/**", "/api/auth/me", "/api/auth/logout", "/api/auth/password");
    }
    @Override
    // 配置 /api 下的跨域访问规则。
    public void addCorsMappings(CorsRegistry registry) {
        // 跨域规则适用于 /api/ 下所有接口。
        registry.addMapping("/api/**")
                // 允许任意来源的跨域请求；允许列出的 HTTP 方法跨域访问；允许任意请求头跨域访问。
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
