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
class BidServiceSelfBidTest {

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

    private Product inProgressProduct(User seller) {
        Product product = new Product("테스트 상품", "자전거래 방지 테스트용", STARTING_PRICE, BID_UNIT,
                seller, LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusHours(1));
        product.setStatus(ProductStatus.IN_PROGRESS);
        return productRepository.save(product);
    }

    @Test
    void 판매자가_본인_상품에_입찰하면_예외가_발생한다() {
        User seller = user("seller");
        Product product = inProgressProduct(seller);

        assertThatThrownBy(() -> bidService.placeBid(product.getId(), seller.getId(), STARTING_PRICE + BID_UNIT))
                .isInstanceOf(SelfBidNotAllowedException.class);
    }

    @Test
    void 다른_사람이_입찰하면_정상_처리된다() {
        User seller = user("seller");
        User bidder = user("bidder");
        Product product = inProgressProduct(seller);

        BidResult result = bidService.placeBid(product.getId(), bidder.getId(), STARTING_PRICE + BID_UNIT);

        assertThat(result.bid().isValid()).isTrue();
    }
}
