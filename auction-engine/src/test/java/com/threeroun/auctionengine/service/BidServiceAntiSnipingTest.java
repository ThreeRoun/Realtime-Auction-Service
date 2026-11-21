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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BidServiceAntiSnipingTest {

    private static final int STARTING_PRICE = 10_000;
    private static final int BID_UNIT = 1_000;

    @Autowired
    private BidService bidService;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;

    private User user(String role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(new User(role + "-" + suffix, role + "-" + suffix + "@test.com", "hash"));
    }

    private Product product(User seller, LocalDateTime endAt) {
        Product p = new Product("테스트 상품", "안티 스나이핑 테스트용", STARTING_PRICE, BID_UNIT,
                seller, LocalDateTime.now().minusMinutes(10), endAt);
        p.setStatus(ProductStatus.IN_PROGRESS);
        return productRepository.save(p);
    }

    @Test
    void 마감_임박_시_유효한_입찰이면_마감이_연장되고_EXTENDED_상태가_된다() {
        User seller = user("seller");
        User bidder = user("bidder");
        LocalDateTime originalEndAt = LocalDateTime.now().plusSeconds(10); // 안티 스나이핑 윈도우(30초) 안
        Product product = product(seller, originalEndAt);

        BidResult result = bidService.placeBid(product.getId(), bidder.getId(), STARTING_PRICE + BID_UNIT);

        assertThat(result.bid().isValid()).isTrue();
        assertThat(result.extended()).isTrue();

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ProductStatus.EXTENDED);
        assertThat(reloaded.getEndAt()).isAfter(originalEndAt.plus(BidService.EXTENSION_DURATION.minusSeconds(1)));
    }

    // 이슈 #152 - 마감 시각이 이미 지난 입찰이 "마감 임박"으로 취급되어 마감이 연장되면 안 된다.
    // (남은 시간이 음수라서 "30초 이하 남음"으로 판정되던 구멍을 막는다)
    @Test
    void 마감_시각이_지난_입찰은_거절되고_마감이_연장되지_않는다() {
        User seller = user("seller");
        User bidder = user("bidder");
        LocalDateTime originalEndAt = LocalDateTime.now().minusSeconds(5);
        Product product = product(seller, originalEndAt);

        assertThatThrownBy(() -> bidService.placeBid(product.getId(), bidder.getId(), STARTING_PRICE + BID_UNIT))
                .isInstanceOf(AuctionNotInProgressException.class);

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ProductStatus.IN_PROGRESS);
        assertThat(reloaded.getCurrentPrice()).isEqualTo(STARTING_PRICE);
        assertThat(reloaded.getEndAt().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(originalEndAt.truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void 마감이_여유있으면_유효한_입찰이어도_연장되지_않는다() {
        User seller = user("seller");
        User bidder = user("bidder");
        LocalDateTime originalEndAt = LocalDateTime.now().plusHours(1); // 윈도우 밖
        Product product = product(seller, originalEndAt);

        BidResult result = bidService.placeBid(product.getId(), bidder.getId(), STARTING_PRICE + BID_UNIT);

        assertThat(result.bid().isValid()).isTrue();
        assertThat(result.extended()).isFalse();

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ProductStatus.IN_PROGRESS);
        // DB 왕복 시 타임스탬프가 마이크로초 단위로 반올림되므로, 나노초 단위 완전 일치 대신 초 단위로 비교한다.
        assertThat(reloaded.getEndAt().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(originalEndAt.truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void 마감_임박이어도_진_입찰이면_연장되지_않는다() {
        User seller = user("seller");
        User bidder = user("bidder");
        LocalDateTime originalEndAt = LocalDateTime.now().plusSeconds(10);
        Product product = product(seller, originalEndAt);

        // 최소 입찰가 미만 -> 진 입찰
        BidResult result = bidService.placeBid(product.getId(), bidder.getId(), STARTING_PRICE);

        assertThat(result.bid().isValid()).isFalse();
        assertThat(result.extended()).isFalse();

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ProductStatus.IN_PROGRESS);
        assertThat(reloaded.getEndAt().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(originalEndAt.truncatedTo(ChronoUnit.SECONDS));
    }

    // 8차 회의 결정: 연장은 1회만. 첫 임박 입찰은 연장되고, 연장된 마감이 다시 임박해도 두 번째는 연장되지 않는다.
    @Test
    void 연장은_한_번만_되고_두_번째_임박_입찰은_연장되지_않는다() {
        User seller = user("seller");
        User first = user("first");
        User second = user("second");
        Product product = product(seller, LocalDateTime.now().plusSeconds(10));

        BidResult firstResult = bidService.placeBid(product.getId(), first.getId(), STARTING_PRICE + BID_UNIT);
        assertThat(firstResult.extended()).isTrue();

        // 연장된 마감이 다시 임박한 상황을 만든다 (다른 사용자라 쿨다운과는 무관)
        Product extended = productRepository.findById(product.getId()).orElseThrow();
        LocalDateTime secondWindowEndAt = LocalDateTime.now().plusSeconds(10);
        extended.setEndAt(secondWindowEndAt);
        productRepository.save(extended);

        BidResult secondResult = bidService.placeBid(product.getId(), second.getId(), STARTING_PRICE + 2 * BID_UNIT);

        assertThat(secondResult.bid().isValid()).isTrue();
        assertThat(secondResult.extended()).isFalse();
        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getExtensionCount()).isEqualTo(1);
        assertThat(reloaded.getEndAt().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(secondWindowEndAt.truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void 연장_횟수가_상한에_도달하면_마감_임박이어도_더_연장되지_않는다() {
        User seller = user("seller");
        User bidder = user("bidder");
        LocalDateTime originalEndAt = LocalDateTime.now().plusSeconds(10); // 윈도우 안
        Product product = product(seller, originalEndAt);
        for (int i = 0; i < BidService.MAX_EXTENSIONS; i++) {
            product.incrementExtensionCount();
        }
        productRepository.save(product);

        BidResult result = bidService.placeBid(product.getId(), bidder.getId(), STARTING_PRICE + BID_UNIT);

        assertThat(result.bid().isValid()).isTrue();
        assertThat(result.extended()).isFalse();

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getExtensionCount()).isEqualTo(BidService.MAX_EXTENSIONS);
        assertThat(reloaded.getEndAt().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(originalEndAt.truncatedTo(ChronoUnit.SECONDS));
    }
}
