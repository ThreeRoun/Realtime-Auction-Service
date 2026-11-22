package com.threeroun.auctionengine.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// B(실시간 중계)는 이벤트 종류별로 채널을 나누지 않고 이 채널 하나만 구독한다.
// 그래서 이벤트 종류 구분은 오직 payload 안의 "event" 필드로만 이루어진다.
//
// ObjectMapper는 스프링 빈으로 주입받지 않고 직접 생성한다. 이 프로젝트의 webmvc 스타터가
// Jackson 자동설정(ObjectMapper 빈)까지는 등록하지 않아서, 주입에 의존하면
// NoSuchBeanDefinitionException이 난다 (다른 곳에서 웹 요청/응답용 ObjectMapper 빈이 생기더라도,
// 이 발행 로직은 그것과 무관하게 항상 같은 직렬화 결과를 내야 하므로 별도로 두는 편이 안전하다).
@Component
public class AuctionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuctionEventPublisher.class);

    public static final String CHANNEL = "auction_events";

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public AuctionEventPublisher(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    public void publish(Object event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // 직렬화 실패는 Redis 장애가 아니라 코드 버그라서 삼키지 않고 그대로 드러낸다.
            throw new IllegalStateException("이벤트 payload 직렬화 실패: " + event, e);
        }

        try {
            redisTemplate.convertAndSend(CHANNEL, payload);
        } catch (RuntimeException e) {
            // 이 시점은 DB 커밋이 끝난 뒤(AuctionEventListener의 AFTER_COMMIT)라서, 예외를 던져도 입찰/연장/마감을
            // 되돌릴 수 없다. 던지면 요청 스레드만 오염되므로 삼키되, 조용히 사라지지 않게 지표와 ERROR 로그를 남긴다.
            // 대가: Redis 장애 동안의 이벤트는 유실되어 다른 사용자 화면에 실시간으로 반영되지 않는다
            // (화면을 새로고침하면 DB 기준 값이 보인다). 재발행(outbox 등)은 구조 변경이라 이번 범위 밖이다.
            String eventName = event.getClass().getSimpleName();
            Counter.builder("auction.event.publish.failed")
                    .description("Redis 발행에 실패해 중계 엔진에 전달되지 못한 이벤트 수")
                    .tag("event", eventName)
                    .register(meterRegistry)
                    .increment();
            log.error("이벤트 발행 실패 (DB에는 반영됨, 실시간 중계만 누락): event={}, cause={}", eventName, e.toString());
        }
    }
}
