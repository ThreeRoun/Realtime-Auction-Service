package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.Bid;
import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.event.AuctionExtendedEvent;
import com.threeroun.auctionengine.event.BidPlacedEvent;
import com.threeroun.auctionengine.repository.BidRepository;
import com.threeroun.auctionengine.repository.ProductRepository;
import com.threeroun.auctionengine.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class BidService {

    // 안티 스나이핑: 마감까지 이 시간 이하로 남은 상태에서 유효한 입찰이 들어오면 마감을 연장한다.
    // 숫자 자체는 데모/운영 편의상 정한 값이라 필요하면 쉽게 바꿀 수 있게 상수로 분리해둔다.
    static final Duration ANTI_SNIPING_WINDOW = Duration.ofSeconds(30);
    static final Duration EXTENSION_DURATION = Duration.ofMinutes(1);
    // 연장이 무한정 반복되는 걸 막는 상한. 이 횟수를 넘으면 마감 임박 시 유효한 입찰이 들어와도
    // 가격만 갱신되고 더 이상 연장되지 않는다.
    static final int MAX_EXTENSIONS = 5;
    // 같은 유저가 같은 상품에 너무 짧은 간격으로 연타 입찰(매크로 등)하는 걸 막는 쿨다운.
    static final Duration BID_COOLDOWN = Duration.ofSeconds(10);

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

    // Product row에 비관적 락을 건 채로 "현재가 확인 -> Bid 기록 -> 현재가 갱신 -> (필요시) 마감 연장"을
    // 한 트랜잭션에서 처리한다. 동시에 여러 요청이 들어와도 이 메서드 안에서는 한 번에 하나씩만
    // 실행되므로, 뒤에 처리되는 요청은 항상 방금 갱신된 current_price/end_at을 기준으로 재검증된다.
    @Transactional
    public BidResult placeBid(UUID productId, UUID bidderId, int amount) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        if (product.getStatus() != ProductStatus.IN_PROGRESS && product.getStatus() != ProductStatus.EXTENDED) {
            throw new AuctionNotInProgressException(productId, product.getStatus());
        }

        // 판매자가 자기 상품에 입찰해서 가격을 띄우는 자전 거래(shill bidding) 방지
        if (product.getSeller().getId().equals(bidderId)) {
            throw new SelfBidNotAllowedException(productId);
        }

        // 쿨다운: 승패(valid) 상관없이 "이 유저가 이 상품에 요청을 보낸 시각" 자체를 본다 -
        // 매크로로 연타하면 지는 입찰도 반복 생성되므로, 이긴 입찰만 보면 쿨다운을 우회할 수 있다.
        Optional<Bid> lastBid = bidRepository.findFirstByProductIdAndBidderIdOrderByBidAtDesc(productId, bidderId);
        LocalDateTime now = LocalDateTime.now();
        if (lastBid.isPresent() && Duration.between(lastBid.get().getBidAt(), now).compareTo(BID_COOLDOWN) < 0) {
            throw new BidCooldownException(productId);
        }

        Bid bid = new Bid(product, userRepository.getReferenceById(bidderId), amount);

        int minNextBid = product.getCurrentPrice() + product.getBidUnit();
        boolean extended = false;
        if (amount >= minNextBid) {
            bid.markValid();
            product.setCurrentPrice(amount);

            // 진 입찰(가격을 못 넘긴 입찰)은 애초에 마감 연장 대상이 아니다 - 가격이 안 바뀌었으니
            // 연장해줄 이유가 없음. 이긴 입찰만 안티 스나이핑 판정 대상.
            // 연장 횟수가 상한에 도달했으면 마감 임박이어도 더 연장하지 않는다(가격만 갱신됨).
            if (product.getExtensionCount() < MAX_EXTENSIONS
                    && !Duration.between(now, product.getEndAt()).minus(ANTI_SNIPING_WINDOW).isPositive()) {
                product.setEndAt(product.getEndAt().plus(EXTENSION_DURATION));
                product.setStatus(ProductStatus.EXTENDED);
                product.incrementExtensionCount();
                extended = true;
            }
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
        if (extended) {
            eventPublisher.publishEvent(new AuctionExtendedEvent(productId, product.getEndAt()));
        }
        return new BidResult(savedBid, extended);
    }
}
