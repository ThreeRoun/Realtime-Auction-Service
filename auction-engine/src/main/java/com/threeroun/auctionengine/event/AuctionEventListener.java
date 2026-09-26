package com.threeroun.auctionengine.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// DB 트랜잭션이 실제로 commit된 뒤에만 Redis로 이벤트를 내보낸다.
// BidService.placeBid()의 @Transactional 안에서 바로 publish하면, 그 뒤 같은 트랜잭션에서
// 예외가 나서 롤백되더라도 이미 나가버린 이벤트는 되돌릴 수 없어 "실제로는 안 반영된 입찰"을
// 프론트가 받아보는 상황이 생긴다. AFTER_COMMIT으로 묶어 그 문제를 막는다.
@Component
public class AuctionEventListener {

    private final AuctionEventPublisher publisher;

    public AuctionEventListener(AuctionEventPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBidPlaced(BidPlacedEvent event) {
        publisher.publish(event);
    }
}
