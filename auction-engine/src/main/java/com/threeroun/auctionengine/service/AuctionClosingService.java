package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.Bid;
import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.event.AuctionClosedEvent;
import com.threeroun.auctionengine.repository.BidRepository;
import com.threeroun.auctionengine.repository.ProductRepository;
import com.threeroun.auctionengine.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AuctionClosingService {

    private final ProductRepository productRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuctionMetrics metrics;

    public AuctionClosingService(ProductRepository productRepository, BidRepository bidRepository,
                                  UserRepository userRepository, ApplicationEventPublisher eventPublisher,
                                  AuctionMetrics metrics) {
        this.productRepository = productRepository;
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
    }

    // 상품 하나를 상품 ID로 받아 독립된 트랜잭션에서 처리한다 (스케줄러가 여러 상품을 순회할 때
    // 하나가 실패해도 나머지 상품 처리에 영향이 없도록 상품 단위로 트랜잭션을 쪼갠다).
    //
    // BidService.placeBid()와 똑같이 findByIdForUpdate로 비관적 락을 걸고 시작한다 - 그래야
    // "마감 처리 중인데 그 사이 마감 임박 입찰이 들어와서 end_at이 늘어나는" 경쟁 상태를 막을 수
    // 있다. 같은 Product row를 두고 두 트랜잭션(이 메서드 vs BidService.placeBid)이 경합하면,
    // 락 때문에 뒤에 들어온 쪽이 기다렸다가 앞쪽이 커밋한 "최신" 상태를 보고 다시 판단하게 된다.
    @Transactional
    public void closeIfExpired(UUID productId) {
        Product product = productRepository.findByIdForUpdate(productId).orElse(null);
        if (product == null) {
            return;
        }
        if (product.getStatus() != ProductStatus.IN_PROGRESS && product.getStatus() != ProductStatus.EXTENDED) {
            return; // 이미 처리된 상품이거나 애초에 대상이 아님
        }
        if (product.getEndAt().isAfter(LocalDateTime.now())) {
            return; // 락 대기하는 사이 마감 연장이 먼저 커밋돼서 더 이상 만료 상태가 아님
        }

        UUID winnerId = null;
        Integer finalPrice = null;

        // 금액 높은 순으로 유효한 입찰을 하나씩 시도한다. 1순위가 크레딧 부족이면 2순위로 승계
        // (크레딧은 낙찰 확정 시점에야 비로소 확인/차감하는 정책이라, 입찰 시점엔 아무도 크레딧
        // 부족 여부를 몰랐을 수 있음 - 그래서 여기서 순서대로 걸러내야 한다).
        //
        // 입찰자 User row도 findByIdForUpdate로 잠근다. 같은 유저가 동시에 두 경매에서 낙찰되는
        // 경우(서로 다른 상품이라 Product 락만으로는 못 막는다) 크레딧 차감이 두 트랜잭션에서
        // 동시에 같은 credit 값을 읽어 lost update가 나는 것을 막기 위함이다.
        //
        // 낙찰되면 같은 트랜잭션에서 판매자에게 낙찰가를 지급한다(수수료 없음). 차감과 지급이 한
        // 트랜잭션이라 "돈만 사라지거나 돈만 생기는" 일이 없다. 판매자 row도 잠가야 하는데, 낙찰자와
        // 판매자를 아무 순서로나 잠그면 두 마감 처리가 서로의 판매자/낙찰자를 반대 순서로 잠가
        // 데드락이 날 수 있다(A의 낙찰자=B의 판매자, A의 판매자=B의 낙찰자). 그래서 항상 id가 작은
        // 쪽부터 잠근다.
        UUID sellerId = product.getSeller().getId();
        List<Bid> candidates = bidRepository.findByProductIdAndValidTrueOrderByAmountDesc(productId);
        for (Bid bid : candidates) {
            UUID bidderId = bid.getBidder().getId();
            User bidder;
            User seller;
            if (bidderId.compareTo(sellerId) < 0) {
                bidder = lockUser(bidderId);
                seller = lockUser(sellerId);
            } else {
                seller = lockUser(sellerId);
                bidder = lockUser(bidderId);
            }
            if (bidder.getCredit() >= bid.getAmount()) {
                bidder.deductCredit(bid.getAmount());
                seller.addCredit(bid.getAmount());
                winnerId = bidder.getId();
                finalPrice = bid.getAmount();
                product.setWinner(bidder);
                break;
            }
        }

        product.setStatus(winnerId != null ? ProductStatus.SOLD : ProductStatus.UNSOLD);
        eventPublisher.publishEvent(new AuctionClosedEvent(productId, winnerId, finalPrice));
        metrics.auctionClosed(winnerId != null);
    }

    private User lockUser(UUID userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다: " + userId));
    }

    // PENDING -> IN_PROGRESS 전이. 엔티티를 읽어서 바꾸는 대신 조건부 UPDATE 쿼리 하나로 처리한다
    // (ProductRepository.activatePendingAuctions 주석 참고) - k8s 롤링 업데이트 중 Pod가
    // 잠깐 겹쳐도 안전하다.
    @Transactional
    public void activatePendingAuctions() {
        productRepository.activatePendingAuctions(LocalDateTime.now());
    }
}
