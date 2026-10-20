package com.social.hotspot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 启用自动配置，扫描启动类所在包及其子包的组件。
@SpringBootApplication
// 扫描指定包的 MyBatis 接口，创建数据库访问代理。
@MapperScan("com.social.hotspot.mapper")
/** 应用启动入口：负责启动 Spring Boot 后端服务。 */
// 定义后端启动入口类。
public class SocialHotspotApplication {
    // 程序入口，启动应用或运行本类回归检查。
    public static void main(String[] args) {
        // 启动 Spring Boot，扫描组件、注入依赖并运行 Web 服务。
        SpringApplication.run(SocialHotspotApplication.class, args);
    }
}
