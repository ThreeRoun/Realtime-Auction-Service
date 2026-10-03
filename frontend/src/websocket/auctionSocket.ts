export interface BidPlacedEvent {
  event: "bid_placed";
  product_id: string;
  current_price: number;
  bidder_id: string;
  bid_at: string;
}

const wsUrl = import.meta.env.VITE_WS_URL || "ws://localhost:4000";

export function connectAuctionSocket(productId: string): WebSocket {
  const socket = new WebSocket(
    `${wsUrl}?productId=${encodeURIComponent(productId)}`
  );

  return socket;
}