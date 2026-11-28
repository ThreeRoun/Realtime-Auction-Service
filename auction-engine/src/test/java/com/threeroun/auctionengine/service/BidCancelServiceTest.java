package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.Bid;
import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.repository.BidRepository;
import com.threeroun.auctionengine.repository.ProductRepository;
import com.threeroun.auctionengine.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 이슈 #182: 입찰 취소. 최고 입찰을 취소하면 직전 유효 입찰로 되돌아가되, 그 입찰자의 사용 가능 크레딧이
// 모자라면 건너뛴다 (건너뛴 입찰은 시스템이 CREDIT_SHORTFALL로 취소 처리).
@SpringBootTest
class BidCancelServiceTest {

    private static final int STARTING_PRICE = 10_000;
    private static final int BID_UNIT = 1_000;

    @Autowired
    private BidService bidService;
    @Autowired
    private BidCancelService bidCancelService;
    @Autowired
    private AuctionClosingService closingService;
    @Autowired
    private AuctionCancelService auctionCancelService;
    @Autowired
    private CreditService creditService;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BidRepository bidRepository;

    private User user(String role, int credit) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(new User(role + "-" + suffix, role + "-" + suffix + "@test.com", "hash", credit));
    }

    private Product product(User seller) {
        Product p = new Product("테스트 상품", "입찰 취소 테스트용", STARTING_PRICE, BID_UNIT,
                seller, LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusHours(1));
        p.setStatus(ProductStatus.IN_PROGRESS);
        return productRepository.save(p);
    }

    private UUID bid(Product product, User bidder, int amount) {
        BidResult result = bidService.placeBid(product.getId(), bidder.getId(), amount);
        assertThat(result.bid().isValid()).as("%d 입찰이 유효해야 한다", amount).isTrue();
        return result.bid().getId();
    }

    private Product reload(Product product) {
        return productRepository.findById(product.getId()).orElseThrow();
    }

    private long available(User user) {
        return creditService.availableCredit(userRepository.findById(user.getId()).orElseThrow());
    }

    @Test
    void 최고_입찰을_취소하면_직전_유효_입찰로_되돌아가고_그_입찰자의_크레딧이_다시_묶인다() {
        User seller = user("seller", 0);
        User first = user("first", 500_000);
        User second = user("second", 500_000);
        Product product = product(seller);
        bid(product, first, 30_000);
        UUID secondBid = bid(product, second, 40_000);
        assertThat(available(first)).isEqualTo(500_000); // 추월당해서 묶임이 풀렸다

        BidCancelResult result = bidCancelService.cancel(secondBid, second.getId());

        assertThat(result.currentPrice()).isEqualTo(30_000);
        assertThat(result.topBidderId()).isEqualTo(first.getId());
        assertThat(result.cancelledBidIds()).containsExactly(secondBid);
        assertThat(reload(product).getCurrentPrice()).isEqualTo(30_000);
        assertThat(available(first)).isEqualTo(470_000); // 되살아난 최고 입찰로 다시 묶인다
        assertThat(available(second)).isEqualTo(500_000); // 취소한 사람의 묶임은 풀린다
        assertThat(result.availableCredit()).isEqualTo(500_000);
    }

    @Test
    void 입찰은_로그로_남고_취소_시각과_사유가_기록된다() {
        User seller = user("seller", 0);
        User bidder = user("bidder", 500_000);
        Product product = product(seller);
        UUID bidId = bid(product, bidder, 30_000);

        bidCancelService.cancel(bidId, bidder.getId());

        Bid cancelled = bidRepository.findById(bidId).orElseThrow();
        assertThat(cancelled.isValid()).isTrue(); // 들어온 순간의 기록(is_valid)은 그대로
        assertThat(cancelled.isCancelled()).isTrue();
        assertThat(cancelled.isActive()).isFalse();
        assertThat(cancelled.getCancelReason()).isEqualTo(BidCancelService.REASON_USER);
        assertThat(cancelled.getCancelledAt()).isNotNull();
    }

    @Test
    void 남은_입찰이_없으면_시작가로_돌아가고_최고_입찰자는_없다() {
        User seller = user("seller", 0);
        User bidder = user("bidder", 500_000);
        Product product = product(seller);
        UUID bidId = bid(product, bidder, 30_000);

        BidCancelResult result = bidCancelService.cancel(bidId, bidder.getId());

        assertThat(result.currentPrice()).isEqualTo(STARTING_PRICE);
        assertThat(result.topBidderId()).isNull();
        assertThat(reload(product).getCurrentPrice()).isEqualTo(STARTING_PRICE);
        assertThat(available(bidder)).isEqualTo(500_000);
    }

    @Test
    void 최고가가_아닌_입찰을_취소해도_현재가와_최고_입찰자는_그대로다() {
        User seller = user("seller", 0);
        User first = user("first", 500_000);
        User second = user("second", 500_000);
        Product product = product(seller);
        UUID firstBid = bid(product, first, 30_000);
        bid(product, second, 40_000);

        BidCancelResult result = bidCancelService.cancel(firstBid, first.getId());

        assertThat(result.currentPrice()).isEqualTo(40_000);
        assertThat(result.topBidderId()).isEqualTo(second.getId());
        assertThat(reload(product).getCurrentPrice()).isEqualTo(40_000);
        assertThat(available(second)).isEqualTo(460_000);
    }

    // 추월당해 묶임이 풀린 직전 입찰자가 그 사이 다른 경매에 크레딧을 써서 자격이 없는 경우:
    // 그 입찰은 건너뛰고 시스템이 취소 처리한다. 자격 있는 입찰이 없으면 시작가로 돌아간다.
    @Test
    void 직전_입찰자의_크레딧이_모자라면_건너뛰고_자격_없는_입찰은_시스템이_취소한다() {
        User seller = user("seller", 0);
        User previous = user("previous", 500_000);
        User top = user("top", 500_000);
        Product auctionA = product(seller);
        Product auctionD = product(seller);
        UUID previousBid = bid(auctionA, previous, 300_000);
        UUID topBid = bid(auctionA, top, 350_000); // previous 추월, previous의 30만 묶임 해제
        bid(auctionD, previous, 400_000);          // previous가 그 크레딧을 다른 경매에 사용 (사용 가능 10만)

        BidCancelResult result = bidCancelService.cancel(topBid, top.getId());

        assertThat(result.currentPrice()).isEqualTo(STARTING_PRICE);
        assertThat(result.topBidderId()).isNull();
        assertThat(result.cancelledBidIds()).containsExactlyInAnyOrder(topBid, previousBid);
        Bid skipped = bidRepository.findById(previousBid).orElseThrow();
        assertThat(skipped.getCancelReason()).isEqualTo(BidCancelService.REASON_CREDIT_SHORTFALL);
        assertThat(available(previous)).isEqualTo(100_000); // 묶인 금액은 경매 D의 40만뿐
    }

    @Test
    void 자격_없는_입찰은_건너뛰고_그_아래에서_자격_있는_입찰로_되돌아간다() {
        User seller = user("seller", 0);
        User low = user("low", 500_000);
        User previous = user("previous", 500_000);
        User top = user("top", 500_000);
        Product auctionA = product(seller);
        Product auctionD = product(seller);
        UUID lowBid = bid(auctionA, low, 200_000);
        UUID previousBid = bid(auctionA, previous, 300_000);
        UUID topBid = bid(auctionA, top, 350_000);
        bid(auctionD, previous, 400_000); // previous는 이제 30만을 다시 묶을 수 없다

        BidCancelResult result = bidCancelService.cancel(topBid, top.getId());

        assertThat(result.currentPrice()).isEqualTo(200_000);
        assertThat(result.topBidderId()).isEqualTo(low.getId());
        assertThat(result.cancelledBidIds()).containsExactlyInAnyOrder(topBid, previousBid);
        assertThat(bidRepository.findById(lowBid).orElseThrow().isActive()).isTrue();
        assertThat(available(low)).isEqualTo(300_000);
    }

    @Test
    void 취소된_입찰은_낙찰_후보에서_빠진다() {
        User seller = user("seller", 0);
        User first = user("first", 500_000);
        User second = user("second", 500_000);
        Product product = product(seller);
        bid(product, first, 30_000);
        UUID secondBid = bid(product, second, 40_000);
        bidCancelService.cancel(secondBid, second.getId());
        Product expired = reload(product);
        expired.setEndAt(LocalDateTime.now().minusSeconds(1));
        productRepository.save(expired);

        closingService.closeIfExpired(product.getId());

        Product closed = reload(product);
        assertThat(closed.getStatus()).isEqualTo(ProductStatus.SOLD);
        assertThat(closed.getWinner().getId()).isEqualTo(first.getId());
        assertThat(userRepository.findById(second.getId()).orElseThrow().getCredit()).isEqualTo(500_000);
    }

    @Test
    void 유일한_입찰을_취소하면_판매자가_경매를_취소할_수_있다() {
        User seller = user("seller", 0);
        User bidder = user("bidder", 500_000);
        Product product = product(seller);
        UUID bidId = bid(product, bidder, 30_000);
        assertThatThrownBy(() -> auctionCancelService.cancel(product.getId(), seller.getId()))
                .isInstanceOf(AuctionNotCancellableException.class);

        bidCancelService.cancel(bidId, bidder.getId());
        auctionCancelService.cancel(product.getId(), seller.getId());

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.CANCELLED);
    }

    @Test
    void 다른_사람의_입찰은_취소할_수_없다() {
        User seller = user("seller", 0);
        User bidder = user("bidder", 500_000);
        User other = user("other", 500_000);
        Product product = product(seller);
        UUID bidId = bid(product, bidder, 30_000);

        assertThatThrownBy(() -> bidCancelService.cancel(bidId, other.getId()))
                .isInstanceOf(BidCancelNotAllowedException.class);
        assertThat(reload(product).getCurrentPrice()).isEqualTo(30_000);
    }

    @Test
    void 이미_취소된_입찰과_최고가를_갱신하지_못한_입찰은_취소할_수_없다() {
        User seller = user("seller", 0);
        User bidder = user("bidder", 500_000);
        User loser = user("loser", 500_000);
        Product product = product(seller);
        UUID bidId = bid(product, bidder, 30_000);
        UUID losingBid = bidService.placeBid(product.getId(), loser.getId(), STARTING_PRICE).bid().getId(); // 금액이 낮아 진 입찰
        bidCancelService.cancel(bidId, bidder.getId());

        assertThatThrownBy(() -> bidCancelService.cancel(bidId, bidder.getId()))
                .isInstanceOf(BidNotCancellableException.class);
        assertThatThrownBy(() -> bidCancelService.cancel(losingBid, loser.getId()))
                .isInstanceOf(BidNotCancellableException.class);
    }

    @Test
    void 마감된_경매의_입찰은_취소할_수_없다() {
        User seller = user("seller", 0);
        User bidder = user("bidder", 500_000);
        Product product = product(seller);
        UUID bidId = bid(product, bidder, 30_000);
        Product expired = reload(product);
        expired.setEndAt(LocalDateTime.now().minusSeconds(1));
        productRepository.save(expired);

        assertThatThrownBy(() -> bidCancelService.cancel(bidId, bidder.getId()))
                .isInstanceOf(AuctionNotInProgressException.class);
    }

    @Test
    void 존재하지_않는_입찰은_취소할_수_없다() {
        User bidder = user("bidder", 500_000);

        assertThatThrownBy(() -> bidCancelService.cancel(UUID.randomUUID(), bidder.getId()))
                .isInstanceOf(BidNotFoundException.class);
    }

    // 취소로 직전 입찰자(previous)가 최고 입찰자로 되살아나는 순간, previous가 다른 경매에 입찰하고 있으면
    // 두 요청이 서로를 못 본다. previous 사용자 row 락이 없으면 둘 다 통과해서 묶인 금액의 합이
    // 보유 크레딧을 넘는다. 어느 쪽이 먼저 끝나든 사용 가능 크레딧은 음수가 되면 안 된다.
    @Test
    void 취소와_직전_입찰자의_다른_경매_입찰이_겹쳐도_보유_크레딧을_넘게_묶이지_않는다() throws Exception {
        for (int round = 0; round < 5; round++) {
            User seller = user("seller", 0);
            User previous = user("previous", 500_000);
            User top = user("top", 500_000);
            Product auctionA = product(seller);
            Product auctionD = product(seller);
            bid(auctionA, previous, 300_000);
            UUID topBid = bid(auctionA, top, 350_000);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return bidService.placeBid(auctionD.getId(), previous.getId(), 400_000);
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return bidCancelService.cancel(topBid, top.getId());
            }));
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();

            assertThat(available(previous)).as("round %d", round).isGreaterThanOrEqualTo(0);
        }
    }
}
