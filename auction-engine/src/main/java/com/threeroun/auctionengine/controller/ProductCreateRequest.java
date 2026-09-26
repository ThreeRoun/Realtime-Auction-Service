package com.threeroun.auctionengine.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDateTime;
import java.util.UUID;

// sellerId를 body로 직접 받는 것도 bidderId와 마찬가지로 인증 체계가 없는 MVP 임시방편.
// startAt은 프론트 상품 등록 폼에 시작 시각 입력란 자체가 없어서(등록하면 바로 시작한다고 가정) 선택값으로 둔다.
// 생략 시 ProductController에서 현재 시각으로 채운다.
public record ProductCreateRequest(
        @NotBlank String title,
        String description,
        @NotNull @PositiveOrZero Integer startingPrice,
        @NotNull @Positive Integer bidUnit,
        @NotNull UUID sellerId,
        LocalDateTime startAt,
        @NotNull LocalDateTime endAt
) {
}
