// server.js

// 1. 웹소켓 서버를 열고, Redis와 대화할 준비하기. 
const { WebSocketServer, WebSocket } = require('ws');
const Redis = require('ioredis');

const PORT = process.env.PORT || 4000;
const REDIS_HOST = process.env.REDIS_HOST || 'auction-redis';
const REDIS_PORT = Number(process.env.REDIS_PORT) || 6379;
// wss: WebSocket Server (8080 문지기인 서버 전체 관리자) 
const wss = new WebSocketServer({ port : PORT });
console.log(`[중계 엔진] WebSocket 서버 포트: ${PORT}`);

const redisSubscriber = new Redis({
  host: REDIS_HOST,
  port: REDIS_PORT,
  // Redis 연결이 끊겼을 때 재연결 시도
  retryStrategy(times) {
    // 0.1초, 0.2초,... 최대 3초 간격으로 다시 연결 시도.
    const delay = Math.min(times * 100, 3000);
    return delay;
  }
});

// Redis 연결 성공 여부 로그
redisSubscriber.on('connect', () => {
  console.log(`✅ [Redis] TCP 소켓 연결 성공 (${REDIS_HOST}:${REDIS_PORT})`);
});

redisSubscriber.on('error', (err) => {
  console.error('❌ [Redis 연결 오류]:', err.message);
});

// key가 productId이고, value가 참가자 set인 map
const rooms = new Map(); 
// key에 대해 rooms.get("101"); 등으로 특정 상품 추출
// value는 해당 상품의 경매 화면에 들어와 있는 브라우저들의 소켓 객체 set이다.
// 아래와 같이 101번 상품에 들어와 있는 클라이언트들에게 브로드캐스트 가능.  
// rooms.get("101").forEach(client => client.send(rawString));

// 2. Redis 구독 및 브로드캐스트 리스너
// Back A가 던진 이벤트를 귀 기울여 듣고, 해당 경매 방에 있는 소켓들에게만 그대로 전달. 

/* auction_events 채널 구독 */
redisSubscriber.subscribe('auction_events', (err,count) => {
  if(err){
    console.error(`❌ [Redis 구독 실패]:`, err.message);
  } else{
    console.log(`✅ [Redis] 'auction_events' 채널 구독 완료! (현재 구독 채널 수: ${count})`);
  }
});

// redisSubscriber.on()
// Back A가 Redis로 쏜 이벤트를 감지했을 때(Back A와 통신!) 해당 방에 브로드캐스트 
// 새 입찰/연장/종료 등 경매 이벤트가 터진 순간에 rawString(JSON 문자열), 채널명을 수신함.
// -> 상품방에 들어와 있는 클라이언트들에게 브로드캐스트.

/* rawString은 {"event":"bid_placed","productId":"101","current_price":55000} 처럼 들어왔다고 가정. */
redisSubscriber.on('message', (channel, rawString) => {
  try{
    // 들어온 rawString을 파싱해서 js 객체로 만들기.  
    const eventData = JSON.parse(rawString);

    // productId와 product_id 둘 다 대응
    const pId = eventData.productId || eventData.product_id;

    if(!pId){
      console.warn('[중계 경고] 페이로드에 productId가 누락되었습니다.');
      return;
    }

    // 만든 js 객체에서 productId 추출해서 해당 상품 방 Set을 targetRoom으로 꺼내온다.
    const targetRoom = rooms.get(String(pId));
    // 방이 존재한다면 방 안의 소켓들을 forEach로 돌면서 전송한다.
    if(targetRoom && targetRoom.size > 0){
      console.log(`\n📢 [중계] ${pId}번 방 (${targetRoom.size}명)에게 데이터 살포`);

      targetRoom.forEach(client => {
        // 사용자가 막 브라우저 창을 닫았거나 네트워크가 끊겨 파이프라인이 닫히는 중일 수도 있으므로,
        // 파이프라인이 정상적으로 열려(OPEN) 있을 때만 데이터 전송.
        if(client.readyState === WebSocket.OPEN){
          // 클라이언트에 보낼 데이터는 A가 보낸 원본 문자열을 그대로 전송
          client.send(rawString);
        }
      });
    } else{
      console.log(`\n ⚠️ [중계 패스] ${pId}번 방 접속자가 없습니다.`);
    }
  } catch (err) {
    console.error(`❌ [메시지 파싱 에러]:`, err.message);
  }
});

console.log(`========================================`);
console.log(`[중계 엔진] 포트 ${PORT}에서 대기 중...`);
console.log(`========================================`);

// Ping-Pong heartbeat: 30초마다 무응답 클라이언트 감지 및 정리
const interval = setInterval(() => {
  // 서버 전체 클라이언트를 주기적으로 훓으면서
  wss.clients.forEach((ws) => {
    // 30초 전 ping을 보냈음에도 pong 응답이 없었던 경우, 좀비 소켓으로 판정.
    if(ws.isAlive === false){
      console.log('[좀비 커넥션 감지] 무응답 소켓 강제 종료');
      return ws.terminate(); // 소켓을 서버 로컬에서 파기하고 close 이벤트 유발.
    }
    // 살아있는 소켓에는 다음 주기 검사를 위해 false로 마킹하고 ping을 발송.
    ws.isAlive = false;
    ws.ping();
  });
}, 30000);

// 서버 종료시 타이머 해제
// 서버가 재시작되거나 종료될 때, 백그라운드의 setInterval 타이머가 계속 살아있지 않도록.
wss.on('close', () => {
  clearInterval(interval);
});

// 유저가 경매 페이지를 열어 연결을 맺는 순간의 
// ws(브라우저 소켓 파이프라인(손님 1명))와 req(URL) 수신(프론트와 통신!)
// -> 상품에 해당하는 rooms에 client 추가하기.
wss.on('connection', (ws, req)=>{

  // 클라이언트가 처음 들어오면 소켓 객체에 생존 여부를 기록할 플래그 isAlive를 붙임.
  ws.isAlive = true;
  // pong 이벤트 수신할 때마다 생존 상태 갱신
  ws.on('pong', () => {
      ws.isAlive = true;
  });

  // 서버측 req.url에는 도메인을 뺀 나머지 상대 경로(/?productId=101)만 들어옴.
  // [프로토콜://도메인/경로]를 임의로 앞에 붙여줌.
  const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  // 쿼리 스트링에서 productId를 꺼내기(key=productId의 item=101을 꺼내기)
  // URL 내부 객체 => searchParams: URLSearchParams { 'productId' => '101' }
  const productId = parsedUrl.searchParams.get('productId') || parsedUrl.searchParams.get('product_id');

  // productId가 없는 경우 잘못된 접근이므로 ws.close()로 연결을 끊는다. 
  if(!productId || !productId.trim()){
    ws.close(1008, 'productId 파라미터가 필요합니다.');
    return;
  }

  const cleanProductId = productId.trim();

  // rooms에서 키가 productId인 상품 방이 있었는지 확인하고,
  if(!rooms.has(cleanProductId)){ // 없었다면,
    // Key가 productId인 새 방을 만든다.
    rooms.set(cleanProductId, new Set());
  }
  // productId 방의 소켓 모음 Set에 새로운 소켓(손님)을 추가한다. 
  rooms.get(cleanProductId).add(ws);
  // 손님에게 환영 메세지를 보낸다. 
  ws.send(JSON.stringify({
    event: 'connected',
    message: `${cleanProductId}번 경매 방 접속 성공`
  }));


  ws.on('close', () => {
  // 닫고자 하는 방의 손님 소켓 모음 Set을 꺼낸다. 
    const room=rooms.get(cleanProductId);

    // 방이 존재할 경우, 정리 작업 수행
    if(room){
      // Set에서 방금 나간 손님의 소켓 ws를 뺀다.
      room.delete(ws);
      console.log(`[클라이언트 연결 해제] 방: ${cleanProductId} (남은 인원: ${room.size}명)`);
    
      // 뺀 이후에 방에 남은 손님의 수가 0이면 방 자체를 Map에서 삭제한다.
      if(room.size===0){
        rooms.delete(cleanProductId);
        console.log(`[방 삭제] 인원이 없어 방 ${cleanProductId}을(를) 정리합니다.`);
      }
    }
  });

  // 소켓 에러로 서버가 꺼지는 것을 방지하기 위한 안전장치.
  ws.on('error', (err) => {
    console.error(`[소켓 에러]:`, err.message);
  });
});

// 컨테이너 종료 시 안전한 연결 회수
const gracefulShutdown = () => {
  console.log('\n🛑 중계 서버 종료 신호 감지, 리소스를 정리합니다...');
  clearInterval(interval);

  wss.clients.forEach((client)=>{
    client.close(1001, '서버가 종료되어 연결을 마칩니다.');
  });

  wss.close(()=>{
    console.log('✅ WebSocket 서버 닫힘');
    redisSubscriber.quit().then(()=>{
      console.log('✅ Redis 연결 정상 종료');
      process.exit(0);
    });
  });

  // 3초 내에 정리가 안 끝나면 강제 종료
  setTimeout(()=>{
    process.exit(1);
  }, 3000);
};

process.on('SIGTERM', gracefulShutdown);
process.on('SIGINT', gracefulShutdown);