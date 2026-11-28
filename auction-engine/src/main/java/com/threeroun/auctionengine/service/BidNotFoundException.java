package com.threeroun.auctionengine.service;

import java.util.UUID;

public class BidNotFoundException extends RuntimeException {

    public BidNotFoundException(UUID bidId) {
        super("입찰을 찾을 수 없습니다: " + bidId);
    }
}
