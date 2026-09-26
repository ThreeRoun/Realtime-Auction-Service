package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.Bid;
import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.event.BidPlacedEvent;
import com.threeroun.auctionengine.repository.BidRepository;
import com.threeroun.auctionengine.repository.ProductRepository;
import com.threeroun.auctionengine.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class BidService {

    private final ProductRepository productRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public BidService(ProductRepository productRepository, BidRepository bidRepository,
                       UserRepository userRepository, ApplicationEventPublisher eventPublisher) {
        this.productRepository = productRepository;
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    // Product row에 비관적 락을 건 채로 "현재가 확인 -> Bid 기록 -> 현재가 갱신"을 한 트랜잭션에서 처리한다.
    // 동시에 여러 요청이 들어와도 이 메서드 안에서는 한 번에 하나씩만 실행되므로,
    // 뒤에 처리되는 요청은 항상 방금 갱신된 current_price를 기준으로 재검증된다.
    @Transactional
    public Bid placeBid(UUID productId, UUID bidderId, int amount) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        if (product.getStatus() != ProductStatus.IN_PROGRESS && product.getStatus() != ProductStatus.EXTENDED) {
            throw new AuctionNotInProgressException(productId, product.getStatus());
        }

        Bid bid = new Bid(product, userRepository.getReferenceById(bidderId), amount);

        int minNextBid = product.getCurrentPrice() + product.getBidUnit();
        if (amount >= minNextBid) {
            bid.markValid();
            product.setCurrentPrice(amount);
        }
        // amount가 minNextBid 미만이면 bid는 is_valid=false인 채로 그대로 저장된다.
        // 여기서 예외를 던지면 @Transactional이 트랜잭션 전체를 롤백시켜서, 남겨야 할
        // "진 입찰" 기록 자체가 사라져 버리기 때문에 예외 대신 반환값(isValid)으로 성패를 알린다.
        Bid savedBid = bidRepository.save(bid);

        // 진 입찰(is_valid=false)은 프론트에 실시간으로 알릴 대상이 아니므로 발행하지 않는다.
        // 실제 Redis 발행은 AuctionEventListener가 이 트랜잭션 commit 이후에 수행한다.
        if (savedBid.isValid()) {
            eventPublisher.publishEvent(new BidPlacedEvent(productId, amount, bidderId, savedBid.getBidAt()));
        }
        return savedBid;
    }
}
