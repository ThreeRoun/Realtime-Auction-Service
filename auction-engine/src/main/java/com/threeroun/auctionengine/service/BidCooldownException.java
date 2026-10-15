package com.threeroun.auctionengine.service;

import java.util.UUID;

public class BidCooldownException extends RuntimeException {

    public BidCooldownException(UUID productId) {
        super("직전 입찰 후 너무 짧은 시간 안에 다시 입찰했습니다: " + productId);
    }
}
