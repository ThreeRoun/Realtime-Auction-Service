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
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
