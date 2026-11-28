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

    // 입찰 취소(이슈 #182). is_valid는 "그 입찰이 들어온 순간 최고가를 갱신했는가"라는 기록이라 그대로 두고,
    // 취소 여부는 따로 남긴다. 지금 효력이 있는 입찰은 isActive()다.
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 30)
    private String cancelReason;

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

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public boolean isCancelled() {
        return cancelledAt != null;
    }

    // 지금도 효력이 있는 입찰: 최고가를 갱신했고(valid) 취소되지 않았다.
    // 크레딧 묶기, 낙찰 후보, 최고 입찰자 판정은 모두 이 기준을 쓴다.
    public boolean isActive() {
        return valid && cancelledAt == null;
    }

    public void cancel(String reason, LocalDateTime at) {
        this.cancelledAt = at;
        this.cancelReason = reason;
    }
}
