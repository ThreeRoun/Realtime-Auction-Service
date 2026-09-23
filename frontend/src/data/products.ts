export interface Product {
  id: number;
  title: string;
  description: string;
  startingPrice: number;
  currentPrice: number;
  bidUnit: number;
  endAt: string;
  status: "active" | "extended" | "closed";
}

export const products: Product[] = [
  {
    id: 1,
    title: "무선 헤드폰",
    description: "상태 좋은 무선 헤드폰입니다.",
    startingPrice: 30000,
    currentPrice: 45000,
    bidUnit: 5000,
    endAt: "2026-09-30 18:00",
    status: "active",
  },
  {
    id: 2,
    title: "기계식 키보드",
    description: "깔끔하게 사용한 기계식 키보드입니다.",
    startingPrice: 40000,
    currentPrice: 55000,
    bidUnit: 5000,
    endAt: "2026-09-30 20:00",
    status: "active",
  },
  {
    id: 3,
    title: "게이밍 마우스",
    description: "사용감이 적은 게이밍 마우스입니다.",
    startingPrice: 20000,
    currentPrice: 30000,
    bidUnit: 2000,
    endAt: "2026-10-01 15:00",
    status: "active",
  },
];