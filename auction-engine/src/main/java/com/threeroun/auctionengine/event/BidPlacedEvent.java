package com.threeroun.auctionengine.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.UUID;

// B(실시간 중계)가 이 payload를 파싱하지 않고 그대로(pass-through) 프론트에 전달하므로,
// 필드 이름(snake_case)과 event 키는 B/README에 문서화된 계약과 한 글자도 다르면 안 된다.
public record BidPlacedEvent(
        @JsonProperty("event") String event,
        @JsonProperty("product_id") UUID productId,
        @JsonProperty("current_price") int currentPrice,
        @JsonProperty("bidder_id") UUID bidderId,
        @JsonProperty("bid_at") LocalDateTime bidAt
) {
    public BidPlacedEvent(UUID productId, int currentPrice, UUID bidderId, LocalDateTime bidAt) {
        this("bid_placed", productId, currentPrice, bidderId, bidAt);
    }
}
