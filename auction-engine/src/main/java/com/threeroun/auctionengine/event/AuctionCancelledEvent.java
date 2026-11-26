package com.threeroun.auctionengine.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

// 판매자가 경매를 취소했을 때 발행한다. B(실시간 중계)가 pass-through이므로 필드 이름은
// realtime-relay README의 이벤트 계약과 같아야 한다.
public record AuctionCancelledEvent(
        @JsonProperty("event") String event,
        @JsonProperty("product_id") UUID productId
) {
    public AuctionCancelledEvent(UUID productId) {
        this("auction_cancelled", productId);
    }
}
