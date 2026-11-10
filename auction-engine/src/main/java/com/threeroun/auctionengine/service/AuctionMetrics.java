package com.threeroun.auctionengine.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

// Grafana 대시보드(k8s/monitoring/config/dashboards/auction-engine.json)가 보는 건 지금까지
// HTTP 요청 수/응답시간, JVM, HikariCP 같은 범용 지표뿐이라, "입찰이 실제로 몇 건 들어왔는지" 같은
// 경매엔진 고유의 비즈니스 흐름은 알 수 없었다. 여기서 그 카운터만 따로 모아 둔다.
@Component
public class AuctionMetrics {

    private final Counter bidPlacedCounter;
    private final Counter auctionExtendedCounter;
    private final Counter auctionSoldCounter;
    private final Counter auctionUnsoldCounter;

    public AuctionMetrics(MeterRegistry registry) {
        this.bidPlacedCounter = Counter.builder("auction.bid.placed")
                .description("최고가를 갱신한 유효한 입찰 수")
                .register(registry);
        this.auctionExtendedCounter = Counter.builder("auction.extended")
                .description("안티 스나이핑으로 마감이 연장된 횟수")
                .register(registry);
        this.auctionSoldCounter = Counter.builder("auction.closed")
                .description("마감된 경매 수")
                .tag("result", "sold")
                .register(registry);
        this.auctionUnsoldCounter = Counter.builder("auction.closed")
                .description("마감된 경매 수")
                .tag("result", "unsold")
                .register(registry);
    }

    public void bidPlaced() {
        bidPlacedCounter.increment();
    }

    public void auctionExtended() {
        auctionExtendedCounter.increment();
    }

    public void auctionClosed(boolean sold) {
        (sold ? auctionSoldCounter : auctionUnsoldCounter).increment();
    }
}
