import { test, expect } from '@playwright/test';

test('shows the real backend response and supports repeat requests', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByText('应用：PIS · API 连接成功')).toBeVisible();
  await page.getByRole('button', { name: '重新请求' }).click();
  await expect(page.getByText('应用：PIS · API 连接成功')).toBeVisible();
});

test('shows an API error and recovers on retry', async ({ page }) => {
  await page.route('**/api/hello', route => route.fulfill({ status: 503, body: 'Unavailable' }));
  await page.goto('/');
  await expect(page.getByText('连接失败', { exact: true })).toBeVisible();
  await page.unroute('**/api/hello');
  await page.getByRole('button', { name: '重新请求' }).click();
  await expect(page.getByText('应用：PIS · API 连接成功')).toBeVisible();
});
