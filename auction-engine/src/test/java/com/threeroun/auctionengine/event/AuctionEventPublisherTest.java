package com.threeroun.auctionengine.event;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 이슈 #142 - Redis가 죽었을 때의 발행 동작을 Redis 없이 검증한다.
// 이벤트는 DB 커밋 뒤에 나가므로, 발행이 실패해도 예외가 밖으로 새면 안 되고(요청 스레드 오염),
// 대신 지표로 남아서 "이벤트가 유실됐다"는 사실은 감지할 수 있어야 한다.
class AuctionEventPublisherTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AuctionEventPublisher publisher = new AuctionEventPublisher(redisTemplate, meterRegistry);

    private BidPlacedEvent bidPlacedEvent() {
        return new BidPlacedEvent(UUID.randomUUID(), 15000, UUID.randomUUID(), LocalDateTime.of(2026, 11, 20, 12, 0));
    }

    @Test
    void 정상이면_auction_events_채널로_payload를_발행하고_실패_지표는_늘지_않는다() {
        publisher.publish(bidPlacedEvent());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).convertAndSend(eq(AuctionEventPublisher.CHANNEL), payload.capture());
        assertThat(payload.getValue().toString()).contains("\"event\":\"bid_placed\"");
        assertThat(meterRegistry.find("auction.event.publish.failed").counter()).isNull();
    }

    @Test
    void Redis_발행이_실패해도_예외를_던지지_않고_이벤트_종류별_실패_지표를_올린다() {
        doThrow(new QueryTimeoutException("Redis command timed out"))
                .when(redisTemplate).convertAndSend(anyString(), any());

        assertThatCode(() -> publisher.publish(bidPlacedEvent())).doesNotThrowAnyException();
        assertThatCode(() -> publisher.publish(bidPlacedEvent())).doesNotThrowAnyException();

        double failed = meterRegistry.get("auction.event.publish.failed")
                .tag("event", "BidPlacedEvent").counter().count();
        assertThat(failed).isEqualTo(2.0);
    }

    @Test
    void 직렬화_실패는_Redis_장애가_아니라_코드_버그라서_삼키지_않는다() {
        // 필드가 하나도 없는 객체는 Jackson이 직렬화하지 못한다
        assertThatThrownBy(() -> publisher.publish(new Object()))
                .isInstanceOf(IllegalStateException.class);

        verify(redisTemplate, never()).convertAndSend(anyString(), any());
        assertThat(meterRegistry.find("auction.event.publish.failed").counter()).isNull();
    }
}
