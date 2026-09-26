package com.threeroun.auctionengine.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDateTime;
import java.util.UUID;

// sellerId를 body로 직접 받는 것도 bidderId와 마찬가지로 인증 체계가 없는 MVP 임시방편.
public record ProductCreateRequest(
        @NotBlank String title,
        String description,
        @NotNull @PositiveOrZero Integer startingPrice,
        @NotNull @Positive Integer bidUnit,
        @NotNull UUID sellerId,
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime endAt
) {
}
