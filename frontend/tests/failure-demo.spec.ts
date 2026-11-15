import { test, expect } from "@playwright/test";

test("failure demo", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveTitle("intentionally-wrong-title");
});