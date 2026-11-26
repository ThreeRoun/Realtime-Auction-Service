package com.threeroun.auctionengine.service;

import java.util.UUID;

// 다른 사람의 입찰을 취소하려 한 경우
public class BidCancelNotAllowedException extends RuntimeException {

    public BidCancelNotAllowedException(UUID bidId) {
        super("본인의 입찰만 취소할 수 있습니다: " + bidId);
    }
}
