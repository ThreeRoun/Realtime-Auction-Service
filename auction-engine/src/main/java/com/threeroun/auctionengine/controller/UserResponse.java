package com.threeroun.auctionengine.controller;

import com.threeroun.auctionengine.domain.User;

import java.time.LocalDateTime;
import java.util.UUID;

// passwordHash는 응답에 절대 포함하지 않는다 (인증 체계가 아직 없어도, 해시값을
// 외부에 노출할 이유는 없음).
public record UserResponse(
        UUID id,
        String username,
        String email,
        Integer credit,
        LocalDateTime createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getCredit(),
                user.getCreatedAt());
    }
}
