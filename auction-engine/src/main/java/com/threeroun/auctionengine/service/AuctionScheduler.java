package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.repository.ProductRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
    //
    // @SchedulerLock: k8s 롤링 업데이트 중 Pod가 잠깐 겹쳐도(이슈 #100) 한 인스턴스만 실행되게
    // 막는다. closeIfExpired 자체는 비관적 락이 있어 데이터는 이미 안전했지만, 이 락이 없으면
    // 두 Pod가 같은 후보를 중복 조회/대기하는 낭비가 생긴다.
    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "closeExpiredAuctions", lockAtLeastFor = "PT1S", lockAtMostFor = "PT30S")
    public void closeExpiredAuctions() {
        List<UUID> expiredIds = productRepository
                .findByStatusInAndEndAtLessThanEqual(ACTIVE_STATUSES, LocalDateTime.now())
                .stream().map(Product::getId).toList();
        for (UUID productId : expiredIds) {
            closingService.closeIfExpired(productId);
        }
    }

    // 5초마다 "시작 시각 지난 등록대기 상품 있나"도 같이 훑어본다.
    //
    // @SchedulerLock: 이쪽은 원래 락이 전혀 없었다 - 두 Pod가 동시에 실행하면 엔티티를 읽어서
    // 저장하는 방식(Hibernate 기본은 UPDATE 시 모든 컬럼을 씀) 특성상, 그 사이 커밋된 입찰
    // 변경(current_price, end_at)을 예전 값으로 덮어쓸 위험이 있었다(이슈 #100). 지금은
    // 벌크 UPDATE로도 바꿔서(ProductRepository 참고) 이중으로 안전하다.
    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "activatePendingAuctions", lockAtLeastFor = "PT1S", lockAtMostFor = "PT30S")
    public void activatePendingAuctions() {
        closingService.activatePendingAuctions();
    }
}
