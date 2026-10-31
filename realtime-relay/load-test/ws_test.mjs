// realtime-relay/load-test/ws_test.mjs
// k6 WebSocket 부하 테스트: 한 상품 방에 다수의 가상 사용자를 접속시키고,
// 입찰 이벤트가 전원에게 전달되는지와 발행 → 수신 지연을 측정한다.
//
// 실행 예시:
//   k6 run -e PRODUCT_ID=<상품 UUID> load-test/ws_test.mjs
//   k6 run -e PRODUCT_ID=<상품 UUID> -e VUS=500 load-test/ws_test.mjs
import ws from 'k6/ws';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const PRODUCT_ID = __ENV.PRODUCT_ID;
const WS_URL = __ENV.WS_URL || 'ws://localhost:4000';
const VUS = Number(__ENV.VUS || 2000);

if (!PRODUCT_ID) {
  throw new Error('PRODUCT_ID가 필요합니다. 예: k6 run -e PRODUCT_ID=<상품 UUID> load-test/ws_test.mjs');
}

// 발행 시각(sent_at)이 담긴 이벤트를 받았을 때, 발행부터 수신까지 걸린 시간(ms)
const broadcastLatency = new Trend('broadcast_latency', true);

export const options = {
  stages: [
    { duration: '20s', target: VUS }, // 20초 동안 VUS명까지 접속
    { duration: '30s', target: VUS }, // 30초 동안 연결 유지 및 메시지 수신
    { duration: '10s', target: 0 }, // 점진적 종료
  ],
};

export default function () {
  const url = `${WS_URL}/?productId=${PRODUCT_ID}`;

  const res = ws.connect(url, { tags: { my_tag: 'auction_ws' } }, function (socket) {
    socket.on('message', (msg) => {
      const data = JSON.parse(msg);

      if (data.event === 'connected') {
        check(data, { '환영 메시지 수신 성공': (obj) => obj.message.includes('접속 성공') });
      } else if (data.event === 'bid_placed') {
        check(data, { '실시간 입찰 데이터 수신': (obj) => obj.product_id === PRODUCT_ID });

        // publish.js가 넣어 보낸 발행 시각이 있으면 지연을 기록한다
        // (같은 머신에서 발행·수신할 때만 의미 있는 값)
        if (typeof data.sent_at === 'number') {
          broadcastLatency.add(Date.now() - data.sent_at);
        }
      }
    });

    socket.on('error', (e) => {
      if (e.error() !== 'websocket: close sent') {
        console.error('웹소켓 에러 발생: ', e.error());
      }
    });

    // 각 가상 사용자는 60초 동안 접속을 유지한 뒤 연결을 닫는다
    socket.setTimeout(() => socket.close(), 60000);
  });

  check(res, { 'status is 101 (Switching Protocols)': (r) => r && r.status === 101 });
}
