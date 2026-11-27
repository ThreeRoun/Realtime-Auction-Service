import { test, expect, type Page } from "@playwright/test";

const PRODUCT_ID = "test-product-1";
const USER_ID = "test-user-1";
const OTHER_USER_ID = "test-user-2";

const product = {
  id: PRODUCT_ID,
  title: "입찰 취소 테스트 상품",
  description: "Playwright 입찰 취소 테스트",
  startingPrice: 100000,
  currentPrice: 150000,
  bidUnit: 10000,
  sellerId: "seller-1",
  sellerUsername: "test-seller",
  status: "IN_PROGRESS",
  startAt: new Date(Date.now() - 3600000).toISOString(),
  endAt: new Date(Date.now() + 3600000).toISOString(),
  winnerId: null,
  imageUrl: null,
  createdAt: new Date().toISOString(),
};

const initialBidHistory = [
  {
    bidId: "my-highest-bid",
    bidderId: USER_ID,
    bidderUsername: "test-user",
    amount: 150000,
    bidAt: new Date().toISOString(),
    isValid: true,
  },
  {
    bidId: "other-bid",
    bidderId: OTHER_USER_ID,
    bidderUsername: "other-user",
    amount: 140000,
    bidAt: new Date().toISOString(),
    isValid: true,
  },
  {
    bidId: "my-previous-bid",
    bidderId: USER_ID,
    bidderUsername: "test-user",
    amount: 130000,
    bidAt: new Date().toISOString(),
    isValid: true,
  },
  {
    bidId: "my-invalid-bid",
    bidderId: USER_ID,
    bidderUsername: "test-user",
    amount: 120000,
    bidAt: new Date().toISOString(),
    isValid: false,
  },
];

async function mockBidCancelPage(
  page: Page,
  status = "IN_PROGRESS"
) {
  let bidHistory = initialBidHistory.map((bid) => ({ ...bid }));
  let availableCredit = 350000;
  let currentPrice = 150000;

  await page.addInitScript((userId) => {
    const header = btoa(
      JSON.stringify({ alg: "HS256", typ: "JWT" })
    );

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
      json: [{ ...product, status, currentPrice }],
    });
  });

  await page.route(`**/api/products/${PRODUCT_ID}`, async (route) => {
    await route.fulfill({
      status: 200,
      json: { ...product, status, currentPrice },
    });
  });

  await page.route(`**/api/products/${PRODUCT_ID}/bids`, async (route) => {
    await route.fulfill({
      status: 200,
      json: bidHistory,
    });
  });

  await page.route(`**/api/users/${USER_ID}`, async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        id: USER_ID,
        username: "test-user",
        email: "test@example.com",
        credit: 500000,
        availableCredit,
        createdAt: new Date().toISOString(),
      },
    });
  });

  return {
    setAvailableCredit(value: number) {
      availableCredit = value;
    },

    setCurrentPrice(value: number) {
      currentPrice = value;
    },

    cancelBids(bidIds: string[]) {
      bidHistory = bidHistory.map((bid) =>
        bidIds.includes(bid.bidId)
          ? { ...bid, isValid: false }
          : bid
      );
    },
  };
}

test("본인의 유효한 입찰에만 취소 버튼이 표시된다", async ({ page }) => {
  await mockBidCancelPage(page);

  await page.routeWebSocket(/productId=test-product-1/, () => {});
  await page.routeWebSocket(/scope=lobby/, () => {});

  await page.goto(`/products/${PRODUCT_ID}`);

  const history = page.locator(".bid-history");

  await expect(history.getByRole("listitem")).toHaveCount(4);

  await expect(
    history.getByRole("button", { name: "입찰 취소", exact: true })
  ).toHaveCount(2);

  const highestBid = history.getByRole("listitem").filter({
    hasText: "150,000원",
  });

  const previousBid = history.getByRole("listitem").filter({
    hasText: "130,000원",
  });

  const otherBid = history.getByRole("listitem").filter({
    hasText: "140,000원",
  });

  const invalidBid = history.getByRole("listitem").filter({
    hasText: "120,000원",
  });

  await expect(highestBid.getByRole("button")).toBeVisible();
  await expect(previousBid.getByRole("button")).toBeVisible();
  await expect(otherBid.getByRole("button")).toHaveCount(0);
  await expect(invalidBid.getByRole("button")).toHaveCount(0);
});

for (const status of ["SOLD", "UNSOLD", "COMPLETED", "CANCELLED"]) {
  test(`${status} 상태의 경매에서는 입찰 취소 버튼이 표시되지 않는다`, async ({ page }) => {
    await mockBidCancelPage(page, status);

    await page.routeWebSocket(/productId=test-product-1/, () => {});
    await page.routeWebSocket(/scope=lobby/, () => {});

    await page.goto(`/products/${PRODUCT_ID}`);

    const history = page.locator(".bid-history");

    await expect(history.getByRole("listitem")).toHaveCount(4);

    await expect(
      history.getByRole("button", { name: "입찰 취소", exact: true })
    ).toHaveCount(0);
  });
}

test("최고 입찰 취소 확인 창에서 거절하면 API 요청을 보내지 않는다", async ({ page }) => {
  await mockBidCancelPage(page);

  await page.routeWebSocket(/productId=test-product-1/, () => {});
  await page.routeWebSocket(/scope=lobby/, () => {});

  let cancelRequestCount = 0;

  await page.route("**/api/bids/my-highest-bid", async (route) => {
    cancelRequestCount += 1;
    await route.fulfill({
      status: 500,
      json: { code: "UNEXPECTED_REQUEST" },
    });
  });

  await page.goto(`/products/${PRODUCT_ID}`);

  const highestBid = page
    .locator(".bid-history")
    .getByRole("listitem")
    .filter({ hasText: "150,000원" });

  const cancelButton = highestBid.getByRole("button", {
    name: "입찰 취소",
    exact: true,
  });

  await expect(cancelButton).toBeVisible();

  let confirmMessage = "";

  page.once("dialog", async (dialog) => {
    confirmMessage = dialog.message();
    await dialog.dismiss();
  });

  await cancelButton.click();

  expect(confirmMessage).toContain(
    "최고 입찰을 취소하면 현재가가 내려갑니다."
  );

  expect(cancelRequestCount).toBe(0);

  await expect(cancelButton).toBeEnabled();
  await expect(highestBid).toContainText("유효 입찰");

  await expect(
    page.locator(".bid-info")
  ).toContainText("현재가: 150,000원");
});

test("최고 입찰 취소에 성공하면 현재가, 최고 입찰자, 입찰 이력, 크레딧이 갱신된다", async ({ page }) => {
  const mock = await mockBidCancelPage(page);

  await page.routeWebSocket(/productId=test-product-1/, () => {});
  await page.routeWebSocket(/scope=lobby/, () => {});

  let cancelRequestCount = 0;

  await page.route("**/api/bids/my-highest-bid", async (route) => {
    expect(route.request().method()).toBe("DELETE");

    expect(
      route.request().headers()["authorization"]
    ).toMatch(/^Bearer /);

    cancelRequestCount += 1;

    mock.cancelBids(["my-highest-bid"]);
    mock.setCurrentPrice(140000);
    mock.setAvailableCredit(500000);

    await route.fulfill({
      status: 200,
      json: {
        bidId: "my-highest-bid",
        productId: PRODUCT_ID,
        currentPrice: 140000,
        topBidderId: OTHER_USER_ID,
        cancelledBidIds: ["my-highest-bid"],
        availableCredit: 500000,
      },
    });
  });

  await page.goto(`/products/${PRODUCT_ID}`);

  const history = page.locator(".bid-history");
  const bidInfo = page.locator(".bid-info");

  const highestBid = history.getByRole("listitem").filter({
    hasText: "150,000원",
  });

  await expect(bidInfo).toContainText("현재가: 150,000원");
  await expect(bidInfo).toContainText("최고 입찰자: test-user");

  await expect(
    page.getByText("사용 가능 크레딧: 350,000원", { exact: true })
  ).toBeVisible();

  page.once("dialog", async (dialog) => {
    await dialog.accept();
  });

  await highestBid.getByRole("button", {
    name: "입찰 취소",
    exact: true,
  }).click();

  await expect(
    page.getByText("입찰이 취소되었습니다.", { exact: true })
  ).toBeVisible();

  await expect(bidInfo).toContainText("현재가: 140,000원");
  await expect(bidInfo).toContainText("최고 입찰자: other-user");

  await expect(highestBid).toContainText("무효 입찰");
  await expect(highestBid.getByRole("button")).toHaveCount(0);

  await expect(history.getByRole("listitem")).toHaveCount(4);

  await expect(
    page.getByText("사용 가능 크레딧: 500,000원", { exact: true })
  ).toBeVisible();

  expect(cancelRequestCount).toBe(1);
});

const cancelErrorCases = [
  {
    code: "NOT_BID_OWNER",
    status: 403,
    message: "본인의 입찰만 취소할 수 있습니다.",
  },
  {
    code: "BID_NOT_FOUND",
    status: 404,
    message: "해당 입찰을 찾을 수 없습니다.",
  },
  {
    code: "BID_NOT_ACTIVE",
    status: 409,
    message: "이미 취소되었거나 유효하지 않은 입찰입니다.",
  },
  {
    code: "AUCTION_NOT_IN_PROGRESS",
    status: 409,
    message: "진행 중인 경매에서만 입찰을 취소할 수 있습니다.",
  },
  {
    code: "UNKNOWN_ERROR",
    status: 500,
    message: "입찰 취소 요청에 실패했습니다.",
  },
];

for (const errorCase of cancelErrorCases) {
  test(`입찰 취소 실패 시 ${errorCase.code}에 맞는 안내가 표시된다`, async ({ page }) => {
    await mockBidCancelPage(page);

    await page.routeWebSocket(/productId=test-product-1/, () => {});
    await page.routeWebSocket(/scope=lobby/, () => {});

    await page.route("**/api/bids/my-highest-bid", async (route) => {
      await route.fulfill({
        status: errorCase.status,
        json: {
          code: errorCase.code,
          message: "internal-bid-id-12345",
        },
      });
    });

    await page.goto(`/products/${PRODUCT_ID}`);

    const highestBid = page
      .locator(".bid-history")
      .getByRole("listitem")
      .filter({ hasText: "150,000원" });

    const cancelButton = highestBid.getByRole("button", {
      name: "입찰 취소",
      exact: true,
    });

    await expect(cancelButton).toBeVisible();

    page.once("dialog", async (dialog) => {
      await dialog.accept();
    });

    await cancelButton.click();

    await expect(
      page.getByText(errorCase.message, { exact: true })
    ).toBeVisible();

    await expect(
      page.getByText("internal-bid-id-12345")
    ).toHaveCount(0);

    await expect(cancelButton).toBeEnabled();
    await expect(highestBid).toContainText("유효 입찰");

    await expect(page.locator(".bid-info")).toContainText(
      "현재가: 150,000원"
    );
  });
}

test("입찰 취소 이벤트를 받으면 상세 화면의 현재가, 최고 입찰자, 입찰 이력이 갱신된다", async ({ page }) => {
  const mock = await mockBidCancelPage(page);

  let sendCancelEvent: (() => void) | undefined;

  await page.routeWebSocket(/productId=test-product-1/, (ws) => {
    sendCancelEvent = () => {
      ws.send(
        JSON.stringify({
          event: "bid_cancelled",
          product_id: PRODUCT_ID,
          current_price: 140000,
          bidder_id: OTHER_USER_ID,
          cancelled_bid_ids: ["my-highest-bid"],
        })
      );
    };
  });

  await page.routeWebSocket(/scope=lobby/, () => {});

  await page.goto(`/products/${PRODUCT_ID}`);

  const bidInfo = page.locator(".bid-info");
  const history = page.locator(".bid-history");

  await expect(bidInfo).toContainText("현재가: 150,000원");
  await expect(bidInfo).toContainText("최고 입찰자: test-user");

  await expect(history.getByRole("listitem")).toHaveCount(4);

  await expect(
    page.getByText("사용 가능 크레딧: 350,000원", { exact: true })
  ).toBeVisible();

  await expect.poll(() => Boolean(sendCancelEvent)).toBe(true);

  mock.cancelBids(["my-highest-bid"]);
  mock.setCurrentPrice(140000);
  mock.setAvailableCredit(500000);

  sendCancelEvent?.();

  await expect(bidInfo).toContainText("현재가: 140,000원");
  await expect(bidInfo).toContainText("최고 입찰자: other-user");

  const cancelledBid = history.getByRole("listitem").filter({
    hasText: "150,000원",
  });

  await expect(cancelledBid).toContainText("무효 입찰");
  await expect(cancelledBid.getByRole("button")).toHaveCount(0);

  await expect(history.getByRole("listitem")).toHaveCount(4);

  await expect(
    page.getByText("사용 가능 크레딧: 500,000원", { exact: true })
  ).toBeVisible();
});

test("입찰 취소 이벤트를 받으면 상품 목록의 현재가가 갱신된다", async ({ page }) => {
  const mock = await mockBidCancelPage(page);

  let sendLobbyEvent: (() => void) | undefined;

  await page.routeWebSocket(/scope=lobby/, (ws) => {
    sendLobbyEvent = () => {
      ws.send(
        JSON.stringify({
          event: "bid_cancelled",
          product_id: PRODUCT_ID,
          current_price: 140000,
        })
      );
    };
  });

  await page.goto("/");

  await expect(
    page.getByText(product.title, { exact: true })
  ).toBeVisible();

  await expect(
    page.getByText(/150,000원/)
  ).toBeVisible();

  await expect.poll(() => Boolean(sendLobbyEvent)).toBe(true);

  mock.cancelBids(["my-highest-bid"]);
  mock.setCurrentPrice(140000);

  sendLobbyEvent?.();

  await expect(
    page.getByText(/140,000원/)
  ).toBeVisible();

  await expect(
    page.getByText(/150,000원/)
  ).toHaveCount(0);
});

test("WebSocket 연결이 끊긴 동안 발생한 입찰 취소가 재연결 후 반영된다", async ({ page }) => {
  const mock = await mockBidCancelPage(page);

  let connectionCount = 0;
  let disconnect: (() => void) | undefined;

  await page.routeWebSocket(/productId=test-product-1/, (ws) => {
    connectionCount += 1;

    disconnect = () => {
      ws.close({ code: 1011 });
    };
  });

  await page.routeWebSocket(/scope=lobby/, () => {});

  await page.goto(`/products/${PRODUCT_ID}`);

  const bidInfo = page.locator(".bid-info");

  const highestBid = page
    .locator(".bid-history")
    .getByRole("listitem")
    .filter({ hasText: "150,000원" });

  await expect(bidInfo).toContainText("현재가: 150,000원");
  await expect(bidInfo).toContainText("최고 입찰자: test-user");

  await expect(highestBid.getByRole("button")).toBeVisible();

  await expect(
    page.getByText("사용 가능 크레딧: 350,000원", { exact: true })
  ).toBeVisible();

  await expect.poll(() => connectionCount).toBe(1);

  // WebSocket 연결 끊기
  disconnect?.();

  // 연결이 끊긴 동안 서버에서 입찰 취소 발생
  mock.cancelBids(["my-highest-bid"]);
  mock.setCurrentPrice(140000);
  mock.setAvailableCredit(500000);

  // WebSocket 자동 재연결 확인
  await expect.poll(() => connectionCount, {
    timeout: 15000,
  }).toBeGreaterThanOrEqual(2);

  // 재연결 후 REST API를 통한 상태 복구 확인
  await expect(bidInfo).toContainText("현재가: 140,000원");
  await expect(bidInfo).toContainText("최고 입찰자: other-user");

  await expect(highestBid).toContainText("무효 입찰");
  await expect(highestBid.getByRole("button")).toHaveCount(0);

  await expect(
    page.getByText("사용 가능 크레딧: 500,000원", { exact: true })
  ).toBeVisible();
});

test("최고 입찰이 아닌 본인의 입찰을 취소하면 현재가와 크레딧이 유지된다", async ({ page }) => {
  const mock = await mockBidCancelPage(page);

  await page.routeWebSocket(/productId=test-product-1/, () => {});
  await page.routeWebSocket(/scope=lobby/, () => {});

  await page.route("**/api/bids/my-previous-bid", async (route) => {
    expect(route.request().method()).toBe("DELETE");
    expect(route.request().postData()).toBeNull();

    mock.cancelBids(["my-previous-bid"]);

    await route.fulfill({
      status: 200,
      json: {
        bidId: "my-previous-bid",
        productId: PRODUCT_ID,
        currentPrice: 150000,
        topBidderId: USER_ID,
        cancelledBidIds: ["my-previous-bid"],
        availableCredit: 350000,
      },
    });
  });

  await page.goto(`/products/${PRODUCT_ID}`);

  const history = page.locator(".bid-history");
  const bidInfo = page.locator(".bid-info");

  const previousBid = history.getByRole("listitem").filter({
    hasText: "130,000원",
  });

  await expect(previousBid.getByRole("button")).toBeVisible();

  let confirmMessage = "";

  page.once("dialog", async (dialog) => {
    confirmMessage = dialog.message();
    await dialog.accept();
  });

  await previousBid.getByRole("button", {
    name: "입찰 취소",
    exact: true,
  }).click();

  await expect(
    page.getByText("입찰이 취소되었습니다.", { exact: true })
  ).toBeVisible();

  expect(confirmMessage).toBe("이 입찰을 취소하시겠습니까?");

  await expect(previousBid).toContainText("무효 입찰");
  await expect(previousBid.getByRole("button")).toHaveCount(0);

  await expect(bidInfo).toContainText("현재가: 150,000원");
  await expect(bidInfo).toContainText("최고 입찰자: test-user");

  await expect(
    page.getByText("사용 가능 크레딧: 350,000원", { exact: true })
  ).toBeVisible();

  await expect(history.getByRole("listitem")).toHaveCount(4);
});

test("연쇄 입찰 취소로 유효한 입찰이 없어지면 현재가가 시작가로 복원된다", async ({ page }) => {
  const mock = await mockBidCancelPage(page);

  await page.routeWebSocket(/productId=test-product-1/, () => {});
  await page.routeWebSocket(/scope=lobby/, () => {});

  const cancelledBidIds = [
    "my-highest-bid",
    "other-bid",
    "my-previous-bid",
  ];

  await page.route("**/api/bids/my-highest-bid", async (route) => {
    mock.cancelBids(cancelledBidIds);
    mock.setCurrentPrice(100000);
    mock.setAvailableCredit(500000);

    await route.fulfill({
      status: 200,
      json: {
        bidId: "my-highest-bid",
        productId: PRODUCT_ID,
        currentPrice: 100000,
        topBidderId: null,
        cancelledBidIds,
        availableCredit: 500000,
      },
    });
  });

  await page.goto(`/products/${PRODUCT_ID}`);

  const history = page.locator(".bid-history");
  const bidInfo = page.locator(".bid-info");

  await expect(bidInfo).toContainText("현재가: 150,000원");

  const highestBid = history.getByRole("listitem").filter({
    hasText: "150,000원",
  });

  await expect(highestBid.getByRole("button")).toBeVisible();

  page.once("dialog", async (dialog) => {
    await dialog.accept();
  });

  await highestBid.getByRole("button", {
    name: "입찰 취소",
    exact: true,
  }).click();

  await expect(
    page.getByText("입찰이 취소되었습니다.", { exact: true })
  ).toBeVisible();

  await expect(bidInfo).toContainText("현재가: 100,000원");
  await expect(bidInfo).toContainText("아직 입찰자가 없습니다.");

  await expect(
    history.getByRole("listitem").filter({
      hasText: "무효 입찰",
    })
  ).toHaveCount(4);

  await expect(
    history.getByRole("button", {
      name: "입찰 취소",
      exact: true,
    })
  ).toHaveCount(0);

  await expect(
    page.getByText("사용 가능 크레딧: 500,000원", { exact: true })
  ).toBeVisible();
});

test("이전 입찰 이력 응답이 늦게 도착해도 최신 취소 상태가 유지된다", async ({ page }) => {
  const mock = await mockBidCancelPage(page);

  let historyRequestCount = 0;
  let releaseOldResponse = () => {};

  const oldResponseGate = new Promise<void>((resolve) => {
    releaseOldResponse = resolve;
  });

  await page.route(
    `**/api/products/${PRODUCT_ID}/bids`,
    async (route) => {
      historyRequestCount += 1;

      if (historyRequestCount === 1) {
        await route.fulfill({
          status: 200,
          json: initialBidHistory,
        });
        return;
      }

      if (historyRequestCount === 2) {
        await oldResponseGate;

        await route.fulfill({
          status: 200,
          json: initialBidHistory,
        });
        return;
      }

      await route.fulfill({
        status: 200,
        json: initialBidHistory.map((bid) =>
          bid.bidId === "my-highest-bid"
            ? { ...bid, isValid: false }
            : bid
        ),
      });
    }
  );

  await page.routeWebSocket(/scope=lobby/, () => {});

  await page.routeWebSocket(/productId=test-product-1/, (ws) => {
    ws.onMessage(() => {});
  });

  await page.route("**/api/bids/my-previous-bid", async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        bidId: "my-previous-bid",
        productId: PRODUCT_ID,
        currentPrice: 150000,
        topBidderId: USER_ID,
        cancelledBidIds: ["my-previous-bid"],
        availableCredit: 350000,
      },
    });
  });

  await page.route("**/api/bids/my-highest-bid", async (route) => {
    mock.cancelBids(["my-highest-bid"]);
    mock.setCurrentPrice(140000);
    mock.setAvailableCredit(500000);

    await route.fulfill({
      status: 200,
      json: {
        bidId: "my-highest-bid",
        productId: PRODUCT_ID,
        currentPrice: 140000,
        topBidderId: OTHER_USER_ID,
        cancelledBidIds: ["my-highest-bid"],
        availableCredit: 500000,
      },
    });
  });

  await page.goto(`/products/${PRODUCT_ID}`);

  const history = page.locator(".bid-history");

  const previousBid = history.getByRole("listitem").filter({
    hasText: "130,000원",
  });

  const highestBid = history.getByRole("listitem").filter({
    hasText: "150,000원",
  });

  page.on("dialog", async (dialog) => {
    await dialog.accept();
  });

  try {
    await previousBid.getByRole("button", {
      name: "입찰 취소",
      exact: true,
    }).click();

    await expect.poll(() => historyRequestCount).toBe(2);

    await highestBid.getByRole("button", {
      name: "입찰 취소",
      exact: true,
    }).click();

    await expect.poll(() => historyRequestCount).toBe(3);

    await expect(highestBid).toContainText("무효 입찰");

    const oldResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/products/${PRODUCT_ID}/bids`)
    );

    releaseOldResponse();

    await (await oldResponse).finished();

    await page.waitForTimeout(200);

    await expect(highestBid).toContainText("무효 입찰");
    await expect(highestBid.getByRole("button")).toHaveCount(0);

    await expect(page.locator(".bid-info")).toContainText(
      "최고 입찰자: other-user"
    );
  } finally {
    releaseOldResponse();
  }
});

test("입찰 취소 응답이 늦게 도착해도 최신 현재가가 유지된다", async ({ page }) => {
  const mock = await mockBidCancelPage(page);

  let cancelRequested = false;
  let releaseCancelResponse = () => {};
  let sendEvent: (message: string) => void = () => {};

  const cancelResponseGate = new Promise<void>((resolve) => {
    releaseCancelResponse = resolve;
  });

  await page.routeWebSocket(/scope=lobby/, () => {});

  await page.routeWebSocket(/productId=test-product-1/, (ws) => {
    sendEvent = (message) => ws.send(message);
  });

  await page.route("**/api/bids/my-highest-bid", async (route) => {
    mock.cancelBids(["my-highest-bid"]);
    mock.setCurrentPrice(140000);
    mock.setAvailableCredit(500000);

    cancelRequested = true;

    await cancelResponseGate;

    await route.fulfill({
      status: 200,
      json: {
        bidId: "my-highest-bid",
        productId: PRODUCT_ID,
        currentPrice: 140000,
        topBidderId: OTHER_USER_ID,
        cancelledBidIds: ["my-highest-bid"],
        availableCredit: 500000,
      },
    });
  });

  await page.goto(`/products/${PRODUCT_ID}`);

  const bidInfo = page.locator(".bid-info");

  const highestBid = page
    .locator(".bid-history")
    .getByRole("listitem")
    .filter({ hasText: "150,000원" });

  await expect(highestBid.getByRole("button")).toBeVisible();

  page.once("dialog", async (dialog) => {
    await dialog.accept();
  });

  try {
    await highestBid.getByRole("button", {
      name: "입찰 취소",
      exact: true,
    }).click();

    await expect.poll(() => cancelRequested).toBe(true);

    mock.setCurrentPrice(160000);

    sendEvent(JSON.stringify({
      event: "bid_placed",
      product_id: PRODUCT_ID,
      current_price: 160000,
      bidder_id: OTHER_USER_ID,
    }));

    await expect(bidInfo).toContainText("현재가: 160,000원");

    const priceResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/products/${PRODUCT_ID}`) &&
        response.request().method() === "GET"
    );

    releaseCancelResponse();

    await expect(
      page.getByText("입찰이 취소되었습니다.", { exact: true })
    ).toBeVisible();

    await (await priceResponse).finished();

    await expect(bidInfo).toContainText("현재가: 160,000원");
    await expect(highestBid).toContainText("무효 입찰");
  } finally {
    releaseCancelResponse();
  }
});