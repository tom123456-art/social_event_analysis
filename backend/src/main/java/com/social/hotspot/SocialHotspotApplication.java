package com.social.hotspot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.social.hotspot.mapper")
public class SocialHotspotApplication {
    public static void main(String[] args) {
        SpringApplication.run(SocialHotspotApplication.class, args);
    }
}
