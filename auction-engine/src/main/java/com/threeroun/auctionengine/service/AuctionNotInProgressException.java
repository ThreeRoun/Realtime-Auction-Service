package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.ProductStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public class AuctionNotInProgressException extends RuntimeException {

    public AuctionNotInProgressException(UUID productId, ProductStatus status) {
        super("진행 중인 경매가 아닙니다: productId=" + productId + ", status=" + status);
    }

    // 상태는 아직 진행중이지만 마감 시각이 지난 경우 (스케줄러가 아직 마감 처리를 못 한 구간).
    // 프론트가 같은 오류 코드(AUCTION_NOT_IN_PROGRESS)로 안내할 수 있게 같은 예외를 쓴다.
    public AuctionNotInProgressException(UUID productId, LocalDateTime endAt) {
        super("마감 시각이 지났습니다: productId=" + productId + ", endAt=" + endAt);
    }
}
