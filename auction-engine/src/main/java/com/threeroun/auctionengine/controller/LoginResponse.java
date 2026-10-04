package com.threeroun.auctionengine.controller;

import java.time.LocalDateTime;

public record LoginResponse(
        String accessToken,
        LocalDateTime expiresAt
) {
}
