package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.event.AuctionCancelledEvent;
import com.threeroun.auctionengine.repository.BidRepository;
import com.threeroun.auctionengine.repository.ProductRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Service
public class AuctionCancelService {

    private static final Set<ProductStatus> CANCELLABLE_STATUSES =
            Set.of(ProductStatus.PENDING, ProductStatus.IN_PROGRESS, ProductStatus.EXTENDED);

    private final ProductRepository productRepository;
    private final BidRepository bidRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AuctionCancelService(ProductRepository productRepository, BidRepository bidRepository,
                                ApplicationEventPublisher eventPublisher) {
        this.productRepository = productRepository;
        this.bidRepository = bidRepository;
        this.eventPublisher = eventPublisher;
    }

    // 판매자가 유효 입찰이 없는 경매만 취소할 수 있다 (입찰자가 있는 경매를 판매자가 일방적으로 없애지 못하게).
    //
    // BidService.placeBid()와 AuctionClosingService.closeIfExpired()처럼 상품 row를 FOR UPDATE로 잠그고 시작한다.
    // 그래서 "유효 입찰 없음을 확인한 직후 입찰이 들어오는" 경쟁이 생기지 않는다. 입찰이 먼저 커밋되면 여기서
    // 유효 입찰을 보고 거절하고, 취소가 먼저 커밋되면 입찰 쪽이 CANCELLED 상태를 보고 AUCTION_NOT_IN_PROGRESS로 거절한다.
    // 마감 처리와 겹쳐도 같은 락 때문에 한쪽만 상태를 바꾼다.
    //
    // 유효 입찰이 없으니 이 경매에 묶인 크레딧도 없다. 크레딧 묶기 계산은 진행중 상태만 보므로
    // 혹시 묶인 금액이 있었더라도 CANCELLED가 되는 순간 풀린다.
    @Transactional
    public void cancel(UUID productId, UUID requesterId) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        if (!product.getSeller().getId().equals(requesterId)) {
            throw new AuctionCancelNotAllowedException(productId);
        }
        // 마감 시각이 지났는데 스케줄러가 아직 마감 처리를 못 한 경매도 이미 끝난 것으로 본다.
        if (!CANCELLABLE_STATUSES.contains(product.getStatus())
                || !product.getEndAt().isAfter(LocalDateTime.now())) {
            throw AuctionNotCancellableException.alreadyEnded(productId);
        }
        if (bidRepository.existsByProductIdAndValidTrue(productId)) {
            throw AuctionNotCancellableException.hasBids(productId);
        }

        product.setStatus(ProductStatus.CANCELLED);
        eventPublisher.publishEvent(new AuctionCancelledEvent(productId));
    }
}
