import { test, expect, type Page } from '@playwright/test';

const scope = '11111111-1111-4111-8111-111111111111';
const encounter = { id: '22222222-2222-4222-8222-222222222222', patientId: '33333333-3333-4333-8333-333333333333', patientLabel: '合成患者', encounterNumber: 'SYN-001' };
async function start(page: Page) {
  await page.route('**/api/auth/me', route => route.fulfill({ json: { id: 'synthetic-user', username: 'synthetic', displayName: '合成用户' } }));
  await page.route('**/api/auth/csrf', route => route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'synthetic-only' } }));
  await page.route('**/api/hello', route => route.fulfill({ json: { application: 'PIS', message: 'Hello World' } }));
  await page.route('**/api/requests/scopes', route => route.fulfill({ json: [{ id: scope, name: '合成院区 / 科室' }] }));
  await page.route('**/api/requests?*', route => route.fulfill({ json: { items: [], total: 0, page: 1, pageSize: 20 } }));
  await page.route('**/api/requests/encounters?*', route => route.fulfill({ json: [encounter] }));
  await page.goto('/');
  await page.getByRole('button', { name: '申请登记工作区' }).click();
  await page.getByRole('combobox', { name: '授权工作范围' }).click();
  await page.getByText('合成院区 / 科室', { exact: true }).last().click();
}
test('empty, forbidden and retry errors remain distinct', async ({ page }) => {
  await start(page);
  await expect(page.getByText('没有符合条件的申请；不会自动创建患者')).toBeVisible();
  await page.route('**/api/requests?*', route => route.fulfill({ status: 403, json: { code: 'ACCESS_DENIED' } }));
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  await expect(page.getByText('无权查看', { exact: true })).toBeVisible();
  await expect(page.getByText('没有符合条件的申请；不会自动创建患者')).toHaveCount(0);
  await page.route('**/api/requests?*', route => route.abort());
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  await expect(page.getByText('查询失败', { exact: true })).toBeVisible();
});
test('dirty close is cancellable; discard clears values on reopen; refresh prompts', async ({ page }) => {
  await start(page);
  await page.getByRole('button', { name: '病理申请录入', exact: true }).click();
  await page.getByLabel('临床诊断与病史', { exact: true }).fill('合成本地草稿');
  await page.screenshot({ path: 'test-results/registration-desktop.png', fullPage: true });
  await page.getByRole('button', { name: '申请单查询', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('button', { name: '继续编辑' }).click();
  await expect(page.getByLabel('临床诊断与病史', { exact: true })).toHaveValue('合成本地草稿');
  let prompted = false;
  page.once('dialog', async dialog => { prompted = dialog.type() === 'beforeunload'; await dialog.dismiss(); });
  await page.reload({ timeout: 2000 }).catch(() => {});
  expect(prompted).toBe(true);
  await page.getByRole('button', { name: '申请单查询', exact: true }).click();
  await page.getByRole('button', { name: '放弃并继续' }).click();
  await page.getByRole('button', { name: '病理申请录入', exact: true }).click();
  await expect(page.getByLabel('临床诊断与病史', { exact: true })).toHaveValue('');
});
test('unknown write result freezes input and reuses exact intent; does not claim success', async ({ page }) => {
  await start(page);
  await page.getByRole('button', { name: '病理申请录入', exact: true }).click();
  await page.getByLabel('精确就诊号').fill('SYN-001'); await page.getByRole('button', { name: '查找就诊' }).click();
  await page.getByLabel('选择已核对就诊').click(); await page.getByText('合成患者 / SYN-001', { exact: true }).last().click();
  await page.getByLabel('部位', { exact: true }).fill('合成部位');
  const writes: { body: string | null; key: string | undefined }[] = [];
  await page.route('**/api/requests', async route => {
    writes.push({ body: route.request().postData(), key: route.request().headers()['idempotency-key'] });
    await route.abort();
  });
  await page.getByRole('button', { name: '保存草稿', exact: true }).dblclick();
  await expect(page.getByText(/结果待确认：/)).toBeVisible();
  expect(writes).toHaveLength(1);
  await expect(page.getByLabel('临床诊断与病史', { exact: true })).toBeDisabled();
  await expect(page.getByText('服务器已确认操作；列表将重新查询。')).toHaveCount(0);
  await page.getByRole('button', { name: '重试原请求确认结果' }).click();
  await expect.poll(() => writes.length).toBe(2); expect(writes[1]).toEqual(writes[0]);
});
test('registration remains readable on a narrow viewport', async ({ page }) => {
  await start(page);
  await page.getByRole('button', { name: '病理申请录入', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByLabel('临床诊断与病史', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/registration-mobile.png', fullPage: true });
});
