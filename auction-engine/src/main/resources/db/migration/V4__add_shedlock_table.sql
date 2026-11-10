-- ShedLock(분산 환경에서 @Scheduled 작업이 한 번에 하나의 인스턴스에서만 실행되게 하는 락)용 테이블.
-- 스키마는 ShedLock 공식 문서가 요구하는 그대로다.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
