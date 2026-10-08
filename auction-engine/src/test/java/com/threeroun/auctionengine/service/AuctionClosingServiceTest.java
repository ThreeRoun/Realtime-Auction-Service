package com.threeroun.auctionengine.service;

import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.repository.ProductRepository;
import com.threeroun.auctionengine.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AuctionClosingServiceTest {

    private static final int STARTING_PRICE = 10_000;
    private static final int BID_UNIT = 1_000;

    @Autowired
    private AuctionClosingService closingService;
    @Autowired
    private BidService bidService;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;

    private User user(String role, int credit) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(new User(role + "-" + suffix, role + "-" + suffix + "@test.com", "hash"));
        if (credit > 0) {
            // 생성자가 credit=0으로 고정이라, 테스트용으로 직접 깎는 메서드가 없으니
            // repository로 바로 올려주는 게 제일 간단하다.
            user.deductCredit(-credit); // amount<=0은 그냥 음수만큼 늘어나는 효과
            userRepository.save(user);
        }
        return user;
    }

    // 입찰은 "아직 한참 남은" 상품에 정상적으로 넣어야 안티 스나이핑 연장이 안 끼어든다.
    // (이미 마감 지난 상품에 입찰하면, BidService가 "마감 임박"으로 보고 자동 연장시켜버려서
    // 낙찰 처리 테스트가 의도와 다르게 EXTENDED로 끝나버린다 - 그래서 입찰까지 다 끝난 뒤에
    // end_at만 과거로 돌려 "지금 막 마감됐다"는 상황을 만든다.)
    private Product futureProduct(User seller) {
        Product product = new Product("테스트 상품", "낙찰 처리 테스트용", STARTING_PRICE, BID_UNIT,
                seller, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        product.setStatus(ProductStatus.IN_PROGRESS);
        return productRepository.save(product);
    }

    private void expireNow(UUID productId) {
        Product product = productRepository.findById(productId).orElseThrow();
        product.setEndAt(LocalDateTime.now().minusSeconds(1));
        productRepository.save(product);
    }

    @Test
    void 마감된_상품은_최고가_입찰자에게_낙찰되고_크레딧이_차감된다() {
        User seller = user("seller", 0);
        User bidder = user("bidder", 100_000);
        Product product = futureProduct(seller);
        bidService.placeBid(product.getId(), bidder.getId(), STARTING_PRICE + BID_UNIT);
        expireNow(product.getId());

        closingService.closeIfExpired(product.getId());

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ProductStatus.SOLD);
        assertThat(reloaded.getWinner().getId()).isEqualTo(bidder.getId());

        User reloadedBidder = userRepository.findById(bidder.getId()).orElseThrow();
        assertThat(reloadedBidder.getCredit()).isEqualTo(100_000 - (STARTING_PRICE + BID_UNIT));
    }

    @Test
    void 최고가_입찰자가_크레딧_부족이면_차순위에게_승계된다() {
        User seller = user("seller", 0);
        User richBidder = user("rich-bidder", 0); // 돈이 없음
        User poorBidder = user("poor-bidder", 100_000);
        Product product = futureProduct(seller);

        // poorBidder가 먼저 낮은 가격, richBidder가 나중에 더 높은 가격 (근데 richBidder는 크레딧 0)
        bidService.placeBid(product.getId(), poorBidder.getId(), STARTING_PRICE + BID_UNIT);
        bidService.placeBid(product.getId(), richBidder.getId(), STARTING_PRICE + BID_UNIT * 2);
        expireNow(product.getId());

        closingService.closeIfExpired(product.getId());

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ProductStatus.SOLD);
        assertThat(reloaded.getWinner().getId()).isEqualTo(poorBidder.getId());
    }

    @Test
    void 유효한_입찰이_하나도_없으면_유찰_처리된다() {
        User seller = user("seller", 0);
        Product product = futureProduct(seller);
        expireNow(product.getId());

        closingService.closeIfExpired(product.getId());

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ProductStatus.UNSOLD);
        assertThat(reloaded.getWinner()).isNull();
    }

    @Test
    void 아직_마감_안_된_상품은_건드리지_않는다() {
        User seller = user("seller", 0);
        Product product = new Product("테스트 상품", "설명", STARTING_PRICE, BID_UNIT,
                seller, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        product.setStatus(ProductStatus.IN_PROGRESS);
        product = productRepository.save(product);

        closingService.closeIfExpired(product.getId());

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ProductStatus.IN_PROGRESS);
    }

    @Test
    void 시작시각_지난_등록대기_상품은_진행중으로_전환된다() {
        User seller = user("seller", 0);
        Product product = new Product("테스트 상품", "설명", STARTING_PRICE, BID_UNIT,
                seller, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        // 생성자가 PENDING으로 시작하므로 그대로 둠
        product = productRepository.save(product);

        closingService.activatePendingAuctions();

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ProductStatus.IN_PROGRESS);
    }
}
