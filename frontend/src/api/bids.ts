export interface BidRequest {
  productId: string;
  bidderId: string;
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
  const response = await fetch("/api/bids", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error("입찰 요청에 실패했습니다.");
  }

  return response.json();
}