package com.threeroun.auctionengine.config;

import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// 이슈 #142 - Redis 명령 타임아웃을 짧게 건 설정(application.properties)이 실제 Redis 클라이언트에
// 적용됐는지 확인한다. 기본값(명령 1분)으로 돌아가면 Redis 장애 때 입찰 응답이 다시 1분씩 멈춘다.
// Redis를 실제로 멈추고 재는 시간 측정은 자동화하지 않았다 (CI 환경에서 불안정해서) -
// 대신 그 시간을 결정하는 설정값이 의도대로 들어갔는지를 고정한다.
@SpringBootTest
class RedisTimeoutConfigTest {

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Test
    void Redis_명령_타임아웃은_Lettuce_기본값_1분이_아니라_짧게_설정된다() {
        LettuceConnectionFactory lettuce = (LettuceConnectionFactory) connectionFactory;

        assertThat(lettuce.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void Redis_연결_타임아웃도_짧게_설정된다() {
        LettuceConnectionFactory lettuce = (LettuceConnectionFactory) connectionFactory;

        SocketOptions socketOptions = lettuce.getClientConfiguration().getClientOptions().orElseThrow()
                .getSocketOptions();
        assertThat(socketOptions.getConnectTimeout()).isEqualTo(Duration.ofSeconds(1));
    }
}
