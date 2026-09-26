package com.threeroun.auctionengine.controller;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

// bidderId를 body로 직접 받는 건 아직 인증 체계가 없는 MVP 임시방편.
// 통합 단계에서 인증 토큰 기반으로 교체될 필드.
public record BidRequest(
        @NotNull UUID bidderId,
        @NotNull @Positive Integer amount
) {
}
