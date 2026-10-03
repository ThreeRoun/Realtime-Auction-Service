# 낙찰 처리 및 상태 자동 전이 (구현 완료)

이슈 #21(낙찰 확정/크레딧 차감), #22(상태 자동 전이 스케줄러).

## 동작 방식

5초마다 두 가지를 훑어본다 (`AuctionScheduler`, `@Scheduled(fixedDelay = 5000)`):

1. **마감 지난 상품 닫기** — status가 `IN_PROGRESS`/`EXTENDED`이고 `end_at`이 지난 상품을 찾아
   `AuctionClosingService.closeIfExpired(productId)`를 상품별로 각각 독립된 트랜잭션에서 실행한다.
2. **등록대기 상품 시작** — status가 `PENDING`이고 `start_at`이 지난 상품을 `IN_PROGRESS`로 바꾼다.

## 낙찰 판정 로직

1. 해당 상품의 유효한 입찰(`is_valid=true`)을 금액 높은 순으로 가져온다.
2. 1순위 입찰자의 크레딧이 입찰 금액 이상이면 → 그 사람이 낙찰자, 크레딧 차감, 상품 status는
   `SOLD`.
3. 크레딧이 부족하면 2순위로 넘어간다 (크레딧은 입찰 시점엔 확인/잠금하지 않고 낙찰 확정
   시점에야 비로소 확인하는 정책이라, 입찰 당시엔 아무도 부족 여부를 몰랐을 수 있다).
4. 유효한 입찰이 하나도 없거나, 전원 크레딧이 부족하면 → status `UNSOLD`, 낙찰자 없음.

## 안티 스나이핑(#20)과의 상호작용

마감 처리(`closeIfExpired`)도 입찰 처리(`BidService.placeBid`)와 **똑같이 `findByIdForUpdate`로
비관적 락**을 건다. 같은 상품 row를 두고 "막 마감 처리하려는 스케줄러"와 "마감 임박에 들어온
입찰"이 동시에 경쟁하면, 락 때문에 둘 중 하나가 기다렸다가 상대방이 커밋한 최신 상태(연장된
`end_at` 등)를 보고 다시 판단하게 된다. 락을 얻은 뒤에도 `end_at`을 한 번 더 확인해서, 그 사이
연장이 먼저 끝났으면 마감 처리를 건너뛴다.

## 이벤트 발행

마감 처리 결과(낙찰/유찰 모두) `auction_closed` 이벤트를 발행한다.

```json
{ "event": "auction_closed", "product_id": "...", "winner_id": "...", "final_price": 15000 }
```

유찰이면 `winner_id`/`final_price`가 `null`인 채로 그대로 발행된다 (B는 pass-through라 프론트가
null 여부로 낙찰/유찰을 구분하면 된다).

## 참고

- 5초 주기는 데모/운영 편의상 정한 값이고 `AuctionScheduler`에서 쉽게 조정 가능.
- 상태 전이(`PENDING`→`IN_PROGRESS`)에는 별도 이벤트를 발행하지 않는다 (README에 정의된
  이벤트 목록에 없음).
