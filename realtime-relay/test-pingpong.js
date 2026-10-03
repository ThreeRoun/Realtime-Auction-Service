// realtime-relay/test-pingpong.js
const { WebSocket } = require('ws');

const TARGET_URL = 'ws://localhost:4000?productId=test-room';

console.log('🧪 [Ping-Pong 테스트 시작]');

// 1. 좀비 클라이언트 (Ping을 받아도 Pong 응답을 안 보냄)
const zombieWs = new WebSocket(TARGET_URL);

zombieWs.on('open', () => {
  console.log('1️⃣ [좀비 클라이언트] 연결 성공 - Pong 응답을 거부하도록 설정합니다.');
  // ws 라이브러리는 기본적으로 ping 수신 시 자동 pong을 보내므로 이를 차단
  zombieWs.pong = () => {}; 
  zombieWs._receiver.onPing = () => {
    console.log('🧟 [좀비 클라이언트] 서버로부터 Ping 수신! 하지만 Pong을 보내지 않고 무시합니다.');
  };
});

zombieWs.on('close', (code, reason) => {
  console.log(`💀 [좀비 클라이언트 종료 감지] code: ${code}, reason: ${reason || '강제 종료됨'}`);
  console.log('🎉 좀비 커넥션 감지 및 강제 종료 테스트 성공!');
});

zombieWs.on('error', (err) => {
  console.error('좀비 소켓 에러:', err.message);
});