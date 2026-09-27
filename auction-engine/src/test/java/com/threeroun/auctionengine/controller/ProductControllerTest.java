package com.threeroun.auctionengine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeroun.auctionengine.domain.Product;
import com.threeroun.auctionengine.domain.ProductStatus;
import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.repository.ProductRepository;
import com.threeroun.auctionengine.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private User seller() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(new User("seller-" + suffix, "seller-" + suffix + "@test.com", "hash"));
    }

    private Product product(User seller, ProductStatus status, LocalDateTime endAt) {
        Product product = new Product("상품-" + UUID.randomUUID().toString().substring(0, 8), "설명",
                10000, 1000, seller, LocalDateTime.now().minusHours(1), endAt);
        product.setStatus(status);
        return productRepository.save(product);
    }

    @Test
    void 기본_조회는_진행중_상품만_마감임박순으로_반환한다() throws Exception {
        User seller = seller();
        Product soonToEnd = product(seller, ProductStatus.IN_PROGRESS, LocalDateTime.now().plusMinutes(10));
        Product laterEnd = product(seller, ProductStatus.EXTENDED, LocalDateTime.now().plusHours(5));
        Product pending = product(seller, ProductStatus.PENDING, LocalDateTime.now().plusDays(1));
        Product completed = product(seller, ProductStatus.COMPLETED, LocalDateTime.now().minusHours(1));

        String body = mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> products = objectMapper.readValue(body, List.class);
        List<String> ids = products.stream().map(p -> (String) p.get("id")).toList();

        assertThat(ids).contains(soonToEnd.getId().toString(), laterEnd.getId().toString());
        assertThat(ids).doesNotContain(pending.getId().toString(), completed.getId().toString());
        // 마감 임박순(end_at asc) 정렬 확인: soonToEnd가 laterEnd보다 앞에 와야 한다
        assertThat(ids.indexOf(soonToEnd.getId().toString()))
                .isLessThan(ids.indexOf(laterEnd.getId().toString()));
    }

    @Test
    void status_all이면_모든_상태의_상품을_반환한다() throws Exception {
        User seller = seller();
        Product pending = product(seller, ProductStatus.PENDING, LocalDateTime.now().plusDays(1));

        String body = mockMvc.perform(get("/api/products").param("status", "all"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> products = objectMapper.readValue(body, List.class);
        List<String> ids = products.stream().map(p -> (String) p.get("id")).toList();
        assertThat(ids).contains(pending.getId().toString());
    }

    @Test
    void 알수없는_status값이면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/products").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 시작시각이_지난_상품을_등록하면_바로_진행중_상태로_생성된다() throws Exception {
        User seller = seller();
        Map<String, Object> request = Map.of(
                "title", "새 상품",
                "description", "설명",
                "startingPrice", 10000,
                "bidUnit", 1000,
                "sellerId", seller.getId().toString(),
                "startAt", LocalDateTime.now().minusMinutes(1).toString(),
                "endAt", LocalDateTime.now().plusHours(1).toString());

        String response = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> body = objectMapper.readValue(response, Map.class);
        assertThat(body.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(body.get("currentPrice")).isEqualTo(10000);
    }

    @Test
    void startAt을_생략하면_지금_시각으로_채워져서_바로_진행중으로_생성된다() throws Exception {
        User seller = seller();
        Map<String, Object> request = Map.of(
                "title", "startAt 생략 상품",
                "description", "설명",
                "startingPrice", 10000,
                "bidUnit", 1000,
                "sellerId", seller.getId().toString(),
                "endAt", LocalDateTime.now().plusHours(1).toString());

        String response = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> body = objectMapper.readValue(response, Map.class);
        assertThat(body.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(body.get("startAt")).isNotNull();
    }

    @Test
    void 시작시각이_미래인_상품을_등록하면_등록대기_상태로_생성된다() throws Exception {
        User seller = seller();
        Map<String, Object> request = Map.of(
                "title", "미래 상품",
                "description", "설명",
                "startingPrice", 10000,
                "bidUnit", 1000,
                "sellerId", seller.getId().toString(),
                "startAt", LocalDateTime.now().plusDays(1).toString(),
                "endAt", LocalDateTime.now().plusDays(2).toString());

        String response = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> body = objectMapper.readValue(response, Map.class);
        assertThat(body.get("status")).isEqualTo("PENDING");
    }

    @Test
    void 존재하지_않는_판매자면_404를_반환한다() throws Exception {
        Map<String, Object> request = Map.of(
                "title", "상품",
                "startingPrice", 10000,
                "bidUnit", 1000,
                "sellerId", UUID.randomUUID().toString(),
                "startAt", LocalDateTime.now().toString(),
                "endAt", LocalDateTime.now().plusHours(1).toString());

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 마감시각이_시작시각보다_빠르면_400을_반환한다() throws Exception {
        User seller = seller();
        Map<String, Object> request = Map.of(
                "title", "상품",
                "startingPrice", 10000,
                "bidUnit", 1000,
                "sellerId", seller.getId().toString(),
                "startAt", LocalDateTime.now().toString(),
                "endAt", LocalDateTime.now().minusHours(1).toString());

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 필수값이_없으면_400을_반환한다() throws Exception {
        Map<String, Object> request = Map.of("title", "상품");

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
