package com.social.hotspot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.social.hotspot.mapper")
/** 应用启动入口：负责启动 Spring Boot 后端服务。 */
public class SocialHotspotApplication {
    public static void main(String[] args) {
        SpringApplication.run(SocialHotspotApplication.class, args);
    }
}
