import { test, expect, type Page, type WebSocketRoute } from "@playwright/test";

const PRODUCT_ID = "test-product-1";
const USER_ID = "test-user-1";

const product = {
  id: PRODUCT_ID,
  title: "크레딧 동기화 테스트 상품",
  description: "Playwright 테스트 상품",
  startingPrice: 100000,
  currentPrice: 120000,
  bidUnit: 10000,
  sellerId: "seller-1",
  sellerUsername: "test-seller",
  status: "IN_PROGRESS",
  startAt: "2026-11-01T10:00:00Z",
  endAt: new Date(
    Date.now() + 365 * 24 * 60 * 60 * 1000
  ).toISOString(),
  winnerId: null,
  imageUrl: null,
  createdAt: "2026-11-01T10:00:00Z",
};

async function mockCreditPage(
  page: Page,
  initialAvailableCredit = 500000
) {
  let availableCredit = initialAvailableCredit;
  let profileRequestCount = 0;

  await page.addInitScript((userId) => {
    const header = btoa(JSON.stringify({ alg: "HS256", typ: "JWT" }));
    const payload = btoa(
      JSON.stringify({
        sub: userId,
        exp: Math.floor(Date.now() / 1000) + 3600,
      })
    );

    localStorage.setItem(
      "accessToken",
      `${header}.${payload}.test-signature`
    );
    localStorage.setItem(
      "expiresAt",
      new Date(Date.now() + 3600000).toISOString()
    );
  }, USER_ID);

  await page.route(/\/api\/products(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      json: [product],
    });
  });

  await page.route(`**/api/products/${PRODUCT_ID}`, async (route) => {
    await route.fulfill({
      status: 200,
      json: product,
    });
  });

  await page.route(`**/api/products/${PRODUCT_ID}/bids`, async (route) => {
    await route.fulfill({
      status: 200,
      json: [],
    });
  });

  await page.route(`**/api/users/${USER_ID}`, async (route) => {
    profileRequestCount += 1;

    await route.fulfill({
      status: 200,
      json: {
        id: USER_ID,
        username: "test-user",
        email: "test@example.com",
        credit: 500000,
        availableCredit,
        createdAt: "2026-11-01T10:00:00Z",
      },
    });
  });

  return {
    setAvailableCredit(value: number) {
      availableCredit = value;
    },
    getProfileRequestCount() {
      return profileRequestCount;
    },
  };
}

test("내 입찰이 성공하면 사용 가능 크레딧이 즉시 갱신된다", async ({ page }) => {
  const credit = await mockCreditPage(page);

  await page.routeWebSocket(/productId=test-product-1/, () => {});
  await page.routeWebSocket(/scope=lobby/, () => {});

  await page.route("**/api/bids", async (route) => {
    credit.setAvailableCredit(370000);

    await route.fulfill({
      status: 201,
      json: {
        bidId: "test-bid-1",
        productId: PRODUCT_ID,
        bidderId: USER_ID,
        amount: 130000,
        bidAt: new Date().toISOString(),
        isValid: true,
      },
    });
  });

  await page.goto(`http://localhost:5173/products/${PRODUCT_ID}`);

  await expect(page.getByText(/500,000원/)).toBeVisible();

  await page
    .getByPlaceholder("입찰 금액을 입력하세요")
    .fill("130000");

  await page.getByRole("button", { name: "입찰하기" }).click();

  await expect(
    page.getByText("130,000원 입찰에 성공했습니다.")
  ).toBeVisible();

  await expect(page.getByText(/370,000원/)).toBeVisible();
});

test("현재 경매에서 다른 입찰자에게 추월당하면 사용 가능 크레딧이 갱신된다", async ({ page }) => {
  const credit = await mockCreditPage(page, 380000);

  await page.route(`**/api/products/${PRODUCT_ID}/bids`, async (route) => {
    await route.fulfill({
      status: 200,
      json: [
        {
          bidId: "my-bid-1",
          bidderId: USER_ID,
          bidderUsername: "test-user",
          amount: 120000,
          bidAt: new Date().toISOString(),
          isValid: true,
        },
      ],
    });
  });

  let sendBidEvent: (() => void) | undefined;

  await page.routeWebSocket(/productId=test-product-1/, (ws) => {
    sendBidEvent = () => {
      ws.send(
        JSON.stringify({
          event: "bid_placed",
          product_id: PRODUCT_ID,
          current_price: 130000,
          bidder_id: "other-user-1",
          bid_at: new Date().toISOString(),
        })
      );
    };
  });

  await page.routeWebSocket(/scope=lobby/, () => {});

  await page.goto(`http://localhost:5173/products/${PRODUCT_ID}`);

  await expect(page.getByText(/380,000원/)).toBeVisible();
  await expect.poll(() => Boolean(sendBidEvent)).toBe(true);

  credit.setAvailableCredit(500000);
  sendBidEvent!();

  await expect(page.getByText("현재가: 130,000원")).toBeVisible();
  await expect(page.getByText(/500,000원/)).toBeVisible();
});

test("다른 경매에서 추월당하면 현재 상세 페이지의 사용 가능 크레딧이 갱신된다", async ({ page }) => {
  const credit = await mockCreditPage(page, 300000);

  await page.routeWebSocket(/productId=test-product-1/, () => {});

  const lobbySockets = new Set<WebSocketRoute>();

  await page.routeWebSocket(/scope=lobby/, (ws) => {
    lobbySockets.add(ws);

    ws.onClose(() => {
      lobbySockets.delete(ws);
    });
  });

  const sendLobbyBidEvent = () => {
    for (const ws of lobbySockets) {
      ws.send(
        JSON.stringify({
          event: "bid_placed",
          product_id: "other-product-1",
          current_price: 210000,
        })
      );
    }
  };

  await page.goto(`http://localhost:5173/products/${PRODUCT_ID}`);

  await expect(page.getByText(/300,000원/)).toBeVisible();
  await expect.poll(() => lobbySockets.size).toBeGreaterThanOrEqual(2);

  credit.setAvailableCredit(500000);
  sendLobbyBidEvent();

  await expect(page.getByText(/500,000원/)).toBeVisible();
  await expect(page.getByText("현재가: 120,000원")).toBeVisible();
});

test("다른 경매가 종료되면 사용 가능 크레딧이 갱신된다", async ({ page }) => {
  const credit = await mockCreditPage(page, 300000);

  await page.routeWebSocket(/productId=test-product-1/, () => {});

  let sendClosedEvent: (() => void) | undefined;

  await page.routeWebSocket(/scope=lobby/, (ws) => {
    sendClosedEvent = () => {
      ws.send(
        JSON.stringify({
          event: "auction_closed",
          product_id: "other-product-1",
          final_price: null,
        })
      );
    };
  });

  await page.goto(`http://localhost:5173/products/${PRODUCT_ID}`);

  await expect(page.getByText(/300,000원/)).toBeVisible();
  await expect.poll(() => Boolean(sendClosedEvent)).toBe(true);

  credit.setAvailableCredit(500000);
  sendClosedEvent!();

  await expect(page.getByText(/500,000원/)).toBeVisible();
  await expect(page.getByText("현재가: 120,000원")).toBeVisible();

  await expect(
    page.getByRole("button", { name: "입찰하기" })
  ).toBeEnabled();
});

test("입찰 취소 이벤트가 발생하면 사용 가능 크레딧이 갱신된다", async ({ page }) => {
  const credit = await mockCreditPage(page, 380000);

  let sendCancelledEvent: (() => void) | undefined;

  await page.routeWebSocket(/productId=test-product-1/, (ws) => {
    sendCancelledEvent = () => {
      ws.send(
        JSON.stringify({
          event: "bid_cancelled",
          product_id: PRODUCT_ID,
          current_price: 100000,
          bidder_id: null,
          cancelled_bid_ids: ["my-bid-1"],
        })
      );
    };
  });

  await page.routeWebSocket(/scope=lobby/, () => {});

  await page.goto(`http://localhost:5173/products/${PRODUCT_ID}`);

  await expect(page.getByText(/380,000원/)).toBeVisible();
  await expect.poll(() => Boolean(sendCancelledEvent)).toBe(true);

  credit.setAvailableCredit(500000);
  sendCancelledEvent!();

  await expect(page.getByText(/500,000원/)).toBeVisible();
});

test("WebSocket이 재연결되면 사용 가능 크레딧이 갱신된다", async ({ page }) => {
  const credit = await mockCreditPage(page, 380000);

  let connectionCount = 0;
  let disconnectSocket: (() => void) | undefined;

  await page.routeWebSocket(/productId=test-product-1/, (ws) => {
    connectionCount += 1;

    disconnectSocket = () => {
      ws.close({
        code: 1011,
        reason: "Test connection failure",
      });
    };
  });

  await page.routeWebSocket(/scope=lobby/, () => {});

  await page.goto(`http://localhost:5173/products/${PRODUCT_ID}`);

  await expect(page.getByText(/380,000원/)).toBeVisible();
  await expect.poll(() => connectionCount).toBe(1);
  await expect.poll(() => Boolean(disconnectSocket)).toBe(true);

  disconnectSocket!();

  credit.setAvailableCredit(500000);

  await expect.poll(() => connectionCount, {
    timeout: 10000,
  }).toBeGreaterThanOrEqual(2);

  await expect(page.getByText(/500,000원/)).toBeVisible();
});

test("연속 입찰 이벤트가 발생하면 사용자 정보 조회 요청이 통합된다", async ({ page }) => {
  const credit = await mockCreditPage(page, 380000);

  let sendBidEvents: (() => void) | undefined;

  await page.routeWebSocket(/productId=test-product-1/, (ws) => {
    sendBidEvents = () => {
      for (let i = 0; i < 5; i++) {
        ws.send(
          JSON.stringify({
            event: "bid_placed",
            product_id: PRODUCT_ID,
            current_price: 130000 + i * 10000,
            bidder_id: "other-user-1",
            bid_at: new Date().toISOString(),
          })
        );
      }
    };
  });

  await page.routeWebSocket(/scope=lobby/, () => {});

  await page.goto(`http://localhost:5173/products/${PRODUCT_ID}`);

  await expect(page.getByText(/380,000원/)).toBeVisible();
  await expect.poll(() => Boolean(sendBidEvents)).toBe(true);

  const initialRequestCount = credit.getProfileRequestCount();

  credit.setAvailableCredit(500000);
  sendBidEvents!();

  await expect(page.getByText("현재가: 170,000원")).toBeVisible();
  await expect(page.getByText(/500,000원/)).toBeVisible();

  await page.waitForTimeout(600);

  expect(credit.getProfileRequestCount()).toBe(
    initialRequestCount + 1
  );
});