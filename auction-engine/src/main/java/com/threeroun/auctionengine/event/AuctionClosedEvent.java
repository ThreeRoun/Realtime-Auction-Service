package com.threeroun.auctionengine.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

// 유찰(낙찰자 없음)일 때는 winnerId/finalPrice가 null인 채로 그대로 발행한다.
// B(실시간 중계)가 pass-through이므로, 프론트가 null 여부로 낙찰/유찰을 구분하면 된다.
public record AuctionClosedEvent(
        @JsonProperty("event") String event,
        @JsonProperty("product_id") UUID productId,
        @JsonProperty("winner_id") UUID winnerId,
        @JsonProperty("final_price") Integer finalPrice
) {
    public AuctionClosedEvent(UUID productId, UUID winnerId, Integer finalPrice) {
        this("auction_closed", productId, winnerId, finalPrice);
    }
}
