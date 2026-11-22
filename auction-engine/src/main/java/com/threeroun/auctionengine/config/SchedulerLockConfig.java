package com.threeroun.auctionengine.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.TimeZone;

// k8s 롤링 업데이트 중 Pod가 잠깐 겹치는 상황(이슈 #100)에서 @Scheduled 작업(AuctionScheduler)이
// 같은 시점에 여러 인스턴스에서 중복 실행되지 않도록 DB(shedlock 테이블, V4 마이그레이션) 기반
// 분산 락을 건다.
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
public class SchedulerLockConfig {

    // 락 시각(lock_until 등)은 서버 시간대가 아니라 항상 UTC로 저장한다 (이슈 #152).
    // 기본 설정은 서버(JVM) 시간대로 저장하는데, 서버 시간대를 한국 시각으로 고정하면(AuctionEngineApplication)
    // 락이 한국 시각으로 쓰인다. 그러면 배포 중 롤백처럼 "시간대가 다른 버전"이 같은 테이블을 보는 순간,
    // 한국 시각으로 쓴 락이 UTC 서버에게는 9시간 뒤까지 잠긴 락으로 보여서 마감 처리 스케줄러가 최대 9시간 멈춘다.
    // UTC로 고정하면 시간대 변경 전(UTC)에 쓰인 기존 락 행과도 같은 기준이라 어느 방향으로 오가도 안전하다.
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTimeZone(TimeZone.getTimeZone("UTC"))
                        .build());
    }
}
