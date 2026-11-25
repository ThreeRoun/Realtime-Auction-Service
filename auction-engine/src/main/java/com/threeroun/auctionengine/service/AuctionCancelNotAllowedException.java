package com.threeroun.auctionengine.service;

import java.util.UUID;

public class AuctionCancelNotAllowedException extends RuntimeException {

    public AuctionCancelNotAllowedException(UUID productId) {
        super("본인이 등록한 경매만 취소할 수 있습니다: " + productId);
    }
}
