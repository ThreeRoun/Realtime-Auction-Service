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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BidServiceCooldownTest {

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

    private Product product(User seller) {
        Product p = new Product("테스트 상품", "쿨다운 테스트용", STARTING_PRICE, BID_UNIT,
                seller, LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusHours(1));
        p.setStatus(ProductStatus.IN_PROGRESS);
        return productRepository.save(p);
    }

    @Test
    void 같은_유저가_같은_상품에_쿨다운_이내_재입찰하면_거절된다() {
        User seller = user("seller");
        User bidder = user("bidder");
        Product product = product(seller);

        bidService.placeBid(product.getId(), bidder.getId(), STARTING_PRICE + BID_UNIT);

        assertThatThrownBy(() ->
                bidService.placeBid(product.getId(), bidder.getId(), STARTING_PRICE + BID_UNIT * 2))
                .isInstanceOf(BidCooldownException.class);
    }

    @Test
    void 쿨다운은_상품별로_독립적이다() {
        User seller1 = user("seller");
        User seller2 = user("seller");
        User bidder = user("bidder");
        Product product1 = product(seller1);
        Product product2 = product(seller2);

        bidService.placeBid(product1.getId(), bidder.getId(), STARTING_PRICE + BID_UNIT);

        BidResult result = bidService.placeBid(product2.getId(), bidder.getId(), STARTING_PRICE + BID_UNIT);
        assertThat(result.bid().isValid()).isTrue();
    }

    @Test
    void 쿨다운은_유저별로_독립적이다() {
        User seller = user("seller");
        User bidder1 = user("bidder");
        User bidder2 = user("bidder");
        Product product = product(seller);

        bidService.placeBid(product.getId(), bidder1.getId(), STARTING_PRICE + BID_UNIT);

        BidResult result = bidService.placeBid(product.getId(), bidder2.getId(), STARTING_PRICE + BID_UNIT * 2);
        assertThat(result.bid().isValid()).isTrue();
    }
}
