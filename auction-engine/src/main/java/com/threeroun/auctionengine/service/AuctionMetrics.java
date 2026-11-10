package com.threeroun.auctionengine.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

// Grafana 대시보드(k8s/monitoring/config/dashboards/auction-engine.json)가 보는 건 지금까지
// HTTP 요청 수/응답시간, JVM, HikariCP 같은 범용 지표뿐이라, "입찰이 실제로 몇 건 들어왔는지" 같은
// 경매엔진 고유의 비즈니스 흐름은 알 수 없었다. 여기서 그 카운터만 따로 모아 둔다.
@Component
public class AuctionMetrics {

    // 성공한 입찰(bidPlaced)만 세면 "왜 입찰이 거절되는지"가 안 보인다 (PR #126 리뷰 피드백).
    // 사유는 BidService.placeBid가 입찰을 거절하는 경로 그대로 고정된 집합이라 enum으로 못박아서,
    // 호출하는 쪽에서 오타로 새 사유 문자열을 만들어버리는 걸 컴파일 타임에 막는다.
    public enum BidRejectionReason {
        PRODUCT_NOT_FOUND, AUCTION_NOT_IN_PROGRESS, SELF_BID_NOT_ALLOWED, COOLDOWN_ACTIVE, AMOUNT_TOO_LOW
    }

    private final Counter bidPlacedCounter;
    private final Counter auctionExtendedCounter;
    private final Counter auctionSoldCounter;
    private final Counter auctionUnsoldCounter;
    private final Map<BidRejectionReason, Counter> bidRejectedCounters = new EnumMap<>(BidRejectionReason.class);

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
        for (BidRejectionReason reason : BidRejectionReason.values()) {
            bidRejectedCounters.put(reason, Counter.builder("auction.bid.rejected")
                    .description("입찰이 거절된 횟수 (사유별)")
                    .tag("reason", reason.name().toLowerCase())
                    .register(registry));
        }
    }

    public void bidPlaced() {
        bidPlacedCounter.increment();
    }

    public void bidRejected(BidRejectionReason reason) {
        bidRejectedCounters.get(reason).increment();
    }

    public void auctionExtended() {
        auctionExtendedCounter.increment();
    }

    public void auctionClosed(boolean sold) {
        (sold ? auctionSoldCounter : auctionUnsoldCounter).increment();
    }
}
