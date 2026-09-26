CREATE TABLE users (
    id             UUID PRIMARY KEY,
    username       VARCHAR(50)  NOT NULL UNIQUE,
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  VARCHAR(255) NOT NULL,
    credit         INTEGER      NOT NULL DEFAULT 0 CHECK (credit >= 0),
    created_at     TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE products (
    id              UUID PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    description     TEXT,
    starting_price  INTEGER      NOT NULL CHECK (starting_price >= 0),
    current_price   INTEGER      NOT NULL CHECK (current_price >= 0),
    bid_unit        INTEGER      NOT NULL CHECK (bid_unit > 0),
    seller_id       UUID         NOT NULL REFERENCES users (id),
    status          VARCHAR(20)  NOT NULL
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'EXTENDED', 'SOLD', 'UNSOLD', 'COMPLETED')),
    start_at        TIMESTAMP    NOT NULL,
    end_at          TIMESTAMP    NOT NULL,
    winner_id       UUID         REFERENCES users (id),
    created_at      TIMESTAMP    NOT NULL DEFAULT now()
);

-- 마감 임박 상품을 훑는 스케줄러(안티 스나이핑, 낙찰 확정)가 status/end_at으로 조회하므로 인덱스 추가
CREATE INDEX idx_products_status_end_at ON products (status, end_at);

CREATE TABLE bids (
    id          UUID      PRIMARY KEY,
    product_id  UUID      NOT NULL REFERENCES products (id),
    bidder_id   UUID      NOT NULL REFERENCES users (id),
    amount      INTEGER   NOT NULL CHECK (amount > 0),
    bid_at      TIMESTAMP NOT NULL DEFAULT now(),
    is_valid    BOOLEAN   NOT NULL DEFAULT false
);

-- 상품별 입찰 이력(현재가/낙찰자 판정)을 시간순으로 조회하는 게 핵심 동작이라 인덱스 추가
CREATE INDEX idx_bids_product_id_bid_at ON bids (product_id, bid_at DESC);
