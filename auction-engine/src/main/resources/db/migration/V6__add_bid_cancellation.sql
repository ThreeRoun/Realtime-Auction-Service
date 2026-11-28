-- 입찰 취소(이슈 #182). 입찰 로그는 덮어쓰지 않으므로 행을 지우거나 is_valid를 바꾸지 않고,
-- 취소 시각과 사유만 따로 남긴다. cancelled_at이 NULL이 아니면 취소된 입찰이다.
-- cancel_reason: USER(본인이 취소), CREDIT_SHORTFALL(최고 입찰이 취소돼 직전 입찰로 되돌릴 때,
-- 그 입찰자의 크레딧이 모자라 시스템이 건너뛰며 취소 처리)
ALTER TABLE bids ADD COLUMN cancelled_at TIMESTAMP;
ALTER TABLE bids ADD COLUMN cancel_reason VARCHAR(30);
