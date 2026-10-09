package com.threeroun.auctionengine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Value("${jwt.secret}")
    private String jwtSecret;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private User user(String rawPassword) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(new User("login-" + suffix, "login-" + suffix + "@test.com",
                passwordEncoder.encode(rawPassword)));
    }

    @Test
    void 로그인에_성공하면_본인_id가_담긴_토큰을_받는다() throws Exception {
        User user = user("secret1234");
        String body = """
                { "username": "%s", "password": "secret1234" }
                """.formatted(user.getUsername());

        String response = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> responseBody = objectMapper.readValue(response, Map.class);
        String token = (String) responseBody.get("accessToken");
        assertThat(token).isNotBlank();
        assertThat(responseBody.get("expiresAt")).isNotNull();

        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
        String subject = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload().getSubject();
        assertThat(subject).isEqualTo(user.getId().toString());
    }

    @Test
    void 비밀번호가_틀리면_401을_반환한다() throws Exception {
        User user = user("secret1234");
        String body = """
                { "username": "%s", "password": "wrong-password" }
                """.formatted(user.getUsername());

        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 존재하지_않는_username이면_401을_반환한다() throws Exception {
        String body = """
                { "username": "no-such-user", "password": "secret1234" }
                """;

        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 형식이_잘못된_요청은_400을_반환한다() throws Exception {
        String body = """
                { "username": "", "password": "" }
                """;

        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
