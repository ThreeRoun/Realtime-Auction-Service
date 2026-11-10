package com.threeroun.auctionengine.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class JwtSecretGuardTest {

    @Test
    void 레포에_공개된_기본_JWT_키를_그대로_쓰면_기동을_거부한다() {
        assertThatThrownBy(() -> new JwtSecretGuard(JwtSecretGuard.KNOWN_DEFAULT_SECRET))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 다른_값이면_정상적으로_생성된다() {
        assertThatNoException().isThrownBy(() -> new JwtSecretGuard("이건-다른-랜덤-키"));
    }
}
