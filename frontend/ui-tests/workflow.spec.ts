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
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/registration-mobile.png', fullPage: true });
});
test('reception uncertain result preserves original intent and blocks navigation', async ({ page }) => {
  await start(page);
  const id = '44444444-4444-4444-8444-444444444444';
  const cid = '55555555-5555-4555-8555-555555555555';
  const detail = { id, scopeId: scope, version: 1, state: 'SUBMITTED', requestNumber: 'DEV-AP-SYNTHETIC',
    patientId: encounter.patientId, patientLabel: encounter.patientLabel, encounterId: encounter.id, encounterNumber: encounter.encounterNumber,
    department: '合成科室', requestedAt: '2026-01-01T08:00:00Z', clinicalHistory: '合成资料', sampledAt: '2026-01-01T08:00:00Z',
    containers: [{ id: cid, site: '合成部位', laterality: 'UNKNOWN', materialQuantity: 1, fixative: '合成固定液', fixedAt: '2026-01-01T08:10:00Z' }] };
  await page.route('**/api/requests?*', route => route.fulfill({ json: { items: [detail], total: 1, page: 1, pageSize: 20 } }));
  await page.route('**/api/requests/' + id, route => route.fulfill({ json: detail }));
  await page.route('**/api/receptions/' + id, route => route.fulfill({ json: { request: detail, caseNumber: null, events: [] } }));
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  await page.getByRole('button', { name: '查看 DEV-AP-SYNTHETIC', exact: true }).click();
  await page.getByRole('button', { name: '处理此申请接收与异常' }).click();
  await page.getByLabel('实物患者 UUID', { exact: true }).fill(encounter.patientId);
  await page.getByLabel('实物就诊号', { exact: true }).fill(encounter.encounterNumber);
  await page.getByLabel('逐个容器 UUID（空白分隔）', { exact: true }).fill(cid);
  const writes: { key: string | undefined; body: string | null }[] = [];
  await page.route('**/api/receptions/' + id + '/receive', async route => {
    writes.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() }); await route.abort();
  });
  await page.getByRole('button', { name: '核对并接收', exact: true }).dblclick();
  await expect(page.getByText('结果待确认，请保留原请求重试；不能视为已经接收。')).toBeVisible();
  expect(writes).toHaveLength(1);
  await expect(page.getByLabel('实物患者 UUID', { exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '申请单查询', exact: true }).click();
  await expect(page.getByText('原请求结果尚未确认，请先使用页面上的原请求确认结果。')).toBeVisible();
  await page.getByRole('button', { name: '重试原接收请求' }).click();
  await expect.poll(() => writes.length).toBe(2); expect(writes[1]).toEqual(writes[0]);
  await expect(page.getByText(/^病理号：/)).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/reception-mobile.png', fullPage: true });
});
