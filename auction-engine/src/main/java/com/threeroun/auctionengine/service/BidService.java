package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.Bid;
import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.domain.User;
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
    // 가격만 갱신되고 더 이상 연장되지 않는다. (8차 회의에서 시연 시간을 예측할 수 있게 1회로 줄임)
    static final int MAX_EXTENSIONS = 1;
    // 같은 유저가 같은 상품에 너무 짧은 간격으로 연타 입찰(매크로 등)하는 걸 막는 쿨다운.
    static final Duration BID_COOLDOWN = Duration.ofSeconds(10);

    private final ProductRepository productRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuctionMetrics metrics;
    private final CreditService creditService;

    public BidService(ProductRepository productRepository, BidRepository bidRepository,
                       UserRepository userRepository, ApplicationEventPublisher eventPublisher,
                       AuctionMetrics metrics, CreditService creditService) {
        this.creditService = creditService;
        this.productRepository = productRepository;
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
    }

    // Product row에 비관적 락을 건 채로 "현재가 확인 -> Bid 기록 -> 현재가 갱신 -> (필요시) 마감 연장"을
    // 한 트랜잭션에서 처리한다. 동시에 여러 요청이 들어와도 이 메서드 안에서는 한 번에 하나씩만
    // 실행되므로, 뒤에 처리되는 요청은 항상 방금 갱신된 current_price/end_at을 기준으로 재검증된다.
    @Transactional
    public BidResult placeBid(UUID productId, UUID bidderId, int amount) {
        Product product = productRepository.findByIdForUpdate(productId).orElse(null);
        if (product == null) {
            metrics.bidRejected(AuctionMetrics.BidRejectionReason.PRODUCT_NOT_FOUND);
            throw new ProductNotFoundException(productId);
        }

        if (product.getStatus() != ProductStatus.IN_PROGRESS && product.getStatus() != ProductStatus.EXTENDED) {
            metrics.bidRejected(AuctionMetrics.BidRejectionReason.AUCTION_NOT_IN_PROGRESS);
            throw new AuctionNotInProgressException(productId, product.getStatus());
        }

        // 마감 시각이 지났으면 상태가 아직 진행중이어도 입찰을 받지 않는다 (이슈 #152).
        // 마감 처리(상태 변경, 낙찰, 크레딧 차감)는 스케줄러가 5초 간격으로 하므로 상태만 보면 "마감 시각 ~ 다음
        // 스케줄러 실행(밀리면 더 길게)" 사이에 들어온 입찰이 받아들여져 가격이 오르고, 아래 안티 스나이핑
        // 판정에서는 마감 시각이 이미 지났는데도 "마감 임박"으로 보고 마감을 연장해 버릴 수 있다.
        // 상품 row를 잠근 뒤에 판정하므로 같은 상품의 동시 요청들이 같은 기준으로 판정된다.
        // 비교 기준은 스케줄러(endAt <= now이면 마감)와 같게 "마감 시각과 같거나 늦으면 거절"이다.
        LocalDateTime now = LocalDateTime.now();
        if (!now.isBefore(product.getEndAt())) {
            metrics.bidRejected(AuctionMetrics.BidRejectionReason.AUCTION_NOT_IN_PROGRESS);
            throw new AuctionNotInProgressException(productId, product.getEndAt());
        }

        // 판매자가 자기 상품에 입찰해서 가격을 띄우는 자전 거래(shill bidding) 방지
        if (product.getSeller().getId().equals(bidderId)) {
            metrics.bidRejected(AuctionMetrics.BidRejectionReason.SELF_BID_NOT_ALLOWED);
            throw new SelfBidNotAllowedException(productId);
        }

        // 쿨다운: 승패(valid) 상관없이 "이 유저가 이 상품에 요청을 보낸 시각" 자체를 본다 -
        // 매크로로 연타하면 지는 입찰도 반복 생성되므로, 이긴 입찰만 보면 쿨다운을 우회할 수 있다.
        Optional<Bid> lastBid = bidRepository.findFirstByProductIdAndBidderIdOrderByBidAtDesc(productId, bidderId);
        if (lastBid.isPresent() && Duration.between(lastBid.get().getBidAt(), now).compareTo(BID_COOLDOWN) < 0) {
            metrics.bidRejected(AuctionMetrics.BidRejectionReason.COOLDOWN_ACTIVE);
            throw new BidCooldownException(productId);
        }

        Bid bid = new Bid(product, userRepository.getReferenceById(bidderId), amount);

        int minNextBid = product.getCurrentPrice() + product.getBidUnit();
        boolean extended = false;
        String reason = null;
        if (amount < minNextBid) {
            // amount가 minNextBid 미만이면 bid는 is_valid=false인 채로 그대로 저장된다.
            // 여기서 예외를 던지면 @Transactional이 트랜잭션 전체를 롤백시켜서, 남겨야 할
            // "진 입찰" 기록 자체가 사라져 버리기 때문에 예외 대신 반환값(isValid)으로 성패를 알린다.
            metrics.bidRejected(AuctionMetrics.BidRejectionReason.AMOUNT_TOO_LOW);
            // 사유를 응답에 실어 보낸다 - 프론트가 "왜 안 됐는지"를 사용자에게 알려줄 수 있게 (이슈 #148)
            reason = BidResult.AMOUNT_TOO_LOW;
        } else if (!hasEnoughCredit(bidderId, productId, amount)) {
            // 보유 크레딧을 넘는 입찰(이슈 #163). 금액이 낮은 경우와 마찬가지로 예외 대신 진 입찰로 기록한다.
            metrics.bidRejected(AuctionMetrics.BidRejectionReason.INSUFFICIENT_CREDIT);
            reason = BidResult.INSUFFICIENT_CREDIT;
        } else {
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
        Bid savedBid = bidRepository.save(bid);

        // 진 입찰(is_valid=false)은 프론트에 실시간으로 알릴 대상이 아니므로 발행하지 않는다.
        // 실제 Redis 발행은 AuctionEventListener가 이 트랜잭션 commit 이후에 수행한다.
        if (savedBid.isValid()) {
            eventPublisher.publishEvent(new BidPlacedEvent(productId, amount, bidderId, savedBid.getBidAt()));
            metrics.bidPlaced();
        }
        if (extended) {
            eventPublisher.publishEvent(new AuctionExtendedEvent(productId, product.getEndAt()));
            metrics.auctionExtended();
        }
        // 처리 직후의 현재가 기준 "다음 입찰의 최소 금액". 이긴 입찰이면 방금 올라간 가격 + 단위,
        // 진 입찰이면 가격이 그대로라 처음 계산한 minNextBid와 같다.
        int nextMinBid = product.getCurrentPrice() + product.getBidUnit();
        return new BidResult(savedBid, extended, reason, nextMinBid);
    }

    // 크레딧 확인 (이슈 #163). 입찰을 받아 주면 그 금액만큼 내 크레딧이 이 경매에 묶이므로,
    // "금액 <= 보유 크레딧 - 다른 진행중 경매에 묶인 금액"이어야 한다.
    //
    // 동시성: 같은 사용자가 서로 다른 두 경매에 동시에 입찰하면 상품 락만으로는 두 요청이 서로를
    // 못 본다(각자 다른 상품 row를 잠그므로). 둘 다 "다른 경매에 묶인 금액 = 0"으로 읽고 둘 다
    // 통과하면 보유 크레딧을 넘게 된다. 그래서 입찰자 User row를 잠가 같은 사용자의 입찰 판정을
    // 한 줄로 세운다. 두 번째 요청은 첫 요청이 커밋한 뒤에 묶인 금액을 계산한다.
    //
    // 락 순서는 항상 "상품 -> 사용자"다(낙찰 처리도 상품 -> 사용자 순서). 순서가 일관되어야
    // 서로 반대 순서로 잠그는 데드락이 생기지 않는다.
    private boolean hasEnoughCredit(UUID bidderId, UUID productId, int amount) {
        User bidder = userRepository.findByIdForUpdate(bidderId)
                .orElseThrow(() -> new IllegalStateException("입찰자를 찾을 수 없습니다: " + bidderId));
        return amount <= creditService.availableCreditExcluding(bidder, productId);
    }
}
