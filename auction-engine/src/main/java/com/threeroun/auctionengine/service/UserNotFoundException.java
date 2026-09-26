package com.threeroun.auctionengine.service;

import java.util.UUID;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UUID userId) {
        super("사용자를 찾을 수 없습니다: " + userId);
    }
}
