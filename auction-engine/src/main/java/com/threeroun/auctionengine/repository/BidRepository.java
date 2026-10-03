package com.threeroun.auctionengine.repository;

import com.threeroun.auctionengine.domain.Bid;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BidRepository extends JpaRepository<Bid, UUID> {

    List<Bid> findByProductId(UUID productId);

    // 입찰 이력 조회용: 최근 입찰이 먼저 나오도록 정렬
    List<Bid> findByProductIdOrderByBidAtDesc(UUID productId);
}
