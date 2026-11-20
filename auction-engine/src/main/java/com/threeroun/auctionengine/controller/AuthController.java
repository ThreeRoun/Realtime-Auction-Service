package com.threeroun.auctionengine.controller;

import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.repository.UserRepository;
import com.threeroun.auctionengine.service.InvalidCredentialsException;
import com.threeroun.auctionengine.service.JwtService;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@RestController
@RequestMapping("/api/login")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    // username이 존재하지 않는 경우와 비밀번호가 틀린 경우를 구분하지 않고 똑같이 401 처리한다
    // (존재하는 username인지 아닌지를 외부에 노출하지 않기 위함).
    @PostMapping
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        String token = jwtService.issueToken(user.getId());
        // expiresAt은 서버 기본 시간대(한국 시각, AuctionEngineApplication 참고)와 무관하게 "UTC 기준"으로 고정한다.
        // 프론트(Navbar)가 이 값에 "Z"를 붙여 UTC로 해석해서 로그인 만료를 판단하기 때문이다 (PR #93).
        // 서버 시간대를 한국 시각으로 바꾸면서 이 값까지 한국 시각이 되면, 프론트가 만료를 9시간 늦게 계산해서
        // 이미 만료된 토큰인데도 로그인 상태로 보이는 문제가 생긴다.
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(jwtService.getExpirationMinutes());
        return new LoginResponse(token, expiresAt);
    }
}
