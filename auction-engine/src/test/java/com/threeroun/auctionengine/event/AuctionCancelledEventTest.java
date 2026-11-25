package com.threeroun.auctionengine.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// B(실시간 중계)가 pass-through로 전달하므로 payload 필드 이름을 고정해 둔다 (BidPlacedEventTest와 같은 이유).
class AuctionCancelledEventTest {

    @Test
    void auction_cancelled_이벤트는_snake_case_계약대로_직렬화된다() throws Exception {
        AuctionCancelledEvent event = new AuctionCancelledEvent(
                UUID.fromString("11111111-1111-1111-1111-111111111111"));

        String json = new ObjectMapper().writeValueAsString(event);

        assertThat(json).isEqualTo(
                "{\"event\":\"auction_cancelled\",\"product_id\":\"11111111-1111-1111-1111-111111111111\"}");
    }
}
