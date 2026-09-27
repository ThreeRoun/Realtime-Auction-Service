// realtime-relay/publish.js
const Redis = require('ioredis');
const redis = new Redis({ host: '127.0.0.1', port: 6379 });

// 연속으로 발생시킬 입찰 시나리오 배열
const bids = [
  { product_id: '101', current_price: 55000, bidder_id: 'user_kim' },
  { product_id: '101', current_price: 60000, bidder_id: 'user_lee' },
  { product_id: '101', current_price: 72000, bidder_id: 'user_park' },
  { product_id: '101', current_price: 85000, bidder_id: 'user_choi' },
  { product_id: '101', current_price: 100000, bidder_id: 'user_final' }
];

async function runAuctionSimulation() {
  console.log('🚀 [경매 연속 입찰 시뮬레이션 시작]');

  for (let i = 0; i < bids.length; i++) {
    const bid = bids[i];
    const payload = {
      event: 'bid_placed',
      product_id: bid.product_id,
      current_price: bid.current_price,
      bidder_id: bid.bidder_id,
      bid_at: new Date().toISOString()
    };

    const count = await redis.publish('auction_events', JSON.stringify(payload));
    console.log(`[${i + 1}/${bids.length}] ${bid.product_id}번 상품 ${bid.current_price}원 입찰 발행 완료 (수신 중계 서버: ${count})`);

    // 1.5초 간격으로 다음 입찰 진행 (실제 입찰 지연 재현)
    if (i < bids.length - 1) {
      await new Promise(resolve => setTimeout(resolve, 1500));
    }
  }

  console.log('🏁 모든 연속 입찰 발행이 완료되었습니다.');
  process.exit(0);
}

runAuctionSimulation().catch(err => {
  console.error('발행 중 오류 발생:', err.message);
  process.exit(1);
});