export interface Product {
  id: string;
  title: string;
  description: string;
  startingPrice: number;
  currentPrice: number;
  bidUnit: number;
  sellerId: string;
  sellerUsername: string;
  status: string;
  startAt: string;
  endAt: string;
  winnerId: string | null;
  // 낙찰자 닉네임 (엔진 이슈 #148). 낙찰자가 없거나 아직 반영되지 않은 엔진에서는 없을 수 있다.
  winnerUsername?: string | null;
  imageUrl: string | null;
  createdAt: string;
}
