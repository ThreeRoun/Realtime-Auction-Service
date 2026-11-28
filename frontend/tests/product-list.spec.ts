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
    page.getByRole("heading", { name: "경매 상품 목록" })
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
          status: "IN_PROGRESS",
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
        status: "IN_PROGRESS",
        startAt: "2026-11-01T10:00:00Z",
        endAt: "2026-12-01T10:00:00Z",
        winnerId: null,
        imageUrl: null,
        createdAt: "2026-11-01T10:00:00Z"
      }])
    });
  });

  
let sendBidEvent: (() => void) | undefined;

await page.routeWebSocket(/scope=lobby/, (ws) => {
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


  await page.goto("http://localhost:5173/");

  await expect(page.getByText("현재가: 120,000원")).toBeVisible();

  await expect.poll(() => Boolean(sendBidEvent)).toBe(true);

  sendBidEvent!();

  await expect(page.getByText("현재가: 150,000원")).toBeVisible();
});

test("경매 마감 이벤트가 발생하면 해당 상품이 목록에서 제거된다", async ({ page }) => {
  let auctionClosed = false;
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
          status: "IN_PROGRESS",
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
          status: "IN_PROGRESS",
          startAt: "2026-11-01T10:00:00Z",
          endAt: "2026-12-01T10:00:00Z",
          winnerId: null,
          imageUrl: null,
          createdAt: "2026-11-01T10:00:00Z"
        }
      ].filter((product) =>
        !auctionClosed || product.id !== "test-product-1"
      ))
    });
  });

  // StrictMode에서는 소켓이 두 번 열리므로 마지막으로 연결된 소켓으로 이벤트를 보낸다

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

  auctionClosed = true;
  sendClosedEvent();

  await expect(page.getByText("테스트용 노트북")).not.toBeVisible();
  await expect(page.getByText("테스트용 카메라")).toBeVisible();
});

test("진행 중과 종료 탭을 전환하고 새로고침 및 뒤로 가기에도 선택한 탭이 유지된다", async ({ page }) => {
  const requestedStatuses: string[] = [];

  await page.route(/\/api\/products(\?.*)?$/, async (route) => {
    const url = new URL(route.request().url());
    requestedStatuses.push(url.searchParams.get("status") ?? "active");

    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([]),
    });
  });

  await page.goto("http://localhost:5173/");

  const activeTab = page.getByRole("button", { name: "진행 중", exact: true });
  const endedTab = page.getByRole("button", { name: "종료", exact: true });

  await expect(activeTab).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByText("현재 진행 중인 경매가 없습니다.")).toBeVisible();
  expect(requestedStatuses).toContain("active");

  await endedTab.click();

  await expect(page).toHaveURL(/\?tab=ended$/);
  await expect(endedTab).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByText("종료된 경매가 없습니다.")).toBeVisible();
  expect(requestedStatuses).toContain("ended");

  await page.reload();

  await expect(endedTab).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByText("종료된 경매가 없습니다.")).toBeVisible();

  await activeTab.click();

  await expect(page).toHaveURL("http://localhost:5173/");
  await expect(activeTab).toHaveAttribute("aria-pressed", "true");

  await page.goBack();

  await expect(page).toHaveURL(/\?tab=ended$/);
  await expect(endedTab).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByText("종료된 경매가 없습니다.")).toBeVisible();
});

test("종료 탭에서 종료된 경매 상품과 상태 배지가 올바르게 표시된다", async ({ page }) => {
  const baseProduct = {
    description: "Playwright 종료 경매 테스트",
    startingPrice: 100000,
    currentPrice: 120000,
    bidUnit: 10000,
    sellerId: "seller-1",
    sellerUsername: "test-seller",
    startAt: "2026-01-01T10:00:00Z",
    endAt: "2026-01-02T10:00:00Z",
    winnerId: null,
    imageUrl: null,
    createdAt: "2026-01-01T10:00:00Z",
  };

  const endedProducts = [
    {
      ...baseProduct,
      id: "sold-product",
      title: "낙찰 테스트 상품",
      status: "SOLD",
      winnerId: "buyer-1",
    },
    {
      ...baseProduct,
      id: "unsold-product",
      title: "유찰 테스트 상품",
      status: "UNSOLD",
    },
    {
      ...baseProduct,
      id: "completed-product",
      title: "완료 테스트 상품",
      status: "COMPLETED",
      winnerId: "buyer-1",
    },
    {
      ...baseProduct,
      id: "cancelled-product",
      title: "취소 테스트 상품",
      status: "CANCELLED",
    },
  ];

  await page.route(/\/api\/products(\?.*)?$/, async (route) => {
    const url = new URL(route.request().url());
    const isEnded = url.searchParams.get("status") === "ended";

    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(isEnded ? endedProducts : []),
    });
  });

  await page.goto("http://localhost:5173/");

  await expect(page.locator(".product-card")).toHaveCount(0);

  await page.getByRole("button", { name: "종료", exact: true }).click();

  await expect(page.locator(".product-card")).toHaveCount(4);

  await expect(
    page.locator(".auction-status-badge.status-sold")
  ).toHaveText("낙찰");

  await expect(
    page.locator(".auction-status-badge.status-unsold")
  ).toHaveText("유찰");

  await expect(
    page.locator(".auction-status-badge.status-completed")
  ).toHaveText("완료");

  await expect(
    page.locator(".auction-status-badge.status-cancelled")
  ).toHaveText("경매 취소");

  await expect(
    page.getByText("종료 시 가격: 120,000원", { exact: true })
  ).toHaveCount(4);

  await expect(
    page.getByRole("link", { name: "상세 보기", exact: true })
  ).toHaveCount(4);

  await expect(
    page.getByRole("link", { name: "경매 참여", exact: true })
  ).toHaveCount(0);
});

test("경매 취소 이벤트가 발생하면 진행 중 목록에서 제거되고 종료 목록에 표시된다", async ({ page }) => {
  let auctionCancelled = false;

  const product = {
    id: "cancel-test-product",
    title: "실시간 취소 테스트 상품",
    description: "Playwright 경매 취소 테스트",
    startingPrice: 100000,
    currentPrice: 100000,
    bidUnit: 10000,
    sellerId: "seller-1",
    sellerUsername: "test-seller",
    status: "IN_PROGRESS",
    startAt: "2026-01-01T10:00:00Z",
    endAt: "2099-12-01T10:00:00Z",
    winnerId: null,
    imageUrl: null,
    createdAt: "2026-01-01T10:00:00Z",
  };

  await page.route(/\/api\/products(\?.*)?$/, async (route) => {
    const url = new URL(route.request().url());
    const isEnded = url.searchParams.get("status") === "ended";

    const products = isEnded
      ? auctionCancelled
        ? [{ ...product, status: "CANCELLED" }]
        : []
      : auctionCancelled
        ? []
        : [product];

    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(products),
    });
  });

  let sendCancelledEvent: (() => void) | undefined;

  await page.routeWebSocket(/scope=lobby/, (ws) => {
    ws.onMessage(() => {});

    sendCancelledEvent = () => {
      ws.send(JSON.stringify({
        event: "auction_cancelled",
        product_id: product.id,
      }));
    };
  });

  await page.goto("http://localhost:5173/");

  await expect(page.getByText(product.title)).toBeVisible();

  await expect.poll(() => Boolean(sendCancelledEvent)).toBe(true);

  auctionCancelled = true;
  sendCancelledEvent!();

  await expect(page.getByText(product.title)).not.toBeVisible();

  await expect(
    page.getByText("현재 진행 중인 경매가 없습니다.")
  ).toBeVisible();

  await page.getByRole("button", { name: "종료", exact: true }).click();

  await expect(page.getByText(product.title)).toBeVisible();

  await expect(
    page.locator(".auction-status-badge.status-cancelled")
  ).toHaveText("경매 취소");
});