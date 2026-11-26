package com.threeroun.auctionengine.service;

import java.util.UUID;

// 취소할 수 없는 경매: 이미 끝났거나(마감 시각이 지났거나 종료 상태) 유효 입찰이 있는 경우.
// 프론트가 사유별로 다른 안내를 띄울 수 있게 에러 코드를 나눠 둔다.
public class AuctionNotCancellableException extends RuntimeException {

    public static final String AUCTION_ALREADY_ENDED = "AUCTION_ALREADY_ENDED";
    public static final String AUCTION_HAS_BIDS = "AUCTION_HAS_BIDS";

    private final String code;

    private AuctionNotCancellableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static AuctionNotCancellableException alreadyEnded(UUID productId) {
        return new AuctionNotCancellableException(AUCTION_ALREADY_ENDED,
                "이미 종료된 경매는 취소할 수 없습니다: " + productId);
    }

    public static AuctionNotCancellableException hasBids(UUID productId) {
        return new AuctionNotCancellableException(AUCTION_HAS_BIDS,
                "유효한 입찰이 있는 경매는 취소할 수 없습니다: " + productId);
    }

    public String getCode() {
        return code;
    }
}
