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
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.TimeZone;
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
    @Value("${jwt.expiration-minutes}")
    private long expirationMinutes;
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

    // 프론트(Navbar)는 expiresAt을 시간대 표시 없이 브라우저 로컬(한국) 시각으로 해석한다 (이슈 #152).
    // 그래서 이 값은 마감 시각 등 다른 시각 필드와 같은 서버 시간대(한국 시각) 기준이어야 한다.
    // 누군가 다시 UTC 등으로 바꾸면 프론트의 로그인 만료 판단이 9시간 어긋난다.
    @Test
    void 로그인_만료_시각은_다른_시각_필드와_같은_서버_시간대_기준이다() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            // 운영에서 main()이 적용하는 서버 시간대(한국 시각)를 흉내 낸다
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
            User user = user("secret1234");
            String body = """
                    { "username": "%s", "password": "secret1234" }
                    """.formatted(user.getUsername());

            String response = mockMvc.perform(post("/api/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            LocalDateTime expiresAt = LocalDateTime.parse((String) objectMapper.readValue(response, Map.class)
                    .get("expiresAt"));
            LocalDateTime expectedSeoul = LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusMinutes(expirationMinutes);
            assertThat(Duration.between(expectedSeoul, expiresAt).abs()).isLessThan(Duration.ofMinutes(2));
        } finally {
            TimeZone.setDefault(original);
        }
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
