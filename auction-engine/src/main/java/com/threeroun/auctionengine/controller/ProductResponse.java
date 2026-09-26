package com.threeroun.auctionengine.controller;

import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;

import java.time.LocalDateTime;
import java.util.UUID;

// seller/winner는 지연 로딩(LAZY) 연관관계지만, 프록시의 식별자(getId())만 읽는 건
// 세션이 이미 닫혀 있어도 안전하다 (Hibernate가 프록시 생성 시점에 FK 값을 이미 들고 있음).
// 그래서 이 DTO는 세션/트랜잭션 종료 이후(open-in-view=false)에 만들어도 문제없다.
public record ProductResponse(
        UUID id,
        String title,
        String description,
        Integer startingPrice,
        Integer currentPrice,
        Integer bidUnit,
        UUID sellerId,
        ProductStatus status,
        LocalDateTime startAt,
        LocalDateTime endAt,
        UUID winnerId,
        LocalDateTime createdAt
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getTitle(),
                product.getDescription(),
                product.getStartingPrice(),
                product.getCurrentPrice(),
                product.getBidUnit(),
                product.getSeller().getId(),
                product.getStatus(),
                product.getStartAt(),
                product.getEndAt(),
                product.getWinner() != null ? product.getWinner().getId() : null,
                product.getCreatedAt());
    }
}
