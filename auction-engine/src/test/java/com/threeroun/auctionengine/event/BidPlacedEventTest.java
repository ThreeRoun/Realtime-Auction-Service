package com.threeroun.auctionengine.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// B(실시간 중계)가 이 JSON을 파싱하지 않고 그대로 프론트에 전달하므로,
// 필드 이름이 계약(README "WebSocket 이벤트" 섹션)과 한 글자라도 다르면 프론트가 못 알아본다.
// Redis/DB 없이 payload 형태만 고정하기 위한 순수 직렬화 테스트.
class BidPlacedEventTest {

    @Test
    void bid_placed_이벤트는_snake_case_계약대로_직렬화된다() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        BidPlacedEvent event = new BidPlacedEvent(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                15000,
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                LocalDateTime.of(2026, 9, 23, 13, 40, 0));

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"event\":\"bid_placed\"");
        assertThat(json).contains("\"product_id\":\"11111111-1111-1111-1111-111111111111\"");
        assertThat(json).contains("\"current_price\":15000");
        assertThat(json).contains("\"bidder_id\":\"22222222-2222-2222-2222-222222222222\"");
        assertThat(json).contains("\"bid_at\":\"2026-09-23T13:40:00\"");
    }
}
