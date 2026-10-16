// 声明包名 com.social.hotspot.config。
package com.social.hotspot.config;
import com.social.hotspot.security.AuthInterceptor;

// 引入 Spring 配置类注解。
import org.springframework.context.annotation.Configuration;
// 引入跨域规则注册器。
import org.springframework.web.servlet.config.annotation.CorsRegistry;
// 引入 MVC 配置扩展接口。
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
    // 标记此方法重写接口或父类方法。
    @Override
    // 配置 /api 下的跨域访问规则。
    public void addCorsMappings(CorsRegistry registry) {
        // 跨域规则适用于 /api/ 下所有接口。
        registry.addMapping("/api/**")
                // 允许任意来源的跨域请求。
                .allowedOriginPatterns("*")
                // 允许列出的 HTTP 方法跨域访问。
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                // 允许任意请求头跨域访问。
                .allowedHeaders("*");
    // 结束当前方法。
    }
// 结束类定义。
}
