package com.threeroun.auctionengine.service;

import java.util.UUID;

public class SelfBidNotAllowedException extends RuntimeException {

    public SelfBidNotAllowedException(UUID productId) {
        super("본인이 등록한 상품에는 입찰할 수 없습니다: " + productId);
    }
}
