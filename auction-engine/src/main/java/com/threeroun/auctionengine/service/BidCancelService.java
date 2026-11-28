package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.Bid;
import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.event.BidCancelledEvent;
import com.threeroun.auctionengine.repository.BidRepository;
import com.threeroun.auctionengine.repository.ProductRepository;
import com.threeroun.auctionengine.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

// 입찰 취소 (이슈 #182). 본인의 입찰을 진행중인 경매에서 취소한다.
@Service
public class BidCancelService {

    public static final String REASON_USER = "USER";
    public static final String REASON_CREDIT_SHORTFALL = "CREDIT_SHORTFALL";

    private final ProductRepository productRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final CreditService creditService;
    private final ApplicationEventPublisher eventPublisher;

    public BidCancelService(ProductRepository productRepository, BidRepository bidRepository,
                            UserRepository userRepository, CreditService creditService,
                            ApplicationEventPublisher eventPublisher) {
        this.productRepository = productRepository;
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.creditService = creditService;
        this.eventPublisher = eventPublisher;
    }

    // 입찰(placeBid), 마감 처리(closeIfExpired), 경매 취소와 같은 상품 row 락으로 시작한다. 그래서 취소와
    // 입찰이 겹쳐도 한쪽이 끝난 뒤 그 결과를 보고 판정한다.
    //
    // 입찰 행을 읽기 전에 상품 락부터 잡는다(상품 -> 사용자 순서). 입찰 행을 먼저 읽으면 락을 기다리는 사이
    // 다른 트랜잭션이 그 입찰을 이미 취소했는데도 예전 상태로 판단할 수 있다.
    //
    // 취소한 입찰이 최고 입찰이면 현재가를 되돌려야 한다. 되돌릴 후보는 남은 유효 입찰을 금액 높은 순으로 보고,
    // "지금 그 입찰자의 사용 가능 크레딧이 그 입찰 금액 이상인 입찰"을 새 최고 입찰로 삼는다. 이유:
    // 추월당해 묶임이 풀린 입찰자는 그 사이 크레딧을 다른 경매에 썼을 수 있다. 그 입찰을 그대로 되살리면
    // 묶인 금액의 합이 보유 크레딧을 넘어, 최고 입찰자는 항상 낙찰가를 낼 수 있다는 전제가 깨진다.
    // 자격이 없는 입찰은 건너뛰되 그냥 두면 낙찰 후보로 남아 현재가보다 높은 입찰이 살아 있게 되므로,
    // 시스템이 취소(CREDIT_SHORTFALL) 처리한다. 자격 있는 입찰이 없으면 시작가로 돌아가고 최고 입찰자는 없다
    // (경매는 계속 진행되고, 입찰이 없으면 마감 때 유찰된다).
    //
    // 마감 시각과 연장 횟수는 되돌리지 않는다 (이미 일어난 연장은 취소와 무관하게 유지).
    @Transactional
    public BidCancelResult cancel(UUID bidId, UUID requesterId) {
        UUID productId = bidRepository.findProductIdById(bidId)
                .orElseThrow(() -> new BidNotFoundException(bidId));
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        Bid bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new BidNotFoundException(bidId));

        if (!bid.getBidder().getId().equals(requesterId)) {
            throw new BidCancelNotAllowedException(bidId);
        }
        if (product.getStatus() != ProductStatus.IN_PROGRESS && product.getStatus() != ProductStatus.EXTENDED) {
            throw new AuctionNotInProgressException(productId, product.getStatus());
        }
        LocalDateTime now = LocalDateTime.now();
        if (!now.isBefore(product.getEndAt())) {
            throw new AuctionNotInProgressException(productId, product.getEndAt());
        }
        if (!bid.isActive()) {
            throw new BidNotCancellableException(bidId);
        }

        boolean wasTop = bid.getAmount().equals(product.getCurrentPrice());
        bid.cancel(REASON_USER, now);
        List<UUID> cancelledIds = new ArrayList<>();
        cancelledIds.add(bid.getId());

        // 취소를 반영한 뒤 남은 유효 입찰 (금액 높은 순). 쿼리 전에 위의 취소가 자동으로 flush된다.
        List<Bid> remaining = bidRepository.findByProductIdAndValidTrueAndCancelledAtIsNullOrderByAmountDesc(productId);

        UUID topBidderId;
        if (wasTop) {
            Bid newTop = restoreTopBid(product, remaining, now, cancelledIds);
            topBidderId = newTop != null ? newTop.getBidder().getId() : null;
        } else {
            // 최고가가 아닌 입찰을 취소하면 현재가와 최고 입찰자는 그대로다
            topBidderId = remaining.isEmpty() ? null : remaining.get(0).getBidder().getId();
        }

        User requester = userRepository.findById(requesterId)
                .orElseThrow(() -> new UserNotFoundException(requesterId));
        long availableCredit = creditService.availableCredit(requester);

        int currentPrice = product.getCurrentPrice();
        eventPublisher.publishEvent(new BidCancelledEvent(productId, currentPrice, topBidderId, List.copyOf(cancelledIds)));
        return new BidCancelResult(bidId, productId, currentPrice, topBidderId, List.copyOf(cancelledIds), availableCredit);
    }

    // 남은 유효 입찰 중 새 최고 입찰이 될 수 있는 입찰을 골라 현재가를 되돌린다. 없으면 시작가로 돌아간다.
    private Bid restoreTopBid(Product product, List<Bid> remaining, LocalDateTime now, List<UUID> cancelledIds) {
        // 후보 입찰자 row를 id 순서로 전부 먼저 잠근다. 후보를 높은 금액 순으로 하나씩 잠그면, 같은 사용자들을
        // 다른 순서로 잠그는 다른 트랜잭션(다른 경매의 취소, 마감 처리)과 서로를 기다리는 데드락이 날 수 있다.
        // 여러 사용자 row를 잠글 때는 항상 id 순서로 잠근다는 규칙이다 (AuctionClosingService도 같은 규칙).
        TreeSet<UUID> bidderIds = new TreeSet<>();
        for (Bid candidate : remaining) {
            bidderIds.add(candidate.getBidder().getId());
        }
        for (UUID bidderId : bidderIds) {
            userRepository.findByIdForUpdate(bidderId)
                    .orElseThrow(() -> new IllegalStateException("입찰자를 찾을 수 없습니다: " + bidderId));
        }

        for (Bid candidate : remaining) {
            UUID candidateBidderId = candidate.getBidder().getId();
            User bidder = userRepository.findByIdForUpdate(candidateBidderId)
                    .orElseThrow(() -> new IllegalStateException("입찰자를 찾을 수 없습니다: " + candidateBidderId));
            // 이 경매에 묶였던 돈은 이미 풀려 있으니, 다른 경매에 묶인 금액만 뺀 크레딧으로 판단한다
            if (candidate.getAmount() <= creditService.availableCreditExcluding(bidder, product.getId())) {
                product.setCurrentPrice(candidate.getAmount());
                return candidate;
            }
            candidate.cancel(REASON_CREDIT_SHORTFALL, now);
            cancelledIds.add(candidate.getId());
        }
        product.setCurrentPrice(product.getStartingPrice());
        return null;
    }
}
