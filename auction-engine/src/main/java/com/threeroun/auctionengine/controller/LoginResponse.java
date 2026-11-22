package com.threeroun.auctionengine.controller;

import java.time.LocalDateTime;

// expiresAt도 마감 시각 등 다른 시각 필드와 같은 서버 시간대(한국 시각) 기준이다 (시간대 표시는 없다).
// 프론트(Navbar)가 이 값을 브라우저 로컬(한국) 시각으로 해석해서 로그인 만료를 판단한다.
public record LoginResponse(
        String accessToken,
        LocalDateTime expiresAt
) {
}
