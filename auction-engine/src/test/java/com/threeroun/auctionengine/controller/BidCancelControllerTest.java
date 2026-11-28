package com.threeroun.auctionengine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.repository.ProductRepository;
import com.threeroun.auctionengine.repository.UserRepository;
import com.threeroun.auctionengine.service.BidService;
import com.threeroun.auctionengine.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 이슈 #182: DELETE /api/bids/{bidId}
@SpringBootTest
@AutoConfigureMockMvc
class BidCancelControllerTest {

    private static final int STARTING_PRICE = 10_000;
    private static final int BID_UNIT = 1_000;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BidService bidService;
    @Autowired
    private JwtService jwtService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private User user(String role, int credit) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(new User(role + "-" + suffix, role + "-" + suffix + "@test.com", "hash", credit));
    }

    private String bearerToken(User user) {
        return "Bearer " + jwtService.issueToken(user.getId());
    }

    private Product product(User seller) {
        Product p = new Product("테스트 상품", "입찰 취소 API 테스트용", STARTING_PRICE, BID_UNIT,
                seller, LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusHours(1));
        p.setStatus(ProductStatus.IN_PROGRESS);
        return productRepository.save(p);
    }

    @Test
    void 본인_입찰을_취소하면_200과_복원된_현재가를_반환한다() throws Exception {
        User seller = user("seller", 0);
        User first = user("first", 500_000);
        User second = user("second", 500_000);
        Product product = product(seller);
        bidService.placeBid(product.getId(), first.getId(), 30_000);
        UUID secondBid = bidService.placeBid(product.getId(), second.getId(), 40_000).bid().getId();

        String response = mockMvc.perform(delete("/api/bids/{id}", secondBid)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(second)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> body = objectMapper.readValue(response, Map.class);
        assertThat(body.get("productId")).isEqualTo(product.getId().toString());
        assertThat(body.get("currentPrice")).isEqualTo(30_000);
        assertThat(body.get("topBidderId")).isEqualTo(first.getId().toString());
        assertThat(body.get("cancelledBidIds")).isEqualTo(List.of(secondBid.toString()));
        assertThat(body.get("availableCredit")).isEqualTo(500_000);
    }

    @Test
    void 입찰_이력에서_취소된_입찰은_유효하지_않게_보이고_취소_정보가_함께_내려간다() throws Exception {
        User seller = user("seller", 0);
        User bidder = user("bidder", 500_000);
        Product product = product(seller);
        UUID bidId = bidService.placeBid(product.getId(), bidder.getId(), 30_000).bid().getId();
        mockMvc.perform(delete("/api/bids/{id}", bidId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(bidder)))
                .andExpect(status().isOk());

        String response = mockMvc.perform(get("/api/products/{id}/bids", product.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<?> history = objectMapper.readValue(response, List.class);
        Map<?, ?> entry = (Map<?, ?>) history.get(0);
        assertThat(entry.get("isValid")).isEqualTo(false);
        assertThat(entry.get("cancelledAt")).isNotNull();
        assertThat(entry.get("cancelReason")).isEqualTo("USER");
    }

    @Test
    void 다른_사람의_입찰을_취소하면_403을_반환한다() throws Exception {
        User seller = user("seller", 0);
        User bidder = user("bidder", 500_000);
        User other = user("other", 500_000);
        Product product = product(seller);
        UUID bidId = bidService.placeBid(product.getId(), bidder.getId(), 30_000).bid().getId();

        mockMvc.perform(delete("/api/bids/{id}", bidId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 토큰_없이_취소하면_401을_반환한다() throws Exception {
        mockMvc.perform(delete("/api/bids/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 존재하지_않는_입찰을_취소하면_404를_반환한다() throws Exception {
        User bidder = user("bidder", 500_000);

        mockMvc.perform(delete("/api/bids/{id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(bidder)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 이미_취소된_입찰을_다시_취소하면_409를_반환한다() throws Exception {
        User seller = user("seller", 0);
        User bidder = user("bidder", 500_000);
        Product product = product(seller);
        UUID bidId = bidService.placeBid(product.getId(), bidder.getId(), 30_000).bid().getId();
        mockMvc.perform(delete("/api/bids/{id}", bidId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(bidder)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/bids/{id}", bidId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(bidder)))
                .andExpect(status().isConflict());
    }

    @Test
    void 입찰_응답에_사용_가능_크레딧이_담긴다() {
        User seller = user("seller", 0);
        User bidder = user("bidder", 500_000);
        Product product = product(seller);

        long availableCredit = bidService.placeBid(product.getId(), bidder.getId(), 30_000).availableCredit();

        assertThat(availableCredit).isEqualTo(470_000);
    }
}
