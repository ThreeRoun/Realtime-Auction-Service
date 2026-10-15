package com.threeroun.auctionengine.repository;

import com.threeroun.auctionengine.domain.Bid;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BidRepository extends JpaRepository<Bid, UUID> {

    List<Bid> findByProductId(UUID productId);

    // 입찰 쿨다운 판정용: 이 유저가 이 상품에 직전에 입찰한 시각(진 입찰 포함, 승패 무관하게
    // "요청을 보낸 적 있다"는 사실 자체가 중요하므로).
    Optional<Bid> findFirstByProductIdAndBidderIdOrderByBidAtDesc(UUID productId, UUID bidderId);

    // 낙찰 처리용: 유효한 입찰을 금액 높은 순으로 - 1순위 크레딧 부족하면 2순위로 넘어가며 시도
    // (Bid 엔티티의 실제 필드명이 valid라서 isValid가 아니라 Valid로 참조해야 한다 - getter만 isValid())
    List<Bid> findByProductIdAndValidTrueOrderByAmountDesc(UUID productId);

    // 입찰 이력 조회용: 최근 입찰이 먼저 나오도록 정렬
    List<Bid> findByProductIdOrderByBidAtDesc(UUID productId);
}
