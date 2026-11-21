package com.threeroun.auctionengine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.repository.UserRepository;
import com.threeroun.auctionengine.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
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
    @Autowired
    private JwtService jwtService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private User user(int credit) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(new User("user-" + suffix, "user-" + suffix + "@test.com", "secret-hash", credit));
    }

    private String bearerToken(User user) {
        return "Bearer " + jwtService.issueToken(user.getId());
    }

    // 이슈 #149 - 사용자 조회는 로그인한 사용자만 할 수 있고, 본인에게는 전체 정보를, 타인에게는 공개 정보만 준다.
    @Test
    void 본인_프로필을_조회하면_이메일과_크레딧까지_전체가_나오고_비밀번호_해시는_안_나온다() throws Exception {
        User user = user(30_000);

        String response = mockMvc.perform(get("/api/users/{id}", user.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> body = objectMapper.readValue(response, Map.class);
        assertThat(body.get("id")).isEqualTo(user.getId().toString());
        assertThat(body.get("username")).isEqualTo(user.getUsername());
        assertThat(body.get("email")).isEqualTo(user.getEmail());
        assertThat(body.get("credit")).isEqualTo(30_000);
        assertThat(body.get("availableCredit")).isEqualTo(30_000); // 진행중 경매에 묶인 게 없으면 보유와 같다
        assertThat(body).containsKey("createdAt");
        assertThat(body).doesNotContainKey("passwordHash");
        assertThat(body).doesNotContainKey("password_hash");
    }

    @Test
    void 타인_프로필을_조회하면_id와_닉네임만_나오고_이메일과_크레딧은_나오지_않는다() throws Exception {
        User me = user(0);
        User other = user(77_000);

        String response = mockMvc.perform(get("/api/users/{id}", other.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(me)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> body = objectMapper.readValue(response, Map.class);
        assertThat(body.get("id")).isEqualTo(other.getId().toString());
        assertThat(body.get("username")).isEqualTo(other.getUsername());
        // 값이 null이 아니라 필드 자체가 없어야 한다 (타인의 이메일/크레딧이 어떤 형태로도 내려가면 안 된다)
        assertThat(body).containsOnlyKeys("id", "username");
        assertThat(response).doesNotContain(other.getEmail());
    }

    @Test
    void 토큰_없이_사용자를_조회하면_401을_반환한다() throws Exception {
        User user = user(0);

        mockMvc.perform(get("/api/users/{id}", user.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 유효하지_않은_토큰으로_사용자를_조회하면_401을_반환한다() throws Exception {
        User user = user(0);

        mockMvc.perform(get("/api/users/{id}", user.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-valid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 존재하지_않는_사용자를_조회하면_404를_반환한다() throws Exception {
        User me = user(0);

        mockMvc.perform(get("/api/users/{id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(me)))
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
