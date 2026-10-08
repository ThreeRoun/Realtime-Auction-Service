package com.threeroun.auctionengine.repository;

import com.threeroun.auctionengine.domain.Bid;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BidRepository extends JpaRepository<Bid, UUID> {

    List<Bid> findByProductId(UUID productId);

    // 낙찰 처리용: 유효한 입찰을 금액 높은 순으로 - 1순위 크레딧 부족하면 2순위로 넘어가며 시도
    // (Bid 엔티티의 실제 필드명이 valid라서 isValid가 아니라 Valid로 참조해야 한다 - getter만 isValid())
    List<Bid> findByProductIdAndValidTrueOrderByAmountDesc(UUID productId);

    // 입찰 이력 조회용: 최근 입찰이 먼저 나오도록 정렬
    List<Bid> findByProductIdOrderByBidAtDesc(UUID productId);
}
