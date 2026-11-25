import { test, expect } from "@playwright/test";

// 이슈 #149 - 사용자 조회 API(GET /api/users/{id})가 로그인한 사용자만 호출할 수 있게 바뀌었다.
// 마이페이지가 본인 정보를 조회할 때 토큰을 보내는지 확인한다. 안 보내면 엔진이 401을 주고 마이페이지가 깨진다.

// MyPage는 토큰의 sub(사용자 ID)를 읽어서 본인 정보를 조회한다. 서명은 확인하지 않으므로 가짜 서명으로 충분하다.
const base64url = (value: object) =>
  Buffer.from(JSON.stringify(value)).toString("base64url");
const ACCESS_TOKEN = `${base64url({ alg: "HS256", typ: "JWT" })}.${base64url({
  sub: "user-1",
})}.signature`;

test.beforeEach(async ({ page }) => {
  await page.addInitScript((token) => {
    localStorage.setItem("accessToken", token);
    localStorage.setItem("expiresAt", "2099-01-01T00:00:00");
  }, ACCESS_TOKEN);

  // 내 경매 목록(등록한, 참여한)은 이 테스트와 상관없으니 빈 목록으로 응답한다
  await page.route(/\/api\/products\?.*/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([]),
    });
  });
});

test("마이페이지는 본인 정보를 조회할 때 토큰을 보내고 이메일과 크레딧을 보여준다", async ({ page }) => {
  let authorizationHeader: string | undefined;

  await page.route("**/api/users/user-1", async (route) => {
    authorizationHeader = route.request().headers()["authorization"];

    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        id: "user-1",
        username: "alice",
        email: "alice@test.com",
        credit: 500000,
        createdAt: "2026-11-01T10:00:00",
      }),
    });
  });

  await page.goto("http://localhost:5173/mypage");

  await expect(page.getByText("alice@test.com")).toBeVisible();
  await expect(page.getByText("500,000원")).toBeVisible();
  expect(authorizationHeader).toBe(`Bearer ${ACCESS_TOKEN}`);
});

test("사용자 조회가 401이면 로그인 정보를 지우고 다시 로그인하라고 안내한다", async ({ page }) => {
  await page.route("**/api/users/user-1", async (route) => {
    await route.fulfill({
      status: 401,
      contentType: "application/json",
      body: JSON.stringify({
        code: "UNAUTHORIZED",
        message: "토큰이 유효하지 않습니다",
      }),
    });
  });

  await page.goto("http://localhost:5173/mypage");

  // 마이페이지는 조회가 실패하면 같은 오류 문구를 내 정보와 경매 목록 각 구역에 모두 보여주므로 첫 번째만 확인한다
  await expect(
    page.getByText("로그인이 만료되었습니다. 다시 로그인해 주세요.").first()
  ).toBeVisible();
  expect(
    await page.evaluate(() => localStorage.getItem("accessToken"))
  ).toBeNull();
});

// 이슈 #187 - 내가 등록한 경매와 낙찰받은 경매도 참여 중인 경매처럼 카드로 보여주고, 상태는 한글로 보여준다.
test("내가 등록한 경매와 낙찰받은 경매를 카드로 보여주고 상태를 한글로 표시한다", async ({ page }) => {
  const product = (overrides: object) => ({
    description: "설명",
    startingPrice: 10000,
    bidUnit: 1000,
    sellerUsername: "seller",
    startAt: "2026-11-01T10:00:00",
    endAt: "2099-01-01T00:00:00",
    winnerId: null,
    imageUrl: null,
    createdAt: "2026-11-01T10:00:00",
    ...overrides,
  });

  await page.route("**/api/users/user-1", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        id: "user-1",
        username: "alice",
        email: "alice@test.com",
        credit: 500000,
        createdAt: "2026-11-01T10:00:00",
      }),
    });
  });
  await page.route(/\/api\/products\?sellerId=/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        product({ id: "p-1", title: "등록한 카메라", currentPrice: 30000, sellerId: "user-1", status: "IN_PROGRESS" }),
      ]),
    });
  });
  await page.route(/\/api\/products\?biddedByUserId=/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        product({
          id: "p-2",
          title: "낙찰받은 시계",
          currentPrice: 75000,
          sellerId: "user-2",
          status: "SOLD",
          endAt: "2026-11-02T10:00:00",
          winnerId: "user-1",
        }),
      ]),
    });
  });

  await page.goto("http://localhost:5173/mypage");

  const registered = page.locator("section", { has: page.getByRole("heading", { name: "내가 등록한 경매" }) });
  const registeredCard = registered.locator(".auction-card");
  await expect(registeredCard).toHaveCount(1);
  await expect(registeredCard.getByRole("heading", { name: "등록한 카메라" })).toBeVisible();
  await expect(registeredCard.getByText("상품 이미지 없음")).toBeVisible();
  await expect(registeredCard.getByText("현재가: 30,000원")).toBeVisible();
  await expect(registeredCard.getByText("상태: 진행 중")).toBeVisible();
  await expect(registeredCard.getByText("남은 시간:")).toBeVisible();

  const won = page.locator("section", { has: page.getByRole("heading", { name: "내가 낙찰받은 경매" }) });
  const wonCard = won.locator(".auction-card");
  await expect(wonCard).toHaveCount(1);
  await expect(wonCard.getByText("낙찰가: 75,000원")).toBeVisible();
  await expect(wonCard.getByText("상태: 낙찰")).toBeVisible();
  // 끝난 경매에는 남은 시간을 보여주지 않는다
  await expect(wonCard.getByText("남은 시간:")).toHaveCount(0);
});
