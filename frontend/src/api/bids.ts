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
}

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

  if (!response.ok) {
    throw new Error("입찰 요청에 실패했습니다.");
  }

  return response.json();
}