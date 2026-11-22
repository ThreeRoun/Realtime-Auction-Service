package com.threeroun.auctionengine.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 기동할 때 "시간이 잘못 기록된 스케줄러 락"을 치운다 (이슈 #152).
//
// 락 시각(shedlock.lock_until)을 UTC 기준으로 저장하도록 바꿨는데(SchedulerLockConfig), 그 전에 서버 시간대
// (예: 한국 시각 PC의 로컬 DB)로 기록된 락이 남아 있으면 새 기준에서는 "9시간 뒤까지 잠긴 락"으로 읽힌다.
// 그러면 마감 처리 스케줄러가 락을 못 잡아 최대 9시간 동안 멈추고, 화면은 마감인데 상태는 그대로인 증상이 된다.
// 정상적인 락의 lock_until은 "지금 + lockAtMostFor(30초)"를 넘지 않으므로, 지금보다 10분 넘게 미래인 락은
// 시각이 잘못 기록된 것으로 보고 지운다. 락은 일시적인 상태라 지워도 다음 실행에서 다시 잡히면 되고(중복 실행은
// 마감 처리 자체가 행 락으로 안전하게 설계돼 있다), 정상 락은 건드리지 않는다.
@Component
public class StaleSchedulerLockCleaner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaleSchedulerLockCleaner.class);

    private final JdbcTemplate jdbcTemplate;

    public StaleSchedulerLockCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        cleanUp();
    }

    // 지운 행 수를 돌려준다. 기준 시각도 DB의 UTC 시각을 쓴다 (락이 UTC 기준으로 저장되므로 같은 기준).
    public int cleanUp() {
        int deleted = jdbcTemplate.update(
                "delete from shedlock where lock_until > (now() at time zone 'utc') + interval '10 minutes'");
        if (deleted > 0) {
            log.warn("시간이 잘못 기록된 스케줄러 락 {}건을 삭제했습니다 (서버 시간대 변경 전에 기록된 락으로 추정)", deleted);
        }
        return deleted;
    }
}
