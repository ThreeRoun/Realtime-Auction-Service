package com.threeroun.auctionengine.repository;

import com.threeroun.auctionengine.domain.Bid;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BidRepository extends JpaRepository<Bid, UUID> {
}
