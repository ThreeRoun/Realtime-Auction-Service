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

// 이슈 #163: 입찰하면 "내가 최고 입찰자인 진행중 경매"의 금액만큼 크레딧이 묶이고,
// 사용 가능 크레딧(보유 - 묶인 금액)을 넘는 입찰은 거절된다.
@SpringBootTest
class BidCreditLockTest {

    private static final int STARTING_PRICE = 10_000;
    private static final int BID_UNIT = 1_000;

    @Autowired
    private BidService bidService;
    @Autowired
    private CreditService creditService;
    @Autowired
    private AuctionClosingService closingService;
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
        Product p = new Product("테스트 상품", "크레딧 묶기 테스트용", STARTING_PRICE, BID_UNIT,
                seller, LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusHours(1));
        p.setStatus(ProductStatus.IN_PROGRESS);
        return productRepository.save(p);
    }

    private long available(User user) {
        return creditService.availableCredit(userRepository.findById(user.getId()).orElseThrow());
    }

    @Test
    void 보유_크레딧보다_높은_입찰은_거절되고_진_입찰로_기록된다() {
        User seller = user("seller", 0);
        User bidder = user("bidder", 50_000);
        Product product = product(seller);

        BidResult result = bidService.placeBid(product.getId(), bidder.getId(), 60_000);

        assertThat(result.bid().isValid()).isFalse();
        assertThat(result.reason()).isEqualTo(BidResult.INSUFFICIENT_CREDIT);
        // 가격은 그대로, 입찰 기록은 남는다 (진 입찰도 로그로 보존)
        assertThat(productRepository.findById(product.getId()).orElseThrow().getCurrentPrice()).isEqualTo(STARTING_PRICE);
        List<Bid> bids = bidRepository.findByProductId(product.getId());
        assertThat(bids).hasSize(1);
        assertThat(bids.get(0).isValid()).isFalse();
    }

    @Test
    void 보유_크레딧과_같은_금액까지는_입찰할_수_있다() {
        User seller = user("seller", 0);
        User bidder = user("bidder", 50_000);
        Product product = product(seller);

        BidResult result = bidService.placeBid(product.getId(), bidder.getId(), 50_000);

        assertThat(result.bid().isValid()).isTrue();
        assertThat(available(bidder)).isZero();
    }

    @Test
    void 한_경매에_묶인_크레딧은_다른_경매에서_쓸_수_없다() {
        User seller = user("seller", 0);
        User bidder = user("bidder", 100_000);
        Product a = product(seller);
        Product b = product(seller);
        Product c = product(seller);

        assertThat(bidService.placeBid(a.getId(), bidder.getId(), 60_000).bid().isValid()).isTrue();
        assertThat(available(bidder)).isEqualTo(40_000);

        // 남은 사용 가능 크레딧(40,000)보다 큰 50,000은 거절, 40,000은 허용
        BidResult tooMuch = bidService.placeBid(b.getId(), bidder.getId(), 50_000);
        assertThat(tooMuch.bid().isValid()).isFalse();
        assertThat(tooMuch.reason()).isEqualTo(BidResult.INSUFFICIENT_CREDIT);
        assertThat(bidService.placeBid(c.getId(), bidder.getId(), 40_000).bid().isValid()).isTrue();
        assertThat(available(bidder)).isZero();
    }

    @Test
    void 다른_사람이_더_높게_입찰하면_묶였던_크레딧이_풀린다() {
        User seller = user("seller", 0);
        User bidder = user("bidder", 100_000);
        User rival = user("rival", 500_000);
        Product a = product(seller);
        Product b = product(seller);

        bidService.placeBid(a.getId(), bidder.getId(), 60_000);
        assertThat(available(bidder)).isEqualTo(40_000);

        bidService.placeBid(a.getId(), rival.getId(), 70_000); // 추월
        assertThat(available(bidder)).isEqualTo(100_000);
        assertThat(bidService.placeBid(b.getId(), bidder.getId(), 90_000).bid().isValid()).isTrue();
    }

    @Test
    void 경매가_마감되면_묶임이_없어지고_낙찰가만큼만_차감된다() {
        User seller = user("seller", 0);
        User bidder = user("bidder", 100_000);
        Product a = product(seller);
        bidService.placeBid(a.getId(), bidder.getId(), 60_000);
        Product reloaded = productRepository.findById(a.getId()).orElseThrow();
        reloaded.setEndAt(LocalDateTime.now().minusSeconds(1));
        productRepository.save(reloaded);

        closingService.closeIfExpired(a.getId());

        // 보유 100,000 - 낙찰 60,000 = 40,000. 마감된 경매에는 더 이상 묶인 게 없다.
        User after = userRepository.findById(bidder.getId()).orElseThrow();
        assertThat(after.getCredit()).isEqualTo(40_000);
        assertThat(available(bidder)).isEqualTo(40_000);
    }

    @Test
    void 같은_경매의_입찰가를_올릴_때는_그_경매에_묶인_금액을_빼고_계산한다() {
        User seller = user("seller", 0);
        User bidder = user("bidder", 100_000);
        Product a = product(seller);
        bidService.placeBid(a.getId(), bidder.getId(), 60_000);

        // 이 경매에 이미 60,000이 묶여 있지만, 새 입찰가가 그걸 대체하므로 "다른 경매에 묶인 금액"은 0이다.
        // 따라서 이 경매에는 보유 크레딧 전체(100,000)까지 입찰할 수 있다.
        User reloaded = userRepository.findById(bidder.getId()).orElseThrow();
        assertThat(creditService.availableCreditExcluding(reloaded, a.getId())).isEqualTo(100_000);
        assertThat(creditService.availableCredit(reloaded)).isEqualTo(40_000);
    }

    // 같은 사용자가 서로 다른 두 경매에 동시에 입찰해도, 합계가 보유 크레딧을 넘으면 안 된다.
    // 상품 락만으로는 두 요청이 서로를 보지 못하므로(각자 다른 상품 row를 잠금) 입찰자 row 락이
    // 없으면 둘 다 "다른 경매에 묶인 금액 = 0"으로 읽고 통과해 버린다. 타이밍 운에 기대지 않게 반복한다.
    @Test
    void 같은_사용자가_두_경매에_동시에_입찰해도_보유_크레딧을_넘지_못한다() throws Exception {
        for (int round = 0; round < 5; round++) {
            User seller = user("seller", 0);
            User bidder = user("bidder", 100_000);
            Product a = product(seller);
            Product b = product(seller);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<BidResult>> futures = new ArrayList<>();
            for (Product target : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return bidService.placeBid(target.getId(), bidder.getId(), 60_000);
                }));
            }
            ready.await();
            start.countDown();
            int valid = 0;
            for (Future<BidResult> future : futures) {
                if (future.get(30, TimeUnit.SECONDS).bid().isValid()) {
                    valid++;
                }
            }
            pool.shutdown();

            // 60,000 + 60,000 > 100,000 이므로 정확히 하나만 성공해야 한다
            assertThat(valid).as("round %d", round).isEqualTo(1);
            assertThat(available(bidder)).as("round %d", round).isEqualTo(40_000);
        }
    }
}
