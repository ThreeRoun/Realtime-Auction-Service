package com.threeroun.auctionengine.controller;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

// bidderId는 더 이상 body로 받지 않는다 - Authorization 헤더의 JWT에서 추출한다
// (JwtAuthenticationFilter 참고).
public record BidRequest(
        @NotNull UUID productId,
        @NotNull @Positive Integer amount
) {
}
