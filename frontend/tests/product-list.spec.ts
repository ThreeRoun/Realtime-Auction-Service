import { test, expect } from "@playwright/test";

test("상품 목록 페이지가 정상적으로 표시된다", async ({ page }) => {
  await page.route("**/api/products", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([]),
    });
  });

  await page.goto("http://localhost:5173/");

  await expect(
    page.getByRole("heading", { name: "진행 중인 경매" })
  ).toBeVisible();

  await expect(
    page.getByText("상품 목록을 불러오는 중입니다...")
  ).not.toBeVisible();
});

test("상품 목록에 상품 카드가 표시된다", async ({ page }) => {
  await page.route("**/api/products", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
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
          createdAt: "2026-11-01T10:00:00Z"
        }
      ])
    });
  });

  await page.goto("http://localhost:5173/");

  await expect(page.getByText("테스트용 노트북")).toBeVisible();
  await expect(page.getByText("현재가: 120,000원")).toBeVisible();
});

test("실시간 입찰 이벤트가 상품 현재가에 반영된다", async ({ page }) => {
  await page.route("**/api/products", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([{
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
        createdAt: "2026-11-01T10:00:00Z"
      }])
    });
  });

  let sendBidEvent!: () => void;
  const socketReady = new Promise<void>((resolve) => {
    sendBidEvent = resolve;
  });

  await page.routeWebSocket(/scope=lobby/, (ws) => {
    ws.onMessage(() => {});
    sendBidEvent = () => {
      ws.send(JSON.stringify({
        event: "bid_placed",
        product_id: "test-product-1",
        current_price: 150000
      }));
    };
    void socketReady;
  });

  await page.goto("http://localhost:5173/");

  await expect(page.getByText("현재가: 120,000원")).toBeVisible();

  sendBidEvent();

  await expect(page.getByText("현재가: 150,000원")).toBeVisible();
});

test("경매 마감 이벤트가 발생하면 해당 상품이 목록에서 제거된다", async ({ page }) => {
  await page.route("**/api/products", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
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
          createdAt: "2026-11-01T10:00:00Z"
        },
        {
          id: "test-product-2",
          title: "테스트용 카메라",
          description: "Playwright 테스트 상품",
          startingPrice: 50000,
          currentPrice: 70000,
          bidUnit: 5000,
          sellerId: "seller-1",
          sellerUsername: "test-seller",
          status: "ACTIVE",
          startAt: "2026-11-01T10:00:00Z",
          endAt: "2026-12-01T10:00:00Z",
          winnerId: null,
          imageUrl: null,
          createdAt: "2026-11-01T10:00:00Z"
        }
      ])
    });
  });

  // StrictMode에서는 소켓이 두 번 열리므로 마지막으로 연결된 소켓으로 이벤트를 보낸다
  let sendClosedEvent!: () => void;

  await page.routeWebSocket(/scope=lobby/, (ws) => {
    ws.onMessage(() => {});
    sendClosedEvent = () => {
      ws.send(JSON.stringify({
        event: "auction_closed",
        product_id: "test-product-1",
        final_price: 150000
      }));
    };
  });

  await page.goto("http://localhost:5173/");

  await expect(page.getByText("테스트용 노트북")).toBeVisible();
  await expect(page.getByText("테스트용 카메라")).toBeVisible();

  sendClosedEvent();

  await expect(page.getByText("테스트용 노트북")).not.toBeVisible();
  await expect(page.getByText("테스트용 카메라")).toBeVisible();
});
