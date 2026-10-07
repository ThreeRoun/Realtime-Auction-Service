package com.threeroun.auctionengine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.repository.ProductRepository;
import com.threeroun.auctionengine.repository.UserRepository;
import com.threeroun.auctionengine.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BidControllerTest {

    private static final int STARTING_PRICE = 10_000;
    private static final int BID_UNIT = 1_000;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private User user(String role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(new User(role + "-" + suffix, role + "-" + suffix + "@test.com", "hash"));
    }

    private String bearerToken(User user) {
        return "Bearer " + jwtService.issueToken(user.getId());
    }

    @Test
    void 유효한_입찰이면_201과_isValid_true를_반환한다() throws Exception {
        User seller = user("seller");
        User bidder = user("bidder");
        Product product = productRepository.save(inProgressProduct(seller));

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "productId", product.getId().toString(),
                "amount", STARTING_PRICE + BID_UNIT));

        String response = mockMvc.perform(post("/api/bids")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(bidder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> body = objectMapper.readValue(response, Map.class);
        assertThat(body.get("isValid")).isEqualTo(true);
        assertThat(body.get("productId")).isEqualTo(product.getId().toString());
        assertThat(body.get("bidderId")).isEqualTo(bidder.getId().toString());
    }

    @Test
    void 최소_입찰가_미만이면_201이지만_isValid_false를_반환한다() throws Exception {
        User seller = user("seller");
        User bidder = user("bidder");
        Product product = productRepository.save(inProgressProduct(seller));

        // 최소 입찰가(현재가+bid_unit) 미만 금액
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "productId", product.getId().toString(),
                "amount", STARTING_PRICE));

        String response = mockMvc.perform(post("/api/bids")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(bidder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> body = objectMapper.readValue(response, Map.class);
        assertThat(body.get("isValid")).isEqualTo(false);
    }

    @Test
    void 존재하지_않는_상품이면_404를_반환한다() throws Exception {
        User bidder = user("bidder");
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "productId", UUID.randomUUID().toString(),
                "amount", 100_000));

        mockMvc.perform(post("/api/bids")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(bidder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isNotFound());
    }

    @Test
    void 진행중이_아닌_상품이면_409를_반환한다() throws Exception {
        User seller = user("seller");
        User bidder = user("bidder");
        Product product = new Product("마감된 상품", "설명", STARTING_PRICE, BID_UNIT,
                seller, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1));
        product.setStatus(ProductStatus.COMPLETED);
        productRepository.save(product);

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "productId", product.getId().toString(),
                "amount", 100_000));

        mockMvc.perform(post("/api/bids")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(bidder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict());
    }

    @Test
    void amount가_없으면_400을_반환한다() throws Exception {
        User seller = user("seller");
        User bidder = user("bidder");
        Product product = productRepository.save(inProgressProduct(seller));

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "productId", product.getId().toString()));

        mockMvc.perform(post("/api/bids")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(bidder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void productId가_없으면_400을_반환한다() throws Exception {
        User bidder = user("bidder");
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "amount", 100_000));

        mockMvc.perform(post("/api/bids")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(bidder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 토큰_없이_요청하면_401을_반환한다() throws Exception {
        User seller = user("seller");
        User bidder = user("bidder");
        Product product = productRepository.save(inProgressProduct(seller));
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "productId", product.getId().toString(),
                "amount", STARTING_PRICE + BID_UNIT));

        mockMvc.perform(post("/api/bids")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 유효하지_않은_토큰이면_401을_반환한다() throws Exception {
        User seller = user("seller");
        Product product = productRepository.save(inProgressProduct(seller));
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "productId", product.getId().toString(),
                "amount", STARTING_PRICE + BID_UNIT));

        mockMvc.perform(post("/api/bids")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized());
    }

    private Product inProgressProduct(User seller) {
        Product product = new Product("테스트 상품", "설명", STARTING_PRICE, BID_UNIT,
                seller, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        product.setStatus(ProductStatus.IN_PROGRESS);
        return product;
    }
}
