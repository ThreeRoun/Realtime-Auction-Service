package com.threeroun.auctionengine.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// k8s 롤링 업데이트 중 Pod가 잠깐 겹치는 상황(이슈 #100)에서 @Scheduled 작업(AuctionScheduler)이
// 같은 시점에 여러 인스턴스에서 중복 실행되지 않도록 DB(shedlock 테이블, V4 마이그레이션) 기반
// 분산 락을 건다.
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(dataSource);
    }
}
