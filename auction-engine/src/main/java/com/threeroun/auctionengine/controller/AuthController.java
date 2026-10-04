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
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(jwtService.getExpirationMinutes());
        return new LoginResponse(token, expiresAt);
    }
}
