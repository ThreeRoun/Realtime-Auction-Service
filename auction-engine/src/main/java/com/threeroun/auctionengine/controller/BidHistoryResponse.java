package com.threeroun.auctionengine.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.threeroun.auctionengine.domain.Bid;

import java.time.LocalDateTime;
import java.util.UUID;

// productId는 URL 경로에 이미 있으므로 응답에 다시 넣지 않는다.
public record BidHistoryResponse(
        UUID bidId,
        UUID bidderId,
        Integer amount,
        LocalDateTime bidAt,
        @JsonProperty("isValid") boolean isValid
) {
    public static BidHistoryResponse from(Bid bid) {
        return new BidHistoryResponse(
                bid.getId(),
                bid.getBidder().getId(),
                bid.getAmount(),
                bid.getBidAt(),
                bid.isValid());
    }
}
