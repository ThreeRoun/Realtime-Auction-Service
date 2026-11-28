export interface BidRequest {
  productId: string;
  amount: number;
}

export interface BidResponse {
  bidId: string;
  productId: string;
  bidderId: string;
  amount: number;
  bidAt: string;
  isValid: boolean;
  // 엔진 이슈 #148에서 추가된 필드. 아직 반영되지 않은 엔진에서는 내려오지 않을 수 있어서 선택 값으로 둔다.
  // reason: 진 입찰의 사유 코드(현재는 AMOUNT_TOO_LOW), minNextBid: 다음 입찰이 유효하려면 필요한 최소 금액
  reason?: string | null;
  minNextBid?: number;
  availableCredit?: number;
}

export interface BidCancelResponse {
  bidId: string;
  productId: string;
  currentPrice: number;
  topBidderId: string | null;
  cancelledBidIds: string[];
  availableCredit: number;
}

export interface BidHistoryResponse {
  bidId: string;
  bidderId: string;
  // 입찰자 닉네임 (엔진 이슈 #148). 없으면 bidderId로 대신 보여준다.
  bidderUsername?: string;
  amount: number;
  bidAt: string;
  isValid: boolean;
}

// 입찰이 HTTP 오류로 거절되면 엔진이 본문에 { code, message }를 내려준다.
// message에는 상품 UUID 같은 내부 정보가 섞여 있어서 화면에 그대로 보여주지 않고, code별로 프론트에서 문구를 정한다.
// (쿨다운 시간은 엔진 설정으로 바뀔 수 있어서 문구에 숫자를 넣지 않는다)
const BID_ERROR_MESSAGES: Record<string, string> = {
  SELF_BID_NOT_ALLOWED: "본인이 등록한 상품에는 입찰할 수 없습니다.",
  BID_COOLDOWN_ACTIVE: "방금 입찰하셨습니다. 잠시 후 다시 입찰해 주세요.",
  AUCTION_NOT_IN_PROGRESS: "진행 중인 경매가 아닙니다.",
  PRODUCT_NOT_FOUND: "존재하지 않는 상품입니다.",
  INSUFFICIENT_CREDIT: "사용 가능한 크레딧이 부족하여 입찰할 수 없습니다.",
};

const BID_CANCEL_ERROR_MESSAGES: Record<string, string> = {
  NOT_BID_OWNER: "본인의 입찰만 취소할 수 있습니다.",
  BID_NOT_FOUND: "해당 입찰을 찾을 수 없습니다.",
  BID_NOT_ACTIVE: "이미 취소되었거나 유효하지 않은 입찰입니다.",
  AUCTION_NOT_IN_PROGRESS: "진행 중인 경매에서만 입찰을 취소할 수 있습니다.",
};

export async function placeBid(
  request: BidRequest
): Promise<BidResponse> {
  const accessToken = localStorage.getItem("accessToken");

  if (!accessToken) {
    throw new Error("로그인이 필요합니다.");
  }

  const response = await fetch("/api/bids", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${accessToken}`,
    },
    body: JSON.stringify(request),
  });

  if (response.status === 401) {
    localStorage.removeItem("accessToken");
    localStorage.removeItem("expiresAt");
    window.dispatchEvent(new Event("auth-change"));
    throw new Error("로그인이 만료되었습니다. 다시 로그인해 주세요.");
  }

  if (!response.ok) {
    // 본문이 JSON이 아니거나 모르는 code면 예전처럼 일반 문구로 안내한다
    const body: { code?: string } | null = await response.json().catch(() => null);
    throw new Error(
      BID_ERROR_MESSAGES[body?.code ?? ""] ?? "입찰 요청에 실패했습니다."
    );
  }

  return response.json();
}

export async function getBidHistory(
  productId: string
): Promise<BidHistoryResponse[]> {
  const response = await fetch(`/api/products/${productId}/bids`);

  if (!response.ok) {
    throw new Error("입찰 내역 조회에 실패했습니다.");
  }

  return response.json();
}

export async function cancelBid(
  bidId: string
): Promise<BidCancelResponse> {
  const accessToken = localStorage.getItem("accessToken");

  if (!accessToken) {
    throw new Error("로그인이 필요합니다.");
  }

  const response = await fetch(`/api/bids/${bidId}`, {
    method: "DELETE",
    headers: {
      Authorization: `Bearer ${accessToken}`,
    },
  });

  if (response.status === 401) {
    localStorage.removeItem("accessToken");
    localStorage.removeItem("expiresAt");
    window.dispatchEvent(new Event("auth-change"));
    throw new Error("로그인이 만료되었습니다. 다시 로그인해 주세요.");
  }

  if (!response.ok) {
    const body: { code?: string } | null =
      await response.json().catch(() => null);

    throw new Error(
      BID_CANCEL_ERROR_MESSAGES[body?.code ?? ""] ??
        "입찰 취소 요청에 실패했습니다."
    );
  }

  return response.json();
}