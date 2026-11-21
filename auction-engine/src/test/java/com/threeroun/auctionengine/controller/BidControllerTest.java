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
        return userRepository.save(new User(role + "-" + suffix, role + "-" + suffix + "@test.com", "hash", 10_000_000));
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

    // 이슈 #148 - 프론트가 "왜 입찰이 안 됐는지"를 사용자에게 알려줄 수 있도록 응답에 사유를 싣는다.
    private Map<?, ?> bid(User bidder, Product product, int amount) throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "productId", product.getId().toString(),
                "amount", amount));
        String response = mockMvc.perform(post("/api/bids")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(bidder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(response, Map.class);
    }

    @Test
    void 최소_입찰가_미만으로_거절되면_사유와_다음_최소_입찰가를_알려준다() throws Exception {
        User seller = user("seller");
        User bidder = user("bidder");
        Product product = productRepository.save(inProgressProduct(seller));

        Map<?, ?> body = bid(bidder, product, STARTING_PRICE);

        assertThat(body.get("isValid")).isEqualTo(false);
        assertThat(body.get("reason")).isEqualTo("AMOUNT_TOO_LOW");
        assertThat(((Number) body.get("minNextBid")).intValue()).isEqualTo(STARTING_PRICE + BID_UNIT);
    }

    @Test
    void 유효한_입찰이면_사유는_없고_그_다음_최소_입찰가를_알려준다() throws Exception {
        User seller = user("seller");
        User bidder = user("bidder");
        Product product = productRepository.save(inProgressProduct(seller));

        Map<?, ?> body = bid(bidder, product, STARTING_PRICE + BID_UNIT);

        assertThat(body.get("isValid")).isEqualTo(true);
        assertThat(body.get("reason")).isNull();
        assertThat(((Number) body.get("minNextBid")).intValue()).isEqualTo(STARTING_PRICE + 2 * BID_UNIT);
    }

    // 프론트가 화면의 현재가로 "최소 입찰가"를 계산하면 틀리는 상황: 내가 화면을 본 사이 다른 사람이 먼저 올렸다.
    // 서버가 상품 row를 잠근 채로 계산한 값만이 이 순간의 정확한 최소 입찰가다.
    @Test
    void 다른_사람이_먼저_올려서_거절되면_올라간_가격_기준의_최소_입찰가를_알려준다() throws Exception {
        User seller = user("seller");
        User first = user("first");
        User late = user("late");
        Product product = productRepository.save(inProgressProduct(seller));

        bid(first, product, STARTING_PRICE + BID_UNIT);
        Map<?, ?> body = bid(late, product, STARTING_PRICE + BID_UNIT);

        assertThat(body.get("isValid")).isEqualTo(false);
        assertThat(body.get("reason")).isEqualTo("AMOUNT_TOO_LOW");
        assertThat(((Number) body.get("minNextBid")).intValue()).isEqualTo(STARTING_PRICE + 2 * BID_UNIT);
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

    // 이슈 #152 - 마감 처리는 스케줄러가 주기적으로 하므로, 마감 시각이 지났어도 상태가 아직 진행중인 구간이 있다.
    // 그 구간에 들어온 입찰은 상태가 아니라 마감 시각을 보고 거절해야 한다.
    @Test
    void 마감_시각이_지났으면_상태가_진행중이어도_409를_반환하고_가격은_그대로다() throws Exception {
        User seller = user("seller");
        User bidder = user("bidder");
        Product product = new Product("마감 시각이 지난 상품", "설명", STARTING_PRICE, BID_UNIT,
                seller, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusSeconds(1));
        product.setStatus(ProductStatus.IN_PROGRESS);
        productRepository.save(product);

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "productId", product.getId().toString(),
                "amount", 100_000));

        mockMvc.perform(post("/api/bids")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(bidder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict());

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getCurrentPrice()).isEqualTo(STARTING_PRICE);
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

    @Test
    void 판매자_본인이_입찰하면_403을_반환한다() throws Exception {
        User seller = user("seller");
        Product product = productRepository.save(inProgressProduct(seller));
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "productId", product.getId().toString(),
                "amount", STARTING_PRICE + BID_UNIT));

        mockMvc.perform(post("/api/bids")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isForbidden());
    }

    private Product inProgressProduct(User seller) {
        Product product = new Product("테스트 상품", "설명", STARTING_PRICE, BID_UNIT,
                seller, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        product.setStatus(ProductStatus.IN_PROGRESS);
        return product;
    }
}
