package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.ProductStatus;

import java.util.UUID;

public class AuctionNotInProgressException extends RuntimeException {

    public AuctionNotInProgressException(UUID productId, ProductStatus status) {
        super("진행 중인 경매가 아닙니다: productId=" + productId + ", status=" + status);
    }
}
