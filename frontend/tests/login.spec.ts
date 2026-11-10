import { test, expect } from "@playwright/test";

test("로그인 성공 시 인증 정보를 저장하고 메인 페이지로 이동한다", async ({ page }) => {
  await page.route("**/api/login", async (route) => {
    const request = route.request();

    expect(request.method()).toBe("POST");
    expect(request.postDataJSON()).toEqual({
      username: "test-user",
      password: "test-password",
    });

    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        accessToken: "test-access-token",
        expiresAt: "2026-12-01T00:00:00",
      }),
    });
  });

  await page.route("**/api/products", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([]),
    });
  });

  await page.goto("http://localhost:5173/login");

  await page.getByLabel("사용자 이름").fill("test-user");
  await page.getByLabel("비밀번호").fill("test-password");
  await page.getByRole("button", { name: "로그인" }).click();

  await expect(page).toHaveURL("http://localhost:5173/");

  await expect
    .poll(() =>
      page.evaluate(() => ({
        accessToken: localStorage.getItem("accessToken"),
        username: localStorage.getItem("username"),
        expiresAt: localStorage.getItem("expiresAt"),
      }))
    )
    .toEqual({
      accessToken: "test-access-token",
      username: "test-user",
      expiresAt: "2026-12-01T00:00:00",
    });
});

test("로그인 후 상품 목록을 조회할 수 있다", async ({ page }) => {
  await page.route("**/api/login", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        accessToken: "test-access-token",
        expiresAt: "2026-12-01T00:00:00",
      }),
    });
  });

  await page.route("**/api/products", async (route) => {
    if (route.request().method() !== "GET") {
      return route.continue();
    }

    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          id: "test-product-1",
          title: "로그인 후 조회한 노트북",
          description: "로그인 후 상품 조회 테스트",
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
        },
      ]),
    });
  });

  await page.goto("http://localhost:5173/login");

  await page.getByLabel("사용자 이름").fill("test-user");
  await page.getByLabel("비밀번호").fill("test-password");
  await page.getByRole("button", { name: "로그인" }).click();

  await expect(page).toHaveURL("http://localhost:5173/");

  await expect(page.getByText("로그인 후 조회한 노트북")).toBeVisible();
  await expect(page.getByText("현재가: 120,000원")).toBeVisible();

  await expect(
    page.getByRole("button", { name: "로그아웃" })
  ).toBeVisible();
});