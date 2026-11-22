package com.threeroun.auctionengine.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 이슈 #152 - 서버 시간대 변경 전에 기록된(한국 시각으로 저장된) 락이 남아 있으면 UTC 기준으로는 "9시간 뒤까지
// 잠긴 락"으로 읽혀 마감 처리 스케줄러가 멈춘다. 그런 락만 골라 지우고 정상 락은 그대로 두는지 확인한다.
@SpringBootTest
class StaleSchedulerLockCleanerTest {

    @Autowired
    private StaleSchedulerLockCleaner cleaner;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void insertLock(String name, String lockUntilOffsetFromUtcNow) {
        jdbcTemplate.update(
                "insert into shedlock(name, lock_until, locked_at, locked_by) values (?, "
                        + "(now() at time zone 'utc') + cast(? as interval), now() at time zone 'utc', 'test')",
                name, lockUntilOffsetFromUtcNow);
    }

    private int count(String name) {
        return jdbcTemplate.queryForObject("select count(*) from shedlock where name = ?", Integer.class, name);
    }

    @Test
    void 지금보다_9시간_뒤로_기록된_락은_지우고_정상_락은_그대로_둔다() {
        String stale = "stale-lock-" + UUID.randomUUID();
        String healthy = "healthy-lock-" + UUID.randomUUID();
        insertLock(stale, "9 hours");     // 한국 시각으로 기록된 락을 UTC 기준으로 읽은 모습
        insertLock(healthy, "30 seconds"); // lockAtMostFor 안에 있는 정상 락

        try {
            int deleted = cleaner.cleanUp();

            assertThat(deleted).isGreaterThanOrEqualTo(1);
            assertThat(count(stale)).isZero();
            assertThat(count(healthy)).isEqualTo(1);
        } finally {
            jdbcTemplate.update("delete from shedlock where name in (?, ?)", stale, healthy);
        }
    }
}
