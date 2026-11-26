package com.threeroun.auctionengine.service;

import java.util.UUID;

// 이미 취소됐거나, 최고가를 갱신하지 못한 입찰(is_valid=false)처럼 지금 효력이 없는 입찰을 취소하려 한 경우
public class BidNotCancellableException extends RuntimeException {

    public BidNotCancellableException(UUID bidId) {
        super("효력이 있는 입찰만 취소할 수 있습니다: " + bidId);
    }
}
