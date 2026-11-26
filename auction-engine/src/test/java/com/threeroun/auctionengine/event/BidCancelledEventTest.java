package com.threeroun.auctionengine.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// B(실시간 중계)가 pass-through로 전달하므로 payload 필드 이름을 고정해 둔다 (BidPlacedEventTest와 같은 이유).
class BidCancelledEventTest {

    @Test
    void bid_cancelled_이벤트는_snake_case_계약대로_직렬화된다() throws Exception {
        BidCancelledEvent event = new BidCancelledEvent(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                30_000,
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                List.of(UUID.fromString("33333333-3333-3333-3333-333333333333")));

        String json = new ObjectMapper().writeValueAsString(event);

        assertThat(json).isEqualTo("{\"event\":\"bid_cancelled\","
                + "\"product_id\":\"11111111-1111-1111-1111-111111111111\","
                + "\"current_price\":30000,"
                + "\"bidder_id\":\"22222222-2222-2222-2222-222222222222\","
                + "\"cancelled_bid_ids\":[\"33333333-3333-3333-3333-333333333333\"]}");
    }

    @Test
    void 되돌릴_입찰이_없으면_bidder_id가_null이다() throws Exception {
        BidCancelledEvent event = new BidCancelledEvent(
                UUID.fromString("11111111-1111-1111-1111-111111111111"), 10_000, null, List.of());

        String json = new ObjectMapper().writeValueAsString(event);

        assertThat(json).contains("\"bidder_id\":null").contains("\"cancelled_bid_ids\":[]");
    }
}
