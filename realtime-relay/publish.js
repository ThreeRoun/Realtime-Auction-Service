// realtime-relay/publish.js
// 테스트용 입찰 이벤트를 Redis 'auction_events' 채널로 발행한다.
// 경매 엔진 없이 relay와 부하 테스트를 검증할 때 사용한다.
//
// 사용법:
//   node publish.js <상품 ID> [발행 횟수=5] [간격(ms)=1500]
//   예) node publish.js 3fa85f64-5717-4562-b3fc-2c963f66afa6 10 1000
const Redis = require('ioredis');

const [productId, countArg, intervalArg] = process.argv.slice(2);

if (!productId) {
  console.error('사용법: node publish.js <상품 ID> [발행 횟수=5] [간격(ms)=1500]');
  process.exit(1);
}

const count = Number(countArg) || 5;
const intervalMs = Number(intervalArg) || 1500;

const redis = new Redis({
  host: process.env.REDIS_HOST || 'localhost',
  port: Number(process.env.REDIS_PORT) || 6379,
});

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function run() {
  console.log(`🚀 [테스트 입찰 발행 시작] 상품 ${productId}, ${count}회, ${intervalMs}ms 간격`);

  for (let i = 1; i <= count; i++) {
    const payload = {
      event: 'bid_placed',
      product_id: productId,
      current_price: 10000 * (i + 1),
      bidder_id: 'load-tester',
      bid_at: new Date().toISOString(),
      // 부하 테스트에서 발행 → 수신 지연을 계산하기 위한 발행 시각(epoch ms).
      // 실제 경매 엔진 이벤트에는 없는 테스트 전용 필드다.
      sent_at: Date.now(),
    };

    const receivers = await redis.publish('auction_events', JSON.stringify(payload));
    console.log(`[${i}/${count}] ${payload.current_price}원 발행 (구독 중인 relay: ${receivers})`);

    if (i < count) {
      await sleep(intervalMs);
    }
  }

  console.log('🏁 발행 완료');
  await redis.quit();
}

run().catch((err) => {
  console.error('발행 중 오류 발생:', err.message);
  process.exit(1);
});
