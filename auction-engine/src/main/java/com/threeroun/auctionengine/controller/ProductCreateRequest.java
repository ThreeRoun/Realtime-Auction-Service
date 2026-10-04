package com.threeroun.auctionengine.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDateTime;

// sellerId는 더 이상 body로 받지 않는다 - Authorization 헤더의 JWT에서 추출한다
// (JwtAuthenticationFilter 참고).
// startAt은 프론트 상품 등록 폼에 시작 시각 입력란 자체가 없어서(등록하면 바로 시작한다고 가정) 선택값으로 둔다.
// 생략 시 ProductController에서 현재 시각으로 채운다.
public record ProductCreateRequest(
        @NotBlank String title,
        String description,
        @NotNull @PositiveOrZero Integer startingPrice,
        @NotNull @Positive Integer bidUnit,
        LocalDateTime startAt,
        @NotNull LocalDateTime endAt
) {
}
