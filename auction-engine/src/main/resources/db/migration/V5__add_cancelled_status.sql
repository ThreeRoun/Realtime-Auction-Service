-- 판매자의 경매 취소(이슈 #183)로 CANCELLED 상태가 생겨서 products.status CHECK 제약을 다시 만든다.
-- V1에서 컬럼 정의에 이름 없이 붙인 CHECK라 PostgreSQL이 products_status_check로 이름을 붙였다.
ALTER TABLE products DROP CONSTRAINT products_status_check;
ALTER TABLE products ADD CONSTRAINT products_status_check
    CHECK (status IN ('PENDING', 'IN_PROGRESS', 'EXTENDED', 'SOLD', 'UNSOLD', 'COMPLETED', 'CANCELLED'));
