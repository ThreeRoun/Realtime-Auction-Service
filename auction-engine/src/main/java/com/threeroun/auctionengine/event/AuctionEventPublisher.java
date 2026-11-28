package com.threeroun.auctionengine.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

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

    // 트레이스에 기록할 때 쓰는 이름 (이 코드가 만든 구간이라는 표시)
    private static final String TRACER_NAME = "com.threeroun.auctionengine.event";

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
        String eventName = event.getClass().getSimpleName();

        // Redis 발행을 하나의 구간(span)으로 기록한다.
        // Java 에이전트가 이 Redis 클라이언트의 PUBLISH를 자동으로 기록하지 않아서, 직접 감싼다.
        // 이렇게 하면 Redis 장애로 발행이 오래 걸리거나 실패했을 때 트레이스에서 그 구간이 바로 보인다.
        // (에이전트가 없으면 아무 동작도 하지 않는 no-op 구간이라 기존 동작에는 영향이 없다)
        Span span = GlobalOpenTelemetry.getTracer(TRACER_NAME)
                .spanBuilder("redis publish " + CHANNEL)
                .setSpanKind(SpanKind.PRODUCER)
                .setAttribute("messaging.system", "redis")
                .setAttribute("messaging.destination.name", CHANNEL)
                .setAttribute("auction.event", eventName)
                .startSpan();

        // makeCurrent: 이 블록 안에서는 방금 만든 구간이 "현재 구간"이 된다.
        // 그래서 payload의 traceparent와 실패 로그의 trace_id/span_id가 모두 이 발행 구간을 가리킨다.
        try (Scope scope = span.makeCurrent()) {
            String payload = toPayload(event);

            try {
                redisTemplate.convertAndSend(CHANNEL, payload);
            } catch (RuntimeException e) {
                // 이 시점은 DB 커밋이 끝난 뒤(AuctionEventListener의 AFTER_COMMIT)라서, 예외를 던져도 입찰/연장/마감을
                // 되돌릴 수 없다. 던지면 요청 스레드만 오염되므로 삼키되, 조용히 사라지지 않게 지표와 ERROR 로그를 남긴다.
                // 대가: Redis 장애 동안의 이벤트는 유실되어 다른 사용자 화면에 실시간으로 반영되지 않는다
                // (화면을 새로고침하면 DB 기준 값이 보인다). 재발행(outbox 등)은 구조 변경이라 이번 범위 밖이다.
                span.recordException(e);
                span.setStatus(StatusCode.ERROR, "Redis 발행 실패");
                Counter.builder("auction.event.publish.failed")
                        .description("Redis 발행에 실패해 중계 엔진에 전달되지 못한 이벤트 수")
                        .tag("event", eventName)
                        .register(meterRegistry)
                        .increment();
                log.error("이벤트 발행 실패 (DB에는 반영됨, 실시간 중계만 누락): event={}, cause={}", eventName, e.toString());
            }
        } catch (IllegalStateException e) {
            // 직렬화 실패(코드 버그)는 구간에도 에러로 남기고 그대로 다시 던진다
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, "이벤트 직렬화 실패");
            throw e;
        } finally {
            // 성공, 실패와 관계없이 구간을 닫아야 트레이스에 기록된다
            span.end();
        }
    }

    // 이벤트를 JSON 문자열로 만들고, 현재 트레이스 정보(traceparent)를 덧붙인다.
    // Redis Pub/Sub 메시지에는 트레이스가 자동으로 실리지 않아서, 직접 넣어줘야
    // 중계 엔진(relay)이 이 트레이스에 이어서 기록할 수 있다.
    private String toPayload(Object event) {
        try {
            ObjectNode node = objectMapper.valueToTree(event);
            String traceparent = currentTraceparent();
            if (traceparent != null) {
                node.put("traceparent", traceparent);
            }
            return objectMapper.writeValueAsString(node);
        } catch (IllegalArgumentException | JsonProcessingException e) {
            // 직렬화 실패는 Redis 장애가 아니라 코드 버그라서 삼키지 않고 그대로 드러낸다.
            throw new IllegalStateException("이벤트 payload 직렬화 실패: " + event, e);
        }
    }

    // 현재 트레이스를 W3C traceparent 문자열로 꺼낸다 (예: 00-<trace_id>-<span_id>-01).
    // Java 에이전트가 없거나 진행 중인 트레이스가 없으면 null을 돌려줘서, payload가 예전과 똑같이 나간다.
    private static String currentTraceparent() {
        Map<String, String> carrier = new HashMap<>();
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(Context.current(), carrier, (map, key, value) -> map.put(key, value));
        return carrier.get("traceparent");
    }
}
