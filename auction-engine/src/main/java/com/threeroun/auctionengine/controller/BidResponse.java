package com.threeroun.auctionengine.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.threeroun.auctionengine.domain.Bid;
import com.threeroun.auctionengine.service.BidResult;

import java.time.LocalDateTime;
import java.util.UUID;

// "진 입찰"도 항상 201로 응답하고 isValid로만 구분한다 (409로 바꾸지 않는 이유는
// BidService가 진 입찰 기록도 롤백 없이 남기도록 설계돼 있어서, HTTP 레벨에서도
// "요청 자체는 정상 접수됐다"는 의미를 유지하기 위함).
//
// @JsonProperty 없이 record 컴포넌트명을 isValid로 두면 Jackson이 boolean getter 관례상
// "is" 접두사를 벗겨내 JSON 키를 "valid"로 잘못 직렬화하므로 명시적으로 고정한다.
public record BidResponse(
        UUID bidId,
        UUID productId,
        UUID bidderId,
        Integer amount,
        LocalDateTime bidAt,
        @JsonProperty("isValid") boolean isValid,
        boolean extended
) {
    public static BidResponse from(BidResult result) {
        Bid bid = result.bid();
        return new BidResponse(
                bid.getId(),
                bid.getProduct().getId(),
                bid.getBidder().getId(),
                bid.getAmount(),
                bid.getBidAt(),
                bid.isValid(),
                result.extended());
    }
}
