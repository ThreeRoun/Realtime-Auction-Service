package com.threeroun.auctionengine.controller;

import com.threeroun.auctionengine.domain.User;
import com.threeroun.auctionengine.repository.UserRepository;
import com.threeroun.auctionengine.service.DuplicateUserException;
import com.threeroun.auctionengine.service.UserNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.threeroun.auctionengine.security.JwtAuthenticationFilter.AUTH_USER_ID_ATTRIBUTE;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // 로그인한 사용자만 조회할 수 있다 (JwtAuthenticationFilter). 본인 조회면 이메일/크레딧까지 전체를,
    // 타인 조회면 공개해도 되는 id와 닉네임만 돌려준다 (이슈 #149). 사용자 id는 입찰 이력이나 상품 응답으로
    // 누구나 알 수 있어서, 이 API가 전체 정보를 내려주면 타인의 이메일과 크레딧이 그대로 노출된다.
    @GetMapping("/{id}")
    public UserResponse detail(@PathVariable UUID id,
                                @RequestAttribute(AUTH_USER_ID_ATTRIBUTE) UUID requesterId) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        return requesterId.equals(id) ? UserResponse.from(user) : UserResponse.publicView(user);
    }

    // 회원가입. 로그인/토큰 발급은 아직 없다 - 여기서 만든 id를 다른 API(상품 등록의
    // sellerId, 입찰의 bidderId)에 그대로 값으로 넘기는 현재 MVP 방식과 바로 연결된다.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse signup(@Valid @RequestBody SignupRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateUserException("username", request.username());
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateUserException("email", request.email());
        }

        String passwordHash = passwordEncoder.encode(request.password());
        int initialCredit = request.initialCredit() != null ? request.initialCredit() : 0;
        User user = new User(request.username(), request.email(), passwordHash, initialCredit);
        return UserResponse.from(userRepository.save(user));
    }
}
