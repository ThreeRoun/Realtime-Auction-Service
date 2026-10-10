export interface ProductCreateRequest {
  title: string;
  description: string;
  startingPrice: number;
  bidUnit: number;
  endAt: string;
}

export async function createProduct(request: ProductCreateRequest) {
  const accessToken = localStorage.getItem("accessToken");

  if (!accessToken) {
    throw new Error("로그인이 필요합니다.");
  }

  const response = await fetch("/api/products", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${accessToken}`,
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(`상품 등록에 실패했습니다. (${response.status})`);
  }

  return response.json();
}