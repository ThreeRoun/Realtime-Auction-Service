// server.js

const http = require('http');
// 1. 웹소켓 서버를 열고, Redis와 대화할 준비하기. 
const { WebSocketServer, WebSocket } = require('ws');
const Redis = require('ioredis');

const pino = require('pino');

// JSON 형식 로거.
// 로그를 한 줄에 하나의 JSON으로 출력해서, Loki(Grafana)가 필드별로 검색 및 집계할 수 있게 한다. 
const logger = pino({
  // 출력할 최소 로그 레벨. 환경변수로 변경 가능하게 구현
  // 로그 레벨: trace < debug < info < warn < error < fatal 순서이고, info면 dedug 출력 x.
  level: process.env.LOG_LEVEL || 'info',

  // 모든 로그에 자동으로 붙는 필드.
  // 여러 서비스 로그가 섞이어도 어느 서비스 로그인지 알 수 있다. 
  base: { service: 'realtime-relay'},

  timestamp: pino.stdTimeFunctions.isoTime, // ISO 형식으로 시간 남김

  // 메시지 필드 이름: 기본값 msg-> message (경매 엔진 logstasg 형식과 통일)
  messageKey: 'message',

  formatters: {
    // pino는 기본으로 레벨을 숫자(info=30, warn=40)로 남김
    // 문자열("info", "warn")로 바꿔야 Grafana가 로그 레벨을 자동으로 인식해서 색을 구분해줌. 
    level: (label) => ({ level: label.toUpperCase() }),
  },
});

// 모든 로그는 console.log() 대신 logger.레벨({ event: '로그 종류', ...추가 정보}, '사람이 읽을 메시지'); 로 변경
// 첫 번째 인자(객체): 그라파나에서 검색 및 집계에 쓸 필드. 특히 event 필드가 핵심으로, 어떤 이벤트인지 구분하는 용도로 쓰임.
// 두 번째 인자(문자열): 사람이 읽을 메시지. message 필드로 들어감. 


const PORT = process.env.PORT || 4000;
const REDIS_HOST = process.env.REDIS_HOST || 'localhost';
const REDIS_PORT = Number(process.env.REDIS_PORT) || 6379;

// 'auction_events' 채널을 구독 중인지 여부 (readiness 판단에 사용)
let isSubscribed = false;

// 종료 중인지 여부. 종료가 시작되면 readiness를 실패로 바꿔 새 클라이언트를 받지 않는다. 
let isShuttingDown = false; 

// 종료 과정 설정 (환경변수로 조절 가능)
// - DRAIN_MS: 종료 신호를 받은 뒤 소켓을 닫기 전까지 기다리는 시간.
//             쿠버네티스가 이 Pod를 Service 목록에서 빼는 동안 새 접속이 이쪽으로 오지 않게 한다.
// - BATCH_SIZE / BATCH_INTERVAL_MS: 소켓을 한 번에 닫지 않고 나눠서 닫는 단위와 간격.
//             모든 클라이언트가 동시에 재접속해 다른 Pod에 부하가 몰리는 것을 막는다.
// - FORCE_EXIT_MS: 이 시간 안에 정리가 끝나지 않으면 강제 종료.
//             쿠버네티스의 terminationGracePeriodSeconds(30초)보다 짧아야 한다.
const SHUTDOWN_DRAIN_MS = Number(process.env.SHUTDOWN_DRAIN_MS) || 5000;
const SHUTDOWN_BATCH_SIZE = Number(process.env.SHUTDOWN_BATCH_SIZE) || 50;
const SHUTDOWN_BATCH_INTERVAL_MS = Number(process.env.SHUTDOWN_BATCH_INTERVAL_MS) || 100;
const SHUTDOWN_FORCE_EXIT_MS = Number(process.env.SHUTDOWN_FORCE_EXIT_MS) || 25000;

// HTTP 서버: 쿠버네티스가 상태를 확인할 헬스 엔드포인트를 제공
// WebSocket 서버는 이 HTTP 서버 위에 붙어서, 같은 4000번 포트를 함께 씀.
const server = http.createServer((req, res) => {
  // liveness: 프로세스가 살아서 요청에 응답 가능하면 200
  if (req.url === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok' }));
    return;
  }

  // readiness: Redis에 연결되어 있고 채널을 구독 중일 때만 200
  // (Redis가 끊긴 Pod로 새 클라이언트가 붙으면 이벤트를 받지 못하기 때문)
  if(req.url === '/readyz'){
    const ready = !isShuttingDown && redisSubscriber.status === 'ready' && isSubscribed;
    // 503: 지금은 일을 받을 수 없다는 뜻의 상태 코드
    // 쿠버네티스는 readiness가 200이 아니면 그 Pod로 새 요청을 보내지 않음.  
    res.writeHead(ready ? 200 : 503, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status: ready ? 'ready' : 'not_ready',
      redis_status: redisSubscriber.status,
      is_subscribed: isSubscribed
    }));
    return;
  }

  // 그 외 경로는 404
  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ status: 'not_found' }));
});

// WebSocket 서버: HTTP 서버 위에 붙어서, 같은 4000번 포트를 함께 씀.
const wss = new WebSocketServer({ server });
server.listen(PORT);

// 포트가 실제로 열린 뒤에 "시작" 로그를 남긴다.
// (서버 객체를 만든 직후에 남기면, 포트 열기에 실패해도 시작 로그가 찍히는 문제가 있음)
wss.on('listening', () => {
  logger.info({ event: 'server_start', port: Number(PORT) }, '중계 서버 시작');
});

// 서버 자체의 에러 처리.
// 이 핸들러가 없으면 포트 충돌 시 긴 스택 트레이스와 함께 프로세스가 죽는다.
wss.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    // fatal: 서버가 더 이상 동작할 수 없는 치명적 오류 레벨
    logger.fatal(
      { event: 'server_error', reason: 'port_in_use', port: Number(PORT) },
      `${PORT}번 포트가 이미 사용 중입니다. 실행 중인 relay(컨테이너 포함)를 종료한 뒤 다시 실행하세요.`
    );
  } else {
    logger.fatal({ event: 'server_error', err: err.message }, '중계 서버 오류');
  }
  // 정상 동작할 수 없으므로 실패 코드(1)로 종료한다.
  process.exit(1);
});

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
  // 어느 Redis에 붙었는지 남겨 두면, 연결 문제가 생겼을 때 원인을 찾기 쉽다.
  logger.info({ event: 'redis_connect', host: REDIS_HOST, port: REDIS_PORT }, 'Redis 연결 성공');
});

redisSubscriber.on('error', (err) => {
  // err 객체 전체 대신 메시지만 남겨서 로그를 짧게 유지한다.
  logger.error({ event: 'redis_error', err: err.message }, 'Redis 연결 오류');
});

// key가 productId이고, value가 참가자 set인 map
const rooms = new Map(); 

// 상품 목록 화면용 로비 방의 key. 상품 ID(UUID)와 겹치지 않는 이름을 쓴다.
// 로비 접속자는 모든 상품의 이벤트를 받는다.
const LOBBY = '__lobby__';

// 특정 방의 열린 소켓 전원에게 문자열을 보내고, 방 인원 수를 반환한다.
function broadcast(roomKey, payload) {
  const room = rooms.get(roomKey);
  if (!room || room.size === 0) {
    return 0;
  }
  room.forEach((client) => {
    // 파이프라인이 정상적으로 열려(OPEN) 있을 때만 전송
    if (client.readyState === WebSocket.OPEN) {
      client.send(payload);
    }
  });
  return room.size;
}

// 목록 화면에 필요한 필드만 추린다.
// 로비는 접속자가 많고 모든 상품의 이벤트를 받으므로, 입찰자 ID 같은 불필요한 정보는 빼서
// 전송량과 정보 노출을 줄인다.
function toLobbyPayload(eventData, productId) {
  const slim = { event: eventData.event, product_id: String(productId) };
  if (eventData.current_price !== undefined) slim.current_price = eventData.current_price;
  if (eventData.new_end_at !== undefined) slim.new_end_at = eventData.new_end_at;
  if (eventData.final_price !== undefined) slim.final_price = eventData.final_price;
  return JSON.stringify(slim);
}
// key에 대해 rooms.get("101"); 등으로 특정 상품 추출
// value는 해당 상품의 경매 화면에 들어와 있는 브라우저들의 소켓 객체 set이다.
// 아래와 같이 101번 상품에 들어와 있는 클라이언트들에게 브로드캐스트 가능.  
// rooms.get("101").forEach(client => client.send(rawString));

// 2. Redis 구독 및 브로드캐스트 리스너
// Back A가 던진 이벤트를 귀 기울여 듣고, 해당 경매 방에 있는 소켓들에게만 그대로 전달. 

/* auction_events 채널 구독 */
redisSubscriber.subscribe('auction_events', (err,count) => {
  if (err) {
    logger.error({ event: 'redis_subscribe_error', channel: 'auction_events', err: err.message }, 'Redis 구독 실패');
  } else {
    isSubscribed = true;
    logger.info({ event: 'redis_subscribe', channel: 'auction_events', count }, 'Redis 채널 구독 완료');
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
      // reason 필드로 "왜" 문제인지 남기면, 나중에 원인별로 집계할 수 있다.
      // auction_event로 어떤 종류의 이벤트에서 빠졌는지도 함께 남긴다.
      logger.warn({ event: 'event_invalid', reason: 'missing_product_id', auction_event: eventData.event }, '상품 ID가 없는 이벤트');
      return;
    }

    // 1) 해당 상품 방에는 엔진이 보낸 원본 문자열을 그대로 전송
    const sent = broadcast(String(pId), rawString);
    // 2) 로비(상품 목록 화면)에는 필요한 필드만 추려서 전송
    const lobbySent = broadcast(LOBBY, toLobbyPayload(eventData, pId));

    // 접속자가 없어도 같은 event('broadcast')로 남긴다.
    // 이렇게 해야 "총 몇 번 중계했나"를 한 번에 셀 수 있다.
    // (예전처럼 상황마다 다른 문장을 쓰면, 집계할 때 두 종류를 따로 찾아야 함)
    logger.info(
      {
        event: 'broadcast',
        auction_event: eventData.event,   // 어떤 경매 이벤트였나 (bid_placed 등)
        product_id: String(pId),          // 어느 상품이었나
        sent,                             // 상품 방에 보낸 인원
        lobby_sent: lobbySent,            // 로비에 보낸 인원
      },
      // 메시지만 상황에 따라 다르게 해서 사람이 볼 때 구분되게 한다.
      sent === 0 && lobbySent === 0 ? '이벤트 중계 (접속자 없음)' : '이벤트 중계'
    );
    } catch (err) {
    logger.error({ event: 'event_parse_error', err: err.message }, '이벤트 파싱 실패');
  }
});

// Ping-Pong heartbeat: 30초마다 무응답 클라이언트 감지 및 정리
const interval = setInterval(() => {
  // 서버 전체 클라이언트를 주기적으로 훓으면서
  wss.clients.forEach((ws) => {
    // 30초 전 ping을 보냈음에도 pong 응답이 없었던 경우, 좀비 소켓으로 판정.
    if (ws.isAlive === false) {
      logger.warn({ event: 'heartbeat_terminate' }, '무응답 소켓 강제 종료');
      return ws.terminate();
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

  // 쿼리 스트링에서 접속 대상을 꺼낸다.
  // - 상품 상세 화면: ?productId=<상품 ID> → 해당 상품 방
  // - 상품 목록 화면: ?scope=lobby          → 로비 방 (모든 상품의 이벤트 수신)
  const scope = parsedUrl.searchParams.get('scope');
  const productId = parsedUrl.searchParams.get('productId') || parsedUrl.searchParams.get('product_id');

  let roomKey;
  if (scope === 'lobby') {
    roomKey = LOBBY;
  } else if (productId && productId.trim()) {
    roomKey = productId.trim();
  } else {
    // 둘 다 없으면 잘못된 접근이므로 연결을 끊는다.
    logger.warn({ event: 'connect_rejected', reason: 'missing_room_param' }, '방 정보 없는 접속 차단');
    ws.close(1008, 'productId 또는 scope=lobby 파라미터가 필요합니다.');
    return;
  }

  // 로그에 남길 방 정보.
  // 로비의 내부 key('__lobby__')는 로그에서 보기 불편해서 'lobby'로 바꿔 표시한다.
  // room_type은 "상품 방인지 로비인지"를 나눠서 집계할 때 쓴다.
  const roomType = roomKey === LOBBY ? 'lobby' : 'product';
  const roomLabel = roomKey === LOBBY ? 'lobby' : roomKey;

  // rooms에서 해당 방이 없었다면 새로 만든다.
  if(!rooms.has(roomKey)){
    rooms.set(roomKey, new Set());
  }
  // 방의 소켓 모음 Set에 새로운 소켓(손님)을 추가한다.
  rooms.get(roomKey).add(ws);
  logger.info({event: 'connect', room: roomLabel, room_type: roomType, members: rooms.get(roomKey).size}, '클라이언트 접속');
  // 손님에게 환영 메세지를 보낸다.
  ws.send(JSON.stringify({
    event: 'connected',
    message: roomKey === LOBBY ? '경매 로비 접속 성공' : `${roomKey}번 경매 방 접속 성공`
  }));


  ws.on('close', () => {
  // 닫고자 하는 방의 손님 소켓 모음 Set을 꺼낸다.
    const room=rooms.get(roomKey);

    // 방이 존재할 경우, 정리 작업 수행
    if(room){
      // Set에서 방금 나간 손님의 소켓 ws를 뺀다.
      room.delete(ws);
      logger.info({event: 'disconnect', room: roomLabel, room_type: roomType, members: room.size}, '클라이언트 연결 해제');

      // 뺀 이후에 방에 남은 손님의 수가 0이면 방 자체를 Map에서 삭제한다.
      if(room.size===0){
        rooms.delete(roomKey);
        logger.info({event: 'room_delete', room: roomLabel, room_type: roomType}, '빈 방 정리');
      }
    }
  });

  // 소켓 에러로 서버가 꺼지는 것을 방지하기 위한 안전장치.
  ws.on('error', (err) => {
    logger.error({event: 'socket_error', room: roomLabel, room_type: roomType, err: err.message }, `소켓 에러`);
  });
});

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const gracefulShutdown = async (signal) => {
  // 신호가 여러 번 와도 종료 과정은 한 번만 실행한다
  if (isShuttingDown) return;
  isShuttingDown = true;

  logger.info({ event: 'shutdown_start', signal, clients: wss.clients.size }, '종료 신호 감지, 리소스 정리 시작');
  clearInterval(interval);

  // 정리가 너무 오래 걸리면 강제 종료 (unref: 이 타이머 때문에 프로세스가 살아 있지 않게 함)
  setTimeout(() => {
    logger.error({ event: 'shutdown_timeout', timeout_ms: SHUTDOWN_FORCE_EXIT_MS }, '정리 시간 초과, 강제 종료');
    process.exit(1);
  }, SHUTDOWN_FORCE_EXIT_MS).unref();

  // 1) readiness가 실패로 바뀐 상태로 잠시 기다린다.
  //    그동안 쿠버네티스가 이 Pod를 목록에서 빼서, 재접속하는 클라이언트가 다른 Pod로 가게 된다.
  await sleep(SHUTDOWN_DRAIN_MS);

  // 2) 소켓을 나눠서 닫는다. (한꺼번에 닫으면 전원이 동시에 재접속해 부하가 몰림)
  const clients = [...wss.clients];
  for (let i = 0; i < clients.length; i += SHUTDOWN_BATCH_SIZE) {
    clients.slice(i, i + SHUTDOWN_BATCH_SIZE).forEach((client) => {
      client.close(1001, '서버가 종료되어 연결을 마칩니다.');
    });
    if (i + SHUTDOWN_BATCH_SIZE < clients.length) {
      await sleep(SHUTDOWN_BATCH_INTERVAL_MS);
    }
  }
  logger.info({ event: 'shutdown_clients_closed', count: clients.length }, '클라이언트 연결 종료 완료');

  // 3) 서버와 Redis 연결을 정리하고 종료한다.
  wss.close(() => {
    logger.info({ event: 'shutdown_ws_closed' }, 'WebSocket 서버 닫힘');
    // 헬스 체크 등으로 열려 있는 유휴 HTTP 연결 때문에 close가 늦어지지 않도록 먼저 정리
    server.closeIdleConnections();
    server.close(() => {
      redisSubscriber.quit().then(() => {
        logger.info({ event: 'shutdown_complete' }, 'Redis 연결 정상 종료, 서버 종료');
        process.exit(0);
      });
    });
  });
};

// 로그에 어떤 신호로 종료됐는지 남기기. 
process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
process.on('SIGINT', () => gracefulShutdown('SIGINT'));