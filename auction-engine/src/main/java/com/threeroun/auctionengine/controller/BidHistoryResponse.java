package com.threeroun.auctionengine.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.threeroun.auctionengine.domain.Bid;

import java.time.LocalDateTime;
import java.util.UUID;

// productId는 URL 경로에 이미 있으므로 응답에 다시 넣지 않는다.
//
// bidderUsername은 화면에 UUID 대신 닉네임을 보여주기 위한 필드다 (이슈 #148).
// bidder는 지연 로딩(LAZY)이라 getUsername()을 읽으려면 미리 초기화돼 있어야 하므로,
// 이 메서드에는 BidRepository.findByProductIdOrderByBidAtDesc처럼 "join fetch b.bidder"로
// 조회한 Bid만 넘겨야 한다 (open-in-view=false라 세션 밖에서는 LazyInitializationException).
public record BidHistoryResponse(
        UUID bidId,
        UUID bidderId,
        String bidderUsername,
        Integer amount,
        LocalDateTime bidAt,
        @JsonProperty("isValid") boolean isValid,
        LocalDateTime cancelledAt,
        String cancelReason
) {
    // isValid는 "지금도 효력이 있는 입찰인가"(취소되지 않았는가)까지 반영한 값으로 내려준다. DB의 is_valid는
    // 입찰이 들어온 순간의 기록이라 취소돼도 true로 남지만, 화면은 isValid로 최고가와 낙찰가를 계산하므로
    // 취소된 입찰이 유효로 보이면 안 된다 (이슈 #182). 취소 여부와 사유는 cancelledAt/cancelReason으로 따로 준다.
    public static BidHistoryResponse from(Bid bid) {
        return new BidHistoryResponse(
                bid.getId(),
                bid.getBidder().getId(),
                bid.getBidder().getUsername(),
                bid.getAmount(),
                bid.getBidAt(),
                bid.isActive(),
                bid.getCancelledAt(),
                bid.getCancelReason());
    }
}
