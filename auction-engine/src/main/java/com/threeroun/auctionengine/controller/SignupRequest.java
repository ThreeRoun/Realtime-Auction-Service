package com.threeroun.auctionengine.controller;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// initialCredit: 결제 연동이 없는 MVP라 별도 "충전" API가 없다. 가입 시 바로 크레딧을
// 받을 수 있게 하는 임시방편 (생략 시 0). 통합 단계에서 실제 결제/충전으로 교체될 값이다.
public record SignupRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 4) String password,
        @PositiveOrZero Integer initialCredit
) {
}
