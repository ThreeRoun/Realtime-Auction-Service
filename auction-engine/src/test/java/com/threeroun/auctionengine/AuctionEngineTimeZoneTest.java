package com.threeroun.auctionengine;

import org.junit.jupiter.api.Test;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 이슈 #152 - 서버가 UTC로 떠도 애플리케이션의 시각(LocalDateTime.now())이 한국 시각 기준이 되는지 확인한다.
// 이게 깨지면 사용자가 입력한 한국 시각 마감이 9시간 늦게 처리된다 (화면은 마감인데 입찰이 받아들여짐).
// JVM 기본 시간대는 프로세스 전체에 영향을 주므로, 다른 테스트에 새지 않게 항상 원래 값으로 되돌린다.
class AuctionEngineTimeZoneTest {

    @Test
    void 서버가_UTC로_떠도_애플리케이션_시각은_한국_시각_기준이_된다() {
        TimeZone original = TimeZone.getDefault();
        try {
            // 운영 컨테이너의 기본 상태(UTC)를 흉내 낸다
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

            AuctionEngineApplication.applyDefaultTimeZone(AuctionEngineApplication.DEFAULT_TIME_ZONE);

            assertThat(ZoneId.systemDefault()).isEqualTo(ZoneId.of("Asia/Seoul"));
            // LocalDateTime.now()가 UTC가 아니라 한국 시각(UTC+9)을 가리킨다
            Duration gapFromUtc = Duration.between(LocalDateTime.now(ZoneOffset.UTC), LocalDateTime.now());
            assertThat(gapFromUtc).isBetween(Duration.ofHours(9).minusMinutes(1), Duration.ofHours(9).plusMinutes(1));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void 잘못된_시간대_값이면_조용히_GMT로_빠지지_않고_실패한다() {
        TimeZone original = TimeZone.getDefault();
        try {
            assertThatThrownBy(() -> AuctionEngineApplication.applyDefaultTimeZone("Asia/Seoull"))
                    .isInstanceOf(DateTimeException.class);

            // 실패했으면 기존 시간대가 그대로여야 한다
            assertThat(TimeZone.getDefault()).isEqualTo(original);
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
