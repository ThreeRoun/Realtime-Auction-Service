package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.Bid;
import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.repository.BidRepository;
import com.threeroun.auctionengine.repository.ProductRepository;
import com.threeroun.auctionengine.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

// 이 프로젝트의 핵심 검증 대상: 같은 상품에 동일 금액 입찰이 동시에 몰려도
// 정확히 하나만 최고가로 확정되는지 확인한다. 비관적 락 없이(findById로만 조회) 돌리면
// 여러 스레드가 동시에 성공해버리는 레이스 컨디션을 실제로 재현해서 확인할 수 있다.
@SpringBootTest
class BidServiceConcurrencyTest {

    @Autowired
    private BidService bidService;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BidRepository bidRepository;

    private User seller;
    private User bidder;
    private Product product;

    private static final int STARTING_PRICE = 10_000;
    private static final int BID_UNIT = 1_000;

    @BeforeEach
    void setUp() {
        // 테스트 메서드마다 새로 실행되므로 username/email 유니크 제약에 걸리지 않게 매번 고유값 사용
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        seller = userRepository.save(new User("seller-" + suffix, "seller-" + suffix + "@test.com", "hash"));
        bidder = userRepository.save(new User("bidder-" + suffix, "bidder-" + suffix + "@test.com", "hash"));

        Product newProduct = new Product("테스트 상품", "동시성 테스트용", STARTING_PRICE, BID_UNIT,
                seller, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        newProduct.setStatus(ProductStatus.IN_PROGRESS);
        product = productRepository.save(newProduct);
    }

    @Test
    void 같은_금액으로_동시에_입찰하면_하나만_최고가로_확정된다() throws InterruptedException {
        int threadCount = 20;
        int bidAmount = STARTING_PRICE + BID_UNIT; // 20명 모두 최소 입찰가로 동시 입찰

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    BidResult result = bidService.placeBid(product.getId(), bidder.getId(), bidAmount);
                    if (result.bid().isValid()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // 대기 중이던 스레드 20개를 동시에 출발시킨다
        boolean finished = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).as("모든 스레드가 15초 안에 끝나야 한다 (데드락/무한대기 없음)").isTrue();

        // 핵심 검증: 20번 동시에 들어왔어도 유효한(is_valid=true) 입찰은 정확히 1건이어야 한다
        assertThat(successCount.get()).isEqualTo(1);

        List<Bid> allBids = bidRepository.findByProductId(product.getId());
        assertThat(allBids).hasSize(threadCount);
        long validCount = allBids.stream().filter(Bid::isValid).count();
        assertThat(validCount).isEqualTo(1);

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(bidAmount);
    }

    @Test
    void 서로_다른_금액으로_동시에_입찰해도_최종_현재가는_최댓값으로_수렴한다() throws InterruptedException {
        int threadCount = 10;
        // 11000, 12000, ..., 20000원 입찰이 순서 상관없이 동시에 들어오는 상황.
        // 락 때문에 실제 처리 순서는 보장되지 않으므로 "몇 건이 성공하느냐"는 순서에 따라 달라진다
        // (예: 20000원이 먼저 처리되면 그 뒤로는 전부 실패). 순서와 무관하게 항상 보장되는 건
        // "최댓값(20000)은 그게 언제 처리되든 그 시점 current_price보다 항상 높으므로 반드시 성공하고,
        // 그 이후로는 아무도 그걸 못 넘으므로 최종 current_price == 제출된 금액의 최댓값"이라는 점뿐이다.
        List<Integer> amounts = IntStream.rangeClosed(1, threadCount)
                .mapToObj(i -> STARTING_PRICE + i * BID_UNIT)
                .collect(Collectors.toList());
        int maxAmount = amounts.get(amounts.size() - 1);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int amount : amounts) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    bidService.placeBid(product.getId(), bidder.getId(), amount);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(maxAmount);

        List<Bid> allBids = bidRepository.findByProductId(product.getId());
        assertThat(allBids).hasSize(threadCount);
        // 처리 순서에 따라 성공 건수 자체는 달라질 수 있지만, 최댓값 입찰은 언제 처리되든
        // 항상 성공하므로 유효한 입찰은 최소 1건 이상이어야 한다.
        assertThat(allBids.stream().filter(Bid::isValid).count()).isGreaterThanOrEqualTo(1);
    }
}
