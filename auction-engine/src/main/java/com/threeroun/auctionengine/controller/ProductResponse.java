package com.threeroun.auctionengine.controller;

import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;

import java.time.LocalDateTime;
import java.util.UUID;

// seller/winner는 지연 로딩(LAZY) 연관관계. 프록시의 식별자(getId())만 읽는 건 세션이 이미
// 닫혀 있어도 안전하지만(Hibernate가 프록시 생성 시점에 FK 값을 이미 들고 있음),
// getUsername()처럼 실제 컬럼을 읽으려면 그 프록시가 미리 초기화돼 있어야 한다(LazyInitializationException).
// 그래서 sellerUsername을 쓰는 호출부는 ProductRepository에서 "join fetch p.seller"로
// seller까지 함께 조회한 Product만 이 메서드에 넘겨야 한다.

public record ProductResponse(
        UUID id,
        String title,
        String description,
        Integer startingPrice,
        Integer currentPrice,
        Integer bidUnit,
        UUID sellerId,
        String sellerUsername,
        ProductStatus status,
        LocalDateTime startAt,
        LocalDateTime endAt,
        UUID winnerId,
        LocalDateTime createdAt,
        String imageUrl
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
                product.getSeller().getUsername(),
                product.getStatus(),
                product.getStartAt(),
                product.getEndAt(),
                product.getWinner() != null ? product.getWinner().getId() : null,
                product.getCreatedAt(),
                product.getImageUrl());
    }
}
