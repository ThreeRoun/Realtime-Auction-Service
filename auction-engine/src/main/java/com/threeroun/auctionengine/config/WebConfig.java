package com.threeroun.auctionengine.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

// MVP 단계라 인증이 없고 배포 도메인도 아직 안 정해졌으므로 전체 허용한다.
// 인증 도입 시점에 allowedOrigins를 실제 프론트 도메인으로 좁혀야 한다.
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.product-image-dir}")
    private String productImageDir;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    // ProductImageService가 저장한 파일을 /images/<파일명>으로 그대로 정적 서빙한다.
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = "file:" + Path.of(productImageDir).toAbsolutePath() + "/";
        registry.addResourceHandler("/images/**").addResourceLocations(location);
    }
}
