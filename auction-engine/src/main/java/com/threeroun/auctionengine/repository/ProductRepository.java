package com.threeroun.auctionengine.repository;

import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    // 동시 입찰 처리용: 이 row를 다른 트랜잭션이 건드리지 못하게 잠그고 조회한다 (SELECT ... FOR UPDATE)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") UUID id);

    // GET /api/products/{id}: seller가 LAZY 연관관계라, 응답(sellerUsername)을 만들 시점엔
    // 이미 트랜잭션/세션이 끝나 있다(open-in-view=false). join fetch로 조회 시점에 미리
    // 채워 넣어야 세션 밖에서도 seller.getUsername()이 안전하다.
    @Query("select p from Product p join fetch p.seller where p.id = :id")
    Optional<Product> findByIdWithSeller(@Param("id") UUID id);

    // GET /api/products: 기본값(진행중 상품만) 조회용, 마감 임박순 정렬
    @Query("select p from Product p join fetch p.seller where p.status in :statuses order by p.endAt asc")
    List<Product> findByStatusInOrderByEndAtAsc(@Param("statuses") Collection<ProductStatus> statuses);

    // GET /api/products?status=XXX: 특정 상태만 조회
    @Query("select p from Product p join fetch p.seller where p.status = :status order by p.endAt asc")
    List<Product> findByStatusOrderByEndAtAsc(@Param("status") ProductStatus status);

    // GET /api/products?status=all: 전체 조회
    @Query("select p from Product p join fetch p.seller order by p.endAt asc")
    List<Product> findAllByOrderByEndAtAsc();

    // 스케줄러: 마감 시각이 지났는데 아직 안 닫힌(진행중/마감연장) 상품 후보 조회
    List<Product> findByStatusInAndEndAtLessThanEqual(Collection<ProductStatus> statuses, LocalDateTime now);

    // 스케줄러: 시작 시각이 지났는데 아직 등록대기인 상품 후보 조회
    List<Product> findByStatusAndStartAtLessThanEqual(ProductStatus status, LocalDateTime now);
}
