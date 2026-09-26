package com.threeroun.auctionengine.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

    public static final String CHANNEL = "auction_events";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public AuctionEventPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void publish(Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(CHANNEL, payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 payload 직렬화 실패: " + event, e);
        }
    }
}
