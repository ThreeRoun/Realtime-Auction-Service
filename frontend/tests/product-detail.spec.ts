
import { test, expect } from "@playwright/test";

test("상품 목록에서 경매 참여를 클릭하면 상세 페이지로 이동한다", async ({ page }) => {
  const product = {
    id: "test-product-1",
    title: "테스트용 노트북",
    description: "Playwright 테스트 상품",
    startingPrice: 100000,
    currentPrice: 120000,
    bidUnit: 10000,
    sellerId: "seller-1",
    sellerUsername: "test-seller",
    status: "ACTIVE",
    startAt: "2026-11-01T10:00:00Z",
    endAt: "2026-12-01T10:00:00Z",
    winnerId: null,
    imageUrl: null,
    createdAt: "2026-11-01T10:00:00Z",
  };

  await page.route(/\/api\/products(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([product]),
    });
  });

  await page.route("**/api/products/*/bids", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([]),
    });
  });

  await page.goto("http://localhost:5173/");

  await expect(page.getByText("테스트용 노트북")).toBeVisible();

  await page.getByRole("link", { name: "경매 참여" }).click();

  await expect(page).toHaveURL(/\/products\/test-product-1$/);
  await expect(
    page.getByRole("heading", { name: "테스트용 노트북" })
  ).toBeVisible();
  await expect(page.getByText("현재가: 120,000원")).toBeVisible();
});


test("입찰 요청이 성공하면 성공 메시지가 표시된다", async ({ page }) => {
  const product = {
    id: "test-product-1",
    title: "테스트용 노트북",
    description: "Playwright 테스트 상품",
    startingPrice: 100000,
    currentPrice: 120000,
    bidUnit: 10000,
    sellerId: "seller-1",
    sellerUsername: "test-seller",
    status: "ACTIVE",
    startAt: "2026-11-01T10:00:00Z",
    endAt: "2026-12-01T10:00:00Z",
    winnerId: null,
    imageUrl: null,
    createdAt: "2026-11-01T10:00:00Z",
  };

  await page.addInitScript(() => {
    localStorage.setItem("accessToken", "playwright-test-token");
  });

  await page.route(/\/api\/products(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([product]),
    });
  });

  await page.route("**/api/products/test-product-1/bids", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([]),
    });
  });

  await page.route("**/api/bids", async (route) => {
    const request = route.request().postDataJSON();

    expect(request).toEqual({
      productId: "test-product-1",
      amount: 130000,
    });
    expect(route.request().headers()["authorization"]).toBe(
      "Bearer playwright-test-token"
    );

    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        bidId: "test-bid-1",
        productId: "test-product-1",
        bidderId: "test-user-1",
        amount: 130000,
        bidAt: "2026-11-12T10:00:00Z",
        isValid: true,
      }),
    });
  });

  await page.goto("http://localhost:5173/products/test-product-1");

  await expect(
    page.getByRole("heading", { name: "테스트용 노트북" })
  ).toBeVisible();

  await page
    .getByPlaceholder("입찰 금액을 입력하세요")
    .fill("130000");

  await page.getByRole("button", { name: "입찰하기" }).click();

  await expect(
    page.getByText("130,000원 입찰에 성공했습니다.")
  ).toBeVisible();
});


test("WebSocket 입찰 이벤트가 발생하면 상세 페이지 현재가가 갱신된다", async ({ page }) => {
  const product = {
    id: "test-product-1",
    title: "테스트용 노트북",
    description: "Playwright 테스트 상품",
    startingPrice: 100000,
    currentPrice: 120000,
    bidUnit: 10000,
    sellerId: "seller-1",
    sellerUsername: "test-seller",
    status: "ACTIVE",
    startAt: "2026-11-01T10:00:00Z",
    endAt: "2026-12-01T10:00:00Z",
    winnerId: null,
    imageUrl: null,
    createdAt: "2026-11-01T10:00:00Z",
  };

  await page.route(/\/api\/products(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([product]),
    });
  });

  await page.route("**/api/products/test-product-1/bids", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([]),
    });
  });

  let sendBidEvent: (() => void) | undefined;

  await page.routeWebSocket(/productId=test-product-1/, (ws) => {
    ws.onMessage(() => {});

    sendBidEvent = () => {
      ws.send(
        JSON.stringify({
          event: "bid_placed",
          product_id: "test-product-1",
          current_price: 150000,
        })
      );
    };
  });

  await page.goto("http://localhost:5173/products/test-product-1");

  await expect(
    page.getByRole("heading", { name: "테스트용 노트북" })
  ).toBeVisible();

  await expect(page.getByText("현재가: 120,000원")).toBeVisible();

  await expect
    .poll(() => Boolean(sendBidEvent))
    .toBe(true);

  sendBidEvent!();

  await expect(page.getByText("현재가: 150,000원")).toBeVisible();
});


test("경매 종료 이벤트 후 낙찰 결과가 표시되고 추가 입찰이 차단된다", async ({ page }) => {
  const product = {
    id: "test-product-1",
    title: "테스트용 노트북",
    description: "Playwright 테스트 상품",
    startingPrice: 100000,
    currentPrice: 120000,
    bidUnit: 10000,
    sellerId: "seller-1",
    sellerUsername: "test-seller",
    status: "ACTIVE",
    startAt: "2026-11-01T10:00:00Z",
    endAt: "2026-12-01T10:00:00Z",
    winnerId: null,
    imageUrl: null,
    createdAt: "2026-11-01T10:00:00Z",
  };

  await page.route(/\/api\/products(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([product]),
    });
  });

  await page.route("**/api/products/test-product-1/bids", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([]),
    });
  });

  let sendClosedEvent: (() => void) | undefined;

  await page.routeWebSocket(/productId=test-product-1/, (ws) => {
    ws.onMessage(() => {});

    sendClosedEvent = () => {
      ws.send(
        JSON.stringify({
          event: "auction_closed",
          product_id: "test-product-1",
          winner_id: "test-user-1",
          final_price: 150000,
        })
      );
    };
  });

  await page.goto("http://localhost:5173/products/test-product-1");

  await expect(
    page.getByRole("button", { name: "입찰하기" })
  ).toBeEnabled();

  await expect.poll(() => Boolean(sendClosedEvent)).toBe(true);

  sendClosedEvent!();

  await expect(
    page.getByRole("heading", { name: "경매 종료" })
  ).toBeVisible();

  await expect(page.getByText("낙찰자 ID: test-user-1")).toBeVisible();
  await expect(page.getByText("최종 낙찰가: 150,000원")).toBeVisible();

  await expect(
    page.getByRole("button", { name: "경매 종료" })
  ).toBeDisabled();

  await expect(
    page.getByRole("button", { name: "+10,000원" })
  ).toBeDisabled();
});
