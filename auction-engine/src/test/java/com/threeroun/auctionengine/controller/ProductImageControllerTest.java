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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 디스크에 쓰는 동작까지 검증하려고 테스트 전용 저장 경로(build 하위, git에 안 잡힘)로 돌린다.
@SpringBootTest(properties = "app.product-image-dir=build/test-product-images")
@AutoConfigureMockMvc
class ProductImageControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private User user() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(new User("user-" + suffix, "user-" + suffix + "@test.com", "hash"));
    }

    private String bearerToken(User user) {
        return "Bearer " + jwtService.issueToken(user.getId());
    }

    private Product product(User seller) {
        Product product = new Product("상품-" + UUID.randomUUID().toString().substring(0, 8), "설명",
                10000, 1000, seller, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        return productRepository.save(product);
    }

    @Test
    void 판매자_본인이_이미지를_업로드하면_imageUrl이_응답에_포함된다() throws Exception {
        User seller = user();
        Product product = product(seller);
        MockMultipartFile image = new MockMultipartFile("image", "photo.jpg", "image/jpeg", "fake-bytes".getBytes());

        String response = mockMvc.perform(multipart("/api/products/{id}/image", product.getId())
                        .file(image)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(seller)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> body = objectMapper.readValue(response, Map.class);
        assertThat((String) body.get("imageUrl")).startsWith("/images/").endsWith(".jpg");
    }

    @Test
    void 판매자가_아니면_이미지_업로드가_403으로_거절된다() throws Exception {
        User seller = user();
        User other = user();
        Product product = product(seller);
        MockMultipartFile image = new MockMultipartFile("image", "photo.jpg", "image/jpeg", "fake-bytes".getBytes());

        mockMvc.perform(multipart("/api/products/{id}/image", product.getId())
                        .file(image)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 토큰_없이_이미지_업로드하면_401을_반환한다() throws Exception {
        Product product = product(user());
        MockMultipartFile image = new MockMultipartFile("image", "photo.jpg", "image/jpeg", "fake-bytes".getBytes());

        mockMvc.perform(multipart("/api/products/{id}/image", product.getId()).file(image))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 지원하지_않는_파일형식이면_400을_반환한다() throws Exception {
        User seller = user();
        Product product = product(seller);
        MockMultipartFile file = new MockMultipartFile("image", "doc.txt", "text/plain", "not-an-image".getBytes());

        mockMvc.perform(multipart("/api/products/{id}/image", product.getId())
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(seller)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 존재하지_않는_상품에_업로드하면_404를_반환한다() throws Exception {
        User seller = user();
        MockMultipartFile image = new MockMultipartFile("image", "photo.jpg", "image/jpeg", "fake-bytes".getBytes());

        mockMvc.perform(multipart("/api/products/{id}/image", UUID.randomUUID())
                        .file(image)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(seller)))
                .andExpect(status().isNotFound());
    }
}
