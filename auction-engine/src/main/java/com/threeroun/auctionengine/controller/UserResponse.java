package com.threeroun.auctionengine.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.threeroun.auctionengine.domain.User;

import java.time.LocalDateTime;
import java.util.UUID;

// passwordHash는 응답에 절대 포함하지 않는다 (인증 체계가 아직 없어도, 해시값을
// 외부에 노출할 이유는 없음).
//
// 타인에게 보여줄 때는 id와 username만 채우고 나머지(email, credit, createdAt)는 null로 둔다 (이슈 #149).
// NON_NULL이라 null인 필드는 JSON에서 키 자체가 빠지므로, "값이 null"이 아니라 "필드가 없다"로 내려간다.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserResponse(
        UUID id,
        String username,
        String email,
        Integer credit,
        Long availableCredit,
        LocalDateTime createdAt
) {
    // 본인에게 보여주는 전체 정보 (회원가입 응답, 내 정보 조회).
    // credit은 보유 크레딧, availableCredit은 거기서 진행중 경매에 묶인 금액을 뺀 값이다.
    public static UserResponse from(User user, long availableCredit) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getCredit(),
                availableCredit,
                user.getCreatedAt());
    }

    // 타인에게 보여줘도 되는 정보만: id와 닉네임
    public static UserResponse publicView(User user) {
        return new UserResponse(user.getId(), user.getUsername(), null, null, null, null);
    }
}
