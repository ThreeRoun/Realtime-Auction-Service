package com.threeroun.auctionengine.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// #102는 "JWT_SECRET 환경변수 자체가 없는 경우"를 막는다(fail-fast). 근데 환경변수가
// *있어도* 그 값이 application.properties에 커밋된 로컬 개발용 기본값 그대로일 수 있다
// (예: Secret 만들 때 실수로 그 기본값을 복붙). 그 경우까지 운영에서 거부한다.
@Component
@Profile("prod")
public class JwtSecretGuard {

    // application.properties의 jwt.secret 기본값과 반드시 같은 문자열로 유지해야 한다.
    static final String KNOWN_DEFAULT_SECRET = "3eeaT2gxbFshjn5ZsRGwvAx2zndh+8vHJQRT2A4DOs0=";

    public JwtSecretGuard(@Value("${jwt.secret}") String jwtSecret) {
        if (KNOWN_DEFAULT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "운영 환경에서 레포에 공개된 기본 JWT 키를 그대로 쓰고 있습니다. "
                            + "JWT_SECRET을 새로 생성한 값으로 교체해주세요.");
        }
    }
}
