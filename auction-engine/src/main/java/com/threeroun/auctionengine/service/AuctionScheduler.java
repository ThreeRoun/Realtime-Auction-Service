package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.repository.ProductRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class AuctionScheduler {

    private static final List<ProductStatus> ACTIVE_STATUSES = List.of(ProductStatus.IN_PROGRESS, ProductStatus.EXTENDED);

    private final ProductRepository productRepository;
    private final AuctionClosingService closingService;

    public AuctionScheduler(ProductRepository productRepository, AuctionClosingService closingService) {
        this.productRepository = productRepository;
        this.closingService = closingService;
    }

    // 5초마다 "마감 지난 상품 있나" 훑어본다. 실제 마감 처리(락+낙찰 판정)는 상품 하나당
    // closeIfExpired()의 별도 트랜잭션에서 수행 - 여기서는 후보 ID만 가볍게 조회한다.
    @Scheduled(fixedDelay = 5000)
    public void closeExpiredAuctions() {
        List<UUID> expiredIds = productRepository
                .findByStatusInAndEndAtLessThanEqual(ACTIVE_STATUSES, LocalDateTime.now())
                .stream().map(Product::getId).toList();
        for (UUID productId : expiredIds) {
            closingService.closeIfExpired(productId);
        }
    }

    // 5초마다 "시작 시각 지난 등록대기 상품 있나"도 같이 훑어본다.
    @Scheduled(fixedDelay = 5000)
    public void activatePendingAuctions() {
        closingService.activatePendingAuctions();
    }
}
