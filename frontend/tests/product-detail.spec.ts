import { test, expect } from "@playwright/test";

// 마감 시각이 지나면 입찰 버튼이 비활성화되므로(이슈 #153), 입찰이 가능한 상품의 마감은 고정 날짜가 아니라
// "지금부터 1년 뒤"로 둔다. 고정 날짜를 쓰면 그 날짜가 지나는 순간 테스트가 깨진다.
const FAR_FUTURE_END_AT = new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toISOString();

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
    status: "IN_PROGRESS",
    startAt: "2026-11-01T10:00:00Z",
    endAt: FAR_FUTURE_END_AT,
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
    status: "IN_PROGRESS",
    startAt: "2026-11-01T10:00:00Z",
    endAt: FAR_FUTURE_END_AT,
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
    status: "IN_PROGRESS",
    startAt: "2026-11-01T10:00:00Z",
    endAt: FAR_FUTURE_END_AT,
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
    status: "IN_PROGRESS",
    startAt: "2026-11-01T10:00:00Z",
    endAt: FAR_FUTURE_END_AT,
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
    page.getByPlaceholder("입찰 금액을 입력하세요")
  ).toHaveCount(0);

  await expect(
    page.getByRole("button", { name: "입찰하기" })
  ).toHaveCount(0);

  await expect(
    page.getByRole("button", { name: "경매 종료" })
  ).toHaveCount(0);

  await expect(
    page.getByRole("button", { name: "+10,000원" })
  ).toHaveCount(0);
});

// 이슈 #150 - 입찰이 실패했을 때 "입찰 요청에 실패했습니다." 하나로 뭉뚱그리지 않고 엔진이 내려주는 사유(code)별로 안내한다.
// 엔진의 message에는 상품 UUID가 섞여 있어서 화면에 그대로 보여주면 안 되므로, 내부 ID가 노출되지 않는 것도 함께 확인한다.
const bidTestProduct = {
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
  endAt: FAR_FUTURE_END_AT,
  winnerId: null,
  imageUrl: null,
  createdAt: "2026-11-01T10:00:00Z",
};

const PRODUCT_UUID_IN_ENGINE_MESSAGE = "3f1e2b7a-0000-4000-8000-000000000001";

for (const { code, status, expected } of [
  {
    code: "SELF_BID_NOT_ALLOWED",
    status: 403,
    expected: "본인이 등록한 상품에는 입찰할 수 없습니다.",
  },
  {
    code: "BID_COOLDOWN_ACTIVE",
    status: 429,
    expected: "방금 입찰하셨습니다. 잠시 후 다시 입찰해 주세요.",
  },
  {
    code: "AUCTION_NOT_IN_PROGRESS",
    status: 409,
    expected: "진행 중인 경매가 아닙니다.",
  },
  {
    code: "SOMETHING_UNKNOWN",
    status: 500,
    expected: "입찰 요청에 실패했습니다.",
  },
]) {
  test(`입찰이 ${code}(${status})로 거절되면 사유에 맞는 안내가 표시되고 내부 ID는 노출되지 않는다`, async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem("accessToken", "playwright-test-token");
    });

    await page.route(/\/api\/products(?:\?.*)?$/, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([bidTestProduct]),
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
      await route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify({
          code,
          message: `엔진 내부 메시지: ${PRODUCT_UUID_IN_ENGINE_MESSAGE}`,
        }),
      });
    });

    await page.goto("http://localhost:5173/products/test-product-1");
    await page.getByPlaceholder("입찰 금액을 입력하세요").fill("130000");
    await page.getByRole("button", { name: "입찰하기" }).click();

    await expect(page.getByText(expected)).toBeVisible();
    await expect(page.getByText(PRODUCT_UUID_IN_ENGINE_MESSAGE)).toHaveCount(0);
  });
}

// 내가 화면을 보는 사이 다른 사람이 먼저 올린 경우: 화면의 현재가(120,000원)로는 최소 입찰가가 130,000원이지만
// 서버가 알려준 값(135,000원)이 정확하므로 그 값을 보여준다.
test("가격을 넘지 못한 입찰이면 서버가 알려준 최소 입찰가를 보여준다", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("accessToken", "playwright-test-token");
  });

  await page.route(/\/api\/products(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([bidTestProduct]),
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
    await route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify({
        bidId: "test-bid-1",
        productId: "test-product-1",
        bidderId: "test-user-1",
        amount: 130000,
        bidAt: "2026-11-12T10:00:00Z",
        isValid: false,
        reason: "AMOUNT_TOO_LOW",
        minNextBid: 135000,
      }),
    });
  });

  await page.goto("http://localhost:5173/products/test-product-1");
  await page.getByPlaceholder("입찰 금액을 입력하세요").fill("130000");
  await page.getByRole("button", { name: "입찰하기" }).click();

  await expect(
    page.getByText("현재 최소 입찰가는 135,000원입니다.")
  ).toBeVisible();
});

// 크레딧 부족(이슈 #163)은 금액이 낮은 게 아니므로 "더 높은 금액으로" 안내가 나가면 안 된다.
test("사용 가능 크레딧이 부족해 거절되면 크레딧 부족 안내를 보여준다", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("accessToken", "playwright-test-token");
  });

  await page.route(/\/api\/products(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([bidTestProduct]),
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
    await route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify({
        bidId: "test-bid-1",
        productId: "test-product-1",
        bidderId: "test-user-1",
        amount: 130000,
        bidAt: "2026-11-12T10:00:00Z",
        isValid: false,
        reason: "INSUFFICIENT_CREDIT",
        minNextBid: 130000,
      }),
    });
  });

  await page.goto("http://localhost:5173/products/test-product-1");
  await page.getByPlaceholder("입찰 금액을 입력하세요").fill("130000");
  await page.getByRole("button", { name: "입찰하기" }).click();

  await expect(page.getByText("사용 가능한 크레딧이 부족합니다.")).toBeVisible();
  await expect(page.getByText("더 높은 금액으로 입찰해 주세요")).toHaveCount(0);
});

test("엔진이 최소 입찰가를 내려주지 않으면 화면의 현재가와 입찰 단위로 계산해서 보여준다", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("accessToken", "playwright-test-token");
  });

  await page.route(/\/api\/products(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([bidTestProduct]),
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
    await route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify({
        bidId: "test-bid-1",
        productId: "test-product-1",
        bidderId: "test-user-1",
        amount: 130000,
        bidAt: "2026-11-12T10:00:00Z",
        isValid: false,
      }),
    });
  });

  await page.goto("http://localhost:5173/products/test-product-1");
  await page.getByPlaceholder("입찰 금액을 입력하세요").fill("130000");
  await page.getByRole("button", { name: "입찰하기" }).click();

  await expect(
    page.getByText("현재 최소 입찰가는 130,000원입니다.")
  ).toBeVisible();
});

// 이슈 #150 - 최고 입찰자가 UUID가 아니라 닉네임으로 보인다. 닉네임을 아직 안 주는 엔진이면 예전처럼 ID로 보인다.
test("입찰 이력에 닉네임이 있으면 최고 입찰자가 닉네임으로 표시된다", async ({ page }) => {
  await page.route(/\/api\/products(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([bidTestProduct]),
    });
  });

  await page.route("**/api/products/test-product-1/bids", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          bidId: "test-bid-1",
          bidderId: "d6d219d9-b266-44e6-a220-652f9525942e",
          bidderUsername: "alice",
          amount: 120000,
          bidAt: "2026-11-12T10:00:00Z",
          isValid: true,
        },
      ]),
    });
  });

  await page.goto("http://localhost:5173/products/test-product-1");

  await expect(page.getByText("최고 입찰자: alice")).toBeVisible();
  await expect(
    page.getByText("d6d219d9-b266-44e6-a220-652f9525942e")
  ).toHaveCount(0);
});

test("입찰 이력에 닉네임이 없으면 최고 입찰자를 ID로 표시한다", async ({ page }) => {
  await page.route(/\/api\/products(?:\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([bidTestProduct]),
    });
  });

  await page.route("**/api/products/test-product-1/bids", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          bidId: "test-bid-1",
          bidderId: "test-user-1",
          amount: 120000,
          bidAt: "2026-11-12T10:00:00Z",
          isValid: true,
        },
      ]),
    });
  });

  await page.goto("http://localhost:5173/products/test-product-1");

  await expect(page.getByText("최고 입찰자: test-user-1")).toBeVisible();
});

// 이슈 #153 - 마감 이후 상품에서는 입찰 버튼이 막혀야 한다. 예전에는 실시간 마감 이벤트(auction_closed)를 받아야만
// 막혀서, 이미 마감된 상품을 열거나 이벤트를 놓치면 입찰할 수 있는 것처럼 보였다.
async function mockClosedProductPage(
  page: import("@playwright/test").Page,
  product: Record<string, unknown>,
  bids: Record<string, unknown>[] = []
) {
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
      body: JSON.stringify(bids),
    });
  });
}

test("이미 낙찰된 상품을 열면 낙찰 결과가 보이고 입찰 UI가 표시되지 않는다", async ({ page }) => {
  await mockClosedProductPage(
    page,
    {
      ...bidTestProduct,
      status: "SOLD",
      endAt: "2026-11-10T10:00:00Z",
      currentPrice: 150000,
      winnerId: "winner-1",
      winnerUsername: "alice",
    },
    [
      {
        bidId: "bid-1",
        bidderId: "winner-1",
        bidderUsername: "alice",
        amount: 150000,
        bidAt: "2026-11-10T09:59:00Z",
        isValid: true,
      },
    ]
  );

  await page.goto("http://localhost:5173/products/test-product-1");

  await expect(page.getByRole("heading", { name: "경매 종료" })).toBeVisible();
  await expect(page.getByText("낙찰자: alice")).toBeVisible();
  await expect(page.getByText("최종 낙찰가: 150,000원")).toBeVisible();
  await expect(page.getByPlaceholder("입찰 금액을 입력하세요")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "입찰하기" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "경매 종료" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "+10,000원" })).toHaveCount(0);
});

test("유찰된 상품을 열면 유찰 안내가 보이고 입찰 UI가 표시되지 않는다", async ({ page }) => {
  await mockClosedProductPage(page, {
    ...bidTestProduct,
    status: "UNSOLD",
    endAt: "2026-11-10T10:00:00Z",
    winnerId: null,
  });

  await page.goto("http://localhost:5173/products/test-product-1");

  await expect(page.getByText("낙찰자가 없어 유찰되었습니다.")).toBeVisible();
  await expect(page.getByPlaceholder("입찰 금액을 입력하세요")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "입찰하기" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "경매 종료" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "+10,000원" })).toHaveCount(0);
});

// 마감 처리는 상품의 현재가를 낙찰가로 바꾸지 않는다. 크레딧 부족으로 차순위가 낙찰되면 현재가(150,000원)와
// 낙찰가(140,000원)가 다르므로, 낙찰자의 입찰 이력에서 낙찰가를 찾아 보여준다.
test("차순위가 낙찰된 상품은 현재가가 아니라 낙찰자의 입찰가를 낙찰가로 보여준다", async ({ page }) => {
  await mockClosedProductPage(
    page,
    {
      ...bidTestProduct,
      status: "SOLD",
      endAt: "2026-11-10T10:00:00Z",
      currentPrice: 150000,
      winnerId: "second-1",
      winnerUsername: "bob",
    },
    [
      {
        bidId: "bid-1",
        bidderId: "first-1",
        bidderUsername: "alice",
        amount: 150000,
        bidAt: "2026-11-10T09:59:00Z",
        isValid: true,
      },
      {
        bidId: "bid-2",
        bidderId: "second-1",
        bidderUsername: "bob",
        amount: 140000,
        bidAt: "2026-11-10T09:58:00Z",
        isValid: true,
      },
    ]
  );

  await page.goto("http://localhost:5173/products/test-product-1");

  await expect(page.getByText("낙찰자: bob")).toBeVisible();
  await expect(page.getByText("최종 낙찰가: 140,000원")).toBeVisible();
});

test("마감 시각이 지났지만 아직 마감 처리 전인 상품은 입찰 UI가 숨겨지고 집계 안내가 보인다", async ({ page }) => {
  await mockClosedProductPage(page, {
    ...bidTestProduct,
    status: "IN_PROGRESS",
    endAt: "2026-11-10T10:00:00Z",
  });

  await page.goto("http://localhost:5173/products/test-product-1");

  await expect(
    page.getByText("경매가 마감되었습니다. 낙찰 결과를 집계하는 중입니다.")
  ).toBeVisible();
  await expect(page.getByPlaceholder("입찰 금액을 입력하세요")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "입찰하기" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "경매 종료" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "+10,000원" })).toHaveCount(0);
});

// 마감 판단을 "한번 켜지면 안 꺼지는 값"이 아니라 현재 시각과 endAt에서 매번 계산하는 이유:
// 마감 직전 입찰로 안티 스나이핑 연장 이벤트가 오면 endAt이 늘어나므로 다시 입찰할 수 있어야 한다.
test("마감 시각이 지나 입찰이 막혀도 마감이 연장되면 다시 입찰할 수 있다", async ({ page }) => {
  await mockClosedProductPage(page, { ...bidTestProduct });

  let sendEndAt: ((endAt: string) => void) | undefined;

  await page.routeWebSocket(/productId=test-product-1/, (ws) => {
    ws.onMessage(() => {});

    sendEndAt = (endAt) => {
      ws.send(
        JSON.stringify({
          event: "auction_extended",
          product_id: "test-product-1",
          new_end_at: endAt,
        })
      );
    };
  });

  await page.goto("http://localhost:5173/products/test-product-1");

  await expect(page.getByRole("button", { name: "입찰하기" })).toBeEnabled();
  await expect.poll(() => Boolean(sendEndAt)).toBe(true);

  // 마감이 곧 지나는 시각으로 바뀌면 시간이 지난 뒤 입찰이 막힌다
  sendEndAt!(new Date(Date.now() + 1500).toISOString());
  await expect(page.getByRole("button", { name: "입찰하기" })).toHaveCount(0, {
  timeout: 8000,
  });
  await expect(page.getByPlaceholder("입찰 금액을 입력하세요")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "+10,000원" })).toHaveCount(0);

  // 안티 스나이핑 연장 이벤트가 오면 다시 입찰할 수 있다
  sendEndAt!(FAR_FUTURE_END_AT);
  await expect(page.getByRole("button", { name: "입찰하기" })).toBeEnabled();
});
