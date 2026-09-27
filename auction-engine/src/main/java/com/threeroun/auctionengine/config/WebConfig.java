package com.threeroun.auctionengine.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// MVP 단계라 인증이 없고 배포 도메인도 아직 안 정해졌으므로 전체 허용한다.
// 인증 도입 시점에 allowedOrigins를 실제 프론트 도메인으로 좁혀야 한다.
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }
}
