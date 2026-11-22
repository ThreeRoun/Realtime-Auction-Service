package com.threeroun.auctionengine.controller;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 가입 크레딧은 요청으로 받지 않고 서버가 고정값을 준다(UserController.SIGNUP_CREDIT).
// 예전 클라이언트가 보내는 initialCredit 필드는 알 수 없는 필드로 무시된다.
public record SignupRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 4) String password
) {
}
