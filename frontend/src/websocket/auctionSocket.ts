export interface BidPlacedEvent {
  event: "bid_placed";
  product_id: string;
  current_price: number;
  bidder_id: string;
  bid_at: string;
}

export interface AuctionExtendedEvent {
  event: "auction_extended";
  product_id: string;
  new_end_at: string;
}

export interface AuctionClosedEvent {
  event: "auction_closed";
  product_id: string;
  winner_id: string | null;
  final_price: number | null;
}

export interface ConnectedEvent {
  event: "connected";
  message: string;
}

export type AuctionEvent =
  | ConnectedEvent
  | BidPlacedEvent
  | AuctionExtendedEvent
  | AuctionClosedEvent;

export type SocketStatus = "connecting" | "open" | "reconnecting" | "closed";

interface AuctionSocketOptions {
  /** 서버에서 이벤트를 받을 때마다 호출 */
  onEvent: (data: AuctionEvent) => void;
  /** 끊겼다가 다시 연결됐을 때 호출 (끊긴 동안 놓친 이벤트 보정용) */
  onReconnect?: () => void;
  /** 연결 상태가 바뀔 때마다 호출 (화면 안내 표시용) */
  onStatusChange?: (status: SocketStatus) => void;
}

export interface AuctionSocketConnection {
  close: () => void;
}

const wsUrl = import.meta.env.VITE_WS_URL || "ws://localhost:4000";

const BASE_DELAY_MS = 1000;
const MAX_DELAY_MS = 10000;
const MAX_RETRIES = 10;
// relay가 productId 누락 시 사용하는 종료 코드: 다시 연결해도 실패하므로 재시도하지 않는다
const POLICY_VIOLATION = 1008;

export function connectAuctionSocket(
  productId: string,
  options: AuctionSocketOptions
): AuctionSocketConnection {
  let socket: WebSocket | null = null;
  let retries = 0;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;
  let closedByClient = false;
  let hasConnectedBefore = false;

  const open = () => {
    options.onStatusChange?.(hasConnectedBefore ? "reconnecting" : "connecting");

    socket = new WebSocket(
      `${wsUrl}?productId=${encodeURIComponent(productId)}`
    );

    socket.onopen = () => {
      if (hasConnectedBefore) {
        options.onReconnect?.();
      }
      hasConnectedBefore = true;
      retries = 0;
      options.onStatusChange?.("open");
    };

    socket.onmessage = (event) => {
      try {
        options.onEvent(JSON.parse(event.data) as AuctionEvent);
      } catch (error) {
        console.error("웹소켓 메시지 파싱 실패:", error);
      }
    };

    // onerror 다음에는 항상 onclose가 호출되므로 재연결 판단은 onclose에서만 한다
    socket.onclose = (event) => {
      socket = null;

      // 페이지 이동 등으로 직접 닫은 경우: 상태를 바꾸지 않는다
      // (React StrictMode에서 먼저 닫힌 소켓이 새 연결의 상태를 덮어쓰지 않도록)
      if (closedByClient) {
        return;
      }

      if (event.code === POLICY_VIOLATION || retries >= MAX_RETRIES) {
        options.onStatusChange?.("closed");
        return;
      }

      // 지수 백오프(1초, 2초, 4초 ... 최대 10초) + 무작위 지연으로 동시 재접속 분산
      const delay =
        Math.min(BASE_DELAY_MS * 2 ** retries, MAX_DELAY_MS) +
        Math.random() * 500;
      retries += 1;
      options.onStatusChange?.("reconnecting");
      retryTimer = setTimeout(open, delay);
    };
  };

  open();

  return {
    close() {
      closedByClient = true;
      if (retryTimer) {
        clearTimeout(retryTimer);
      }
      socket?.close();
    },
  };
}
