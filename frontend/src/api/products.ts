import type { Product } from "../data/products";

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

  if (response.status === 401) {
    localStorage.removeItem("accessToken");
    localStorage.removeItem("expiresAt");
    window.dispatchEvent(new Event("auth-change"));
    throw new Error("로그인이 만료되었습니다. 다시 로그인해 주세요.");
  }

  if (!response.ok) {
    throw new Error(`상품 등록에 실패했습니다. (${response.status})`);
  }

  return response.json();
}

export async function uploadProductImage(
  productId: string,
  image: File
) {
  const accessToken = localStorage.getItem("accessToken");

  if (!accessToken) {
    throw new Error("로그인이 필요합니다.");
  }

  const formData = new FormData();
  formData.append("image", image);

  const response = await fetch(`/api/products/${productId}/image`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
    },
    body: formData,
  });

  if (!response.ok) {
    throw new Error(`이미지 업로드에 실패했습니다. (${response.status})`);
  }
}

export async function getMyProducts(
  userId: string,
  type: "registered" | "participated"
): Promise<Product[]> {
  const params = new URLSearchParams();

  if (type === "registered") {
    params.set("sellerId", userId);
  } else {
    params.set("biddedByUserId", userId);
  }

  const response = await fetch(`/api/products?${params.toString()}`);

  if (!response.ok) {
    throw new Error(`경매 목록 조회에 실패했습니다. (${response.status})`);
  }

  return response.json();
}

export async function getProductById(
  productId: string
): Promise<Product> {
  const response = await fetch(
    `/api/products/${encodeURIComponent(productId)}`
  );

  if (!response.ok) {
    throw new Error(
      `상품 상세 조회에 실패했습니다. (${response.status})`
    );
  }

  return response.json();
}
// 경매 취소 실패 사유별 안내 문구 (엔진 docs/product-api.md의 POST /api/products/{id}/cancel)
const CANCEL_ERROR_MESSAGES: Record<string, string> = {
  AUCTION_HAS_BIDS: "이미 입찰이 들어온 경매는 취소할 수 없습니다.",
  AUCTION_ALREADY_ENDED: "이미 종료된 경매는 취소할 수 없습니다.",
  NOT_PRODUCT_OWNER: "본인이 등록한 경매만 취소할 수 있습니다.",
  PRODUCT_NOT_FOUND: "경매를 찾을 수 없습니다.",
};

export async function cancelAuction(productId: string): Promise<Product> {
  const accessToken = localStorage.getItem("accessToken");

  if (!accessToken) {
    throw new Error("로그인이 필요합니다.");
  }

  const response = await fetch(
    `/api/products/${encodeURIComponent(productId)}/cancel`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
    }
  );

  if (response.status === 401) {
    localStorage.removeItem("accessToken");
    localStorage.removeItem("expiresAt");
    window.dispatchEvent(new Event("auth-change"));
    throw new Error("로그인이 만료되었습니다. 다시 로그인해 주세요.");
  }

  if (!response.ok) {
    const body: { code?: string } | null = await response
      .json()
      .catch(() => null);
    const message = body?.code ? CANCEL_ERROR_MESSAGES[body.code] : undefined;

    throw new Error(
      message ?? `경매 취소에 실패했습니다. (${response.status})`
    );
  }

  return response.json();
}
