package com.threeroun.auctionengine.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.UUID;

// B가 그대로 pass-through하므로 필드명(snake_case)과 event 값은 계약에 맞춰 고정한다.
public record AuctionExtendedEvent(
        @JsonProperty("event") String event,
        @JsonProperty("product_id") UUID productId,
        @JsonProperty("new_end_at") LocalDateTime newEndAt
) {
    public AuctionExtendedEvent(UUID productId, LocalDateTime newEndAt) {
        this("auction_extended", productId, newEndAt);
    }
}
