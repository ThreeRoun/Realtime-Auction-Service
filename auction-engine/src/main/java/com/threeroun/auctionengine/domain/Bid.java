package com.threeroun.auctionengine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

// 입찰 로그 테이블: 입찰마다 새 행을 추가하고 절대 덮어쓰지 않는다.
// 동시 입찰 중 진 쪽도 is_valid=false로 남겨서 동시성 처리 증빙 자료로 쓴다.
@Entity
@Table(name = "bids")
public class Bid {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bidder_id", nullable = false)
    private User bidder;

    @Column(nullable = false)
    private Integer amount;

    @Column(name = "bid_at", nullable = false)
    private LocalDateTime bidAt = LocalDateTime.now();

    @Column(name = "is_valid", nullable = false)
    private boolean valid = false;

    protected Bid() {
    }

    public Bid(Product product, User bidder, Integer amount) {
        this.product = product;
        this.bidder = bidder;
        this.amount = amount;
    }

    public UUID getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public User getBidder() {
        return bidder;
    }

    public Integer getAmount() {
        return amount;
    }

    public LocalDateTime getBidAt() {
        return bidAt;
    }

    public boolean isValid() {
        return valid;
    }

    public void markValid() {
        this.valid = true;
    }
}
