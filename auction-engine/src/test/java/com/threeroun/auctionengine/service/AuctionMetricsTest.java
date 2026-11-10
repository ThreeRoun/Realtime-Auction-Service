package com.threeroun.auctionengine.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// AuctionMetrics 자체만 떼어서 순수 단위 테스트로 검증한다 - 이걸 @SpringBootTest로 돌리면
// 실제 배포 환경과 똑같이 살아있는 AuctionScheduler가 백그라운드에서 이 로컬 DB에 오래전부터
// 쌓여있는 다른 테스트/수동 실행 잔여 데이터(마감 지난 상품들)를 계속 정리하면서 같은
// MeterRegistry의 카운터를 끼어들어 건드려서, "내가 방금 호출한 만큼만 늘었는지"를 정확히
// 검증할 수가 없다(실제로 그래서 한 번 실패했다). SimpleMeterRegistry로 완전히 격리하면
// 그 문제 자체가 없다.
class AuctionMetricsTest {

    @Test
    void 입찰이_들어오면_bid_placed_카운터만_증가한다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuctionMetrics metrics = new AuctionMetrics(registry);

        metrics.bidPlaced();
        metrics.bidPlaced();

        assertThat(registry.get("auction.bid.placed").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("auction.extended").counter().count()).isEqualTo(0.0);
    }

    @Test
    void 마감_연장되면_auction_extended_카운터가_증가한다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuctionMetrics metrics = new AuctionMetrics(registry);

        metrics.auctionExtended();

        assertThat(registry.get("auction.extended").counter().count()).isEqualTo(1.0);
    }

    @Test
    void 낙찰과_유찰은_result_태그로_서로_다른_카운터에_쌓인다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuctionMetrics metrics = new AuctionMetrics(registry);

        metrics.auctionClosed(true);
        metrics.auctionClosed(true);
        metrics.auctionClosed(false);

        assertThat(registry.get("auction.closed").tag("result", "sold").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("auction.closed").tag("result", "unsold").counter().count()).isEqualTo(1.0);
    }
}
