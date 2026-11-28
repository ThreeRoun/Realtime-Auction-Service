package com.threeroun.auctionengine.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

// 입찰이 취소됐을 때 발행한다 (이슈 #182). B(실시간 중계)가 pass-through이므로 필드 이름은
// realtime-relay README의 이벤트 계약과 같아야 한다.
//
// current_price/bidder_id는 취소를 반영한 "지금" 값이다. 최고 입찰이 취소돼 직전 입찰로 되돌아갔으면
// 그 입찰의 금액과 입찰자이고, 되돌릴 입찰이 없으면 시작가와 null이다.
// cancelled_bid_ids에는 본인이 취소한 입찰뿐 아니라, 되돌리는 과정에서 크레딧 부족으로 시스템이
// 건너뛰며 취소한 입찰도 들어간다.
public record BidCancelledEvent(
        @JsonProperty("event") String event,
        @JsonProperty("product_id") UUID productId,
        @JsonProperty("current_price") int currentPrice,
        @JsonProperty("bidder_id") UUID bidderId,
        @JsonProperty("cancelled_bid_ids") List<UUID> cancelledBidIds
) {
    public BidCancelledEvent(UUID productId, int currentPrice, UUID bidderId, List<UUID> cancelledBidIds) {
        this("bid_cancelled", productId, currentPrice, bidderId, cancelledBidIds);
    }
}
