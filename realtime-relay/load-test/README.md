# 📈 Load Test (부하 테스트)

[k6](https://k6.io/)로 한 상품 방에 다수의 가상 사용자를 접속시키고,
입찰 이벤트가 전원에게 전달되는지와 발행 → 수신 지연을 측정합니다.

## 실행 방법

```bash
# 1. Redis와 relay 실행 (realtime-relay/README.md의 Local Run 참고)

# 2. 부하 테스트 시작 (기본 2,000명, VUS로 조절 가능)
# (realtime-relay 폴더에서 실행)
k6 run -e PRODUCT_ID=test-product load-test/ws_test.mjs

# 3. 접속자가 다 찬 뒤(약 20초 후) 다른 터미널에서 테스트 이벤트 발행
node publish.js test-product 5 1000
```

`publish.js`의 인자는 `<상품 ID> [발행 횟수=5] [간격(ms)=1500]`입니다.
실제 경매 엔진으로 테스트할 때는 `PRODUCT_ID`에 진행 중인 상품의 UUID를 넣고, UI에서 입찰하면 됩니다.

## 결과 지표

| 지표 | 의미 |
| --- | --- |
| `checks` | 연결 성공(101), 환영 메시지, 입찰 이벤트 수신 검증 결과 |
| `ws_msgs_received` | 수신한 전체 메시지 수 (환영 메시지 + 입찰 이벤트) |
| `broadcast_latency` | `publish.js` 발행부터 k6 수신까지 걸린 시간 (avg, p95 등) |

- `broadcast_latency`는 `publish.js`가 넣는 테스트 전용 필드 `sent_at`으로 계산하므로,
  경매 엔진의 실제 이벤트로 테스트할 때는 측정되지 않습니다.
- 발행과 수신의 시계를 비교하므로, `publish.js`와 k6를 같은 머신에서 실행해야 정확합니다.
