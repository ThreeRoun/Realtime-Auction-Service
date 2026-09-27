export interface Product {
  id: string;
  title: string;
  description: string;
  startingPrice: number;
  currentPrice: number;
  bidUnit: number;
  sellerId: string;
  status: string;
  startAt: string;
  endAt: string;
  winnerId: string | null;
  createdAt: string;
}