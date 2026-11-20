package com.threeroun.auctionengine.controller;

import java.time.LocalDateTime;

// expiresAt은 이 API에서 유일하게 "UTC 기준" 시각이다 (시간대 표시는 없다). 다른 시각 필드(마감 시각 등)는
// 서버 기본 시간대인 한국 시각이다. 프론트가 이 값에 "Z"를 붙여 UTC로 해석하므로 바꾸면 로그인 만료 표시가 어긋난다.
public record LoginResponse(
        String accessToken,
        LocalDateTime expiresAt
) {
}
