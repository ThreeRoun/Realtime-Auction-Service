package com.threeroun.auctionengine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeroun.auctionengine.domain.Bid;
import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.repository.BidRepository;
import com.threeroun.auctionengine.repository.ProductRepository;
import com.threeroun.auctionengine.repository.UserRepository;
import com.threeroun.auctionengine.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 이슈 #183 - 판매자의 경매 취소 (POST /api/products/{id}/cancel)
@SpringBootTest
@AutoConfigureMockMvc
class AuctionCancelControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BidRepository bidRepository;
    @Autowired
    private JwtService jwtService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private User user() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User("user-" + suffix, "user-" + suffix + "@test.com", "hash");
        user.addCredit(500_000);
        return userRepository.save(user);
    }

    private String bearerToken(User user) {
        return "Bearer " + jwtService.issueToken(user.getId());
    }

    private Product product(User seller, ProductStatus status, LocalDateTime endAt) {
        Product product = new Product("상품-" + UUID.randomUUID().toString().substring(0, 8), "설명",
                10000, 1000, seller, LocalDateTime.now().minusHours(1), endAt);
        product.setStatus(status);
        return productRepository.save(product);
    }

    private ProductStatus statusOf(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStatus();
    }

    @Test
    void 판매자가_입찰_없는_진행중_경매를_취소하면_CANCELLED가_된다() throws Exception {
        User seller = user();
        Product product = product(seller, ProductStatus.IN_PROGRESS, LocalDateTime.now().plusHours(1));

        mockMvc.perform(post("/api/products/{id}/cancel", product.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(statusOf(product)).isEqualTo(ProductStatus.CANCELLED);
    }

    @Test
    void 등록대기_경매도_취소할_수_있다() throws Exception {
        User seller = user();
        Product product = product(seller, ProductStatus.PENDING, LocalDateTime.now().plusDays(1));

        mockMvc.perform(post("/api/products/{id}/cancel", product.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(seller)))
                .andExpect(status().isOk());

        assertThat(statusOf(product)).isEqualTo(ProductStatus.CANCELLED);
    }

    @Test
    void 무효_입찰만_있으면_취소할_수_있다() throws Exception {
        User seller = user();
        Product product = product(seller, ProductStatus.IN_PROGRESS, LocalDateTime.now().plusHours(1));
        bidRepository.save(new Bid(product, user(), 9000)); // markValid() 안 함 = 진 입찰

        mockMvc.perform(post("/api/products/{id}/cancel", product.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(seller)))
                .andExpect(status().isOk());
    }

    @Test
    void 유효_입찰이_있으면_409_AUCTION_HAS_BIDS로_거절된다() throws Exception {
        User seller = user();
        Product product = product(seller, ProductStatus.IN_PROGRESS, LocalDateTime.now().plusHours(1));
        Bid bid = new Bid(product, user(), 11000);
        bid.markValid();
        bidRepository.save(bid);

        mockMvc.perform(post("/api/products/{id}/cancel", product.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(seller)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_HAS_BIDS"));

        assertThat(statusOf(product)).isEqualTo(ProductStatus.IN_PROGRESS);
    }

    @Test
    void 판매자가_아니면_403_NOT_PRODUCT_OWNER로_거절된다() throws Exception {
        User seller = user();
        Product product = product(seller, ProductStatus.IN_PROGRESS, LocalDateTime.now().plusHours(1));

        mockMvc.perform(post("/api/products/{id}/cancel", product.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_PRODUCT_OWNER"));

        assertThat(statusOf(product)).isEqualTo(ProductStatus.IN_PROGRESS);
    }

    @Test
    void 이미_종료된_경매는_409_AUCTION_ALREADY_ENDED로_거절된다() throws Exception {
        User seller = user();
        Product product = product(seller, ProductStatus.UNSOLD, LocalDateTime.now().minusHours(1));

        mockMvc.perform(post("/api/products/{id}/cancel", product.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(seller)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_ALREADY_ENDED"));
    }

    @Test
    void 마감시각이_지났는데_아직_마감처리_전인_경매도_취소할_수_없다() throws Exception {
        User seller = user();
        Product product = product(seller, ProductStatus.IN_PROGRESS, LocalDateTime.now().minusSeconds(1));

        mockMvc.perform(post("/api/products/{id}/cancel", product.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(seller)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_ALREADY_ENDED"));
    }

    @Test
    void 이미_취소된_경매를_다시_취소하면_409로_거절된다() throws Exception {
        User seller = user();
        Product product = product(seller, ProductStatus.CANCELLED, LocalDateTime.now().plusHours(1));

        mockMvc.perform(post("/api/products/{id}/cancel", product.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(seller)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_ALREADY_ENDED"));
    }

    @Test
    void 토큰_없이_취소하면_401을_반환한다() throws Exception {
        Product product = product(user(), ProductStatus.IN_PROGRESS, LocalDateTime.now().plusHours(1));

        mockMvc.perform(post("/api/products/{id}/cancel", product.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 존재하지_않는_상품을_취소하면_404를_반환한다() throws Exception {
        mockMvc.perform(post("/api/products/{id}/cancel", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user())))
                .andExpect(status().isNotFound());
    }

    @Test
    void 취소된_경매에는_입찰할_수_없다() throws Exception {
        User seller = user();
        Product product = product(seller, ProductStatus.IN_PROGRESS, LocalDateTime.now().plusHours(1));
        mockMvc.perform(post("/api/products/{id}/cancel", product.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(seller)))
                .andExpect(status().isOk());

        String bid = objectMapper.writeValueAsString(Map.of("productId", product.getId().toString(), "amount", 11000));
        mockMvc.perform(post("/api/bids")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bid))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_IN_PROGRESS"));
    }

    @Test
    void status_ended로_조회하면_낙찰_유찰_완료_취소된_경매만_반환한다() throws Exception {
        User seller = user();
        Product inProgress = product(seller, ProductStatus.IN_PROGRESS, LocalDateTime.now().plusHours(1));
        Product sold = product(seller, ProductStatus.SOLD, LocalDateTime.now().minusHours(1));
        Product unsold = product(seller, ProductStatus.UNSOLD, LocalDateTime.now().minusHours(1));
        Product cancelled = product(seller, ProductStatus.CANCELLED, LocalDateTime.now().plusHours(1));

        String body = mockMvc.perform(get("/api/products").param("status", "ended"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> products = objectMapper.readValue(body, List.class);
        List<String> ids = products.stream().map(p -> (String) p.get("id")).toList();
        assertThat(ids).contains(sold.getId().toString(), unsold.getId().toString(), cancelled.getId().toString());
        assertThat(ids).doesNotContain(inProgress.getId().toString());
    }
}
