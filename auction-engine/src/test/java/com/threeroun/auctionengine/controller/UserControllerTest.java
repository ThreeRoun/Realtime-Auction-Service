package com.threeroun.auctionengine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 사용자_프로필_조회에_성공하고_비밀번호_해시는_안_나온다() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(new User("user-" + suffix, "user-" + suffix + "@test.com", "secret-hash"));

        String response = mockMvc.perform(get("/api/users/{id}", user.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> body = objectMapper.readValue(response, Map.class);
        assertThat(body.get("id")).isEqualTo(user.getId().toString());
        assertThat(body.get("username")).isEqualTo(user.getUsername());
        assertThat(body.get("credit")).isEqualTo(0);
        assertThat(body).doesNotContainKey("passwordHash");
        assertThat(body).doesNotContainKey("password_hash");
    }

    @Test
    void 존재하지_않는_사용자를_조회하면_404를_반환한다() throws Exception {
        mockMvc.perform(get("/api/users/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 회원가입에_성공하면_초기_크레딧이_반영되고_비밀번호_해시는_안_나온다() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String body = """
                { "username": "new-%s", "email": "new-%s@test.com", "password": "secret1234", "initialCredit": 50000 }
                """.formatted(suffix, suffix);

        String response = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> responseBody = objectMapper.readValue(response, Map.class);
        assertThat(responseBody.get("username")).isEqualTo("new-" + suffix);
        assertThat(responseBody.get("credit")).isEqualTo(50000);
        assertThat(responseBody).doesNotContainKey("passwordHash");

        User saved = userRepository.findById(UUID.fromString((String) responseBody.get("id"))).orElseThrow();
        assertThat(saved.getPasswordHash()).isNotEqualTo("secret1234"); // 평문으로 저장되면 안 됨
    }

    @Test
    void initialCredit을_생략하면_크레딧_0으로_가입된다() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String body = """
                { "username": "nocr-%s", "email": "nocr-%s@test.com", "password": "secret1234" }
                """.formatted(suffix, suffix);

        String response = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> responseBody = objectMapper.readValue(response, Map.class);
        assertThat(responseBody.get("credit")).isEqualTo(0);
    }

    @Test
    void 이미_있는_username으로_가입하면_409를_반환한다() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userRepository.save(new User("dup-" + suffix, "dup-" + suffix + "@test.com", "hash"));

        String body = """
                { "username": "dup-%s", "email": "other-%s@test.com", "password": "secret1234" }
                """.formatted(suffix, suffix);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void 형식이_잘못된_요청은_400을_반환한다() throws Exception {
        String body = """
                { "username": "", "email": "not-an-email", "password": "a" }
                """;

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
