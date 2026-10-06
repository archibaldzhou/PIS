import { test, expect, type Page } from '@playwright/test';
import { statisticsId, statisticsView } from './fixtures/statistics';

const scope = '11111111-1111-4111-8111-111111111111';
const encounter = { id: '22222222-2222-4222-8222-222222222222', patientId: '33333333-3333-4333-8333-333333333333', patientLabel: '合成患者', encounterNumber: 'SYN-001' };
async function start(page: Page) {
  await page.route('**/api/auth/me', route => route.fulfill({ json: { id: 'synthetic-user', username: 'synthetic', displayName: '合成用户' } }));
  await page.route('**/api/auth/csrf', route => route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'synthetic-only' } }));
  await page.route('**/api/hello', route => route.fulfill({ json: { application: 'PIS', message: 'Hello World' } }));
  await page.route('**/api/requests/work-context', route => route.fulfill({ json: { defaultScopeId: scope, writableScopes: [scope], name: '合成院区 / 科室', scopes: [{ id: scope, name: '合成院区 / 科室' }], administration: false, menus: ['operations', 'adapters', 'requests', 'registration', 'manual', 'reception', 'labels', 'grossing', 'technical', 'materials', 'quality', 'worklist', 'diagnosis', 'report', 'review', 'output', 'amendments', 'delivery', 'frozen', 'cytology', 'staining', 'consultation', 'archive', 'statistics', 'storage', 'scan', 'digitalqc', 'viewer', 'ai', 'aitasks'] } }));
  await page.route('**/api/requests?*', route => route.fulfill({ json: { items: [], total: 0, page: 1, pageSize: 20 } }));
  await page.route('**/api/requests/encounters?*', route => route.fulfill({ json: [encounter] }));
  await page.goto('/');
  await page.getByRole('button', { name: '申请登记工作区' }).click();
  await expect(page.getByText('当前工作范围：合成院区 / 科室', { exact: true })).toBeVisible();
}

async function openStatistics(page: Page) { await start(page); await page.getByRole('button', { name: '工作量TAT与QC统计', exact: true }).click(); }
test('statistics zero, no data and fixed source drill retain metric definitions', async ({ page }) => {
  await page.route('**/api/requests/statistics/**', route => {
    if (route.request().method() === 'POST') return route.fulfill({ json: { receipt: { status: 200, resourceType: 'STATISTICS_SNAPSHOT', resourceId: statisticsId, version: 0 }, replayed: false } });
    return route.fulfill({ json: statisticsView(scope, new URL(route.request().url()).searchParams.get('metric') === 'RECEPTION' ? 'RECEPTION' : null) });
  });
  await openStatistics(page); await page.getByRole('button', { name: '冻结并查询统计' }).click();
  await expect(page.getByText('完成 / 入组：0 / 1；0.00%')).toBeVisible();
  await expect(page.getByText('无数据', { exact: true })).toHaveCount(3);
  await page.getByRole('button', { name: '查看接收来源' }).click();
  await expect(page.getByRole('cell', { name: 'OPEN', exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: '3600', exact: true })).toBeVisible();
  await page.getByLabel('统计时区').fill('Asia/Shanghai');
  await expect(page.getByRole('cell', { name: 'OPEN', exact: true })).toHaveCount(0);
});
test('statistics double-click, network failure and retry preserve exact query key', async ({ page }) => {
  const writes: { key: string | undefined; body: string | null }[] = [];
  await page.route('**/api/requests/statistics/**', route => {
    if (route.request().method() === 'POST') {
      writes.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() });
      if (writes.length === 1) return route.abort();
      return route.fulfill({ json: { receipt: { status: 200, resourceType: 'STATISTICS_SNAPSHOT', resourceId: statisticsId, version: 0 }, replayed: true } });
    }
    return route.fulfill({ json: statisticsView(scope) });
  });
  await openStatistics(page); await page.getByRole('button', { name: '冻结并查询统计' }).dblclick();
  await expect(page.getByRole('button', { name: '原键重试统计' })).toBeVisible(); expect(writes).toHaveLength(1);
  await page.getByRole('button', { name: '原键重试统计' }).click();
  await expect(page.getByText('完成 / 入组：0 / 1；0.00%')).toBeVisible(); expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]);
});
test('statistics cancelled late response never restores obsolete totals and forbidden is not empty', async ({ page }) => {
  let release: (() => void) | undefined;
  await page.route('**/api/requests/statistics/**', async route => {
    if (route.request().method() === 'POST') { await new Promise<void>(resolve => { release = resolve; }); await route.fulfill({ json: { receipt: { status: 200, resourceType: 'STATISTICS_SNAPSHOT', resourceId: statisticsId, version: 0 }, replayed: false } }).catch(() => {}); return; }
    await route.fulfill({ json: statisticsView(scope) });
  });
  await openStatistics(page); await page.getByRole('button', { name: '冻结并查询统计' }).click(); await expect.poll(() => !!release).toBe(true);
  await page.getByRole('button', { name: '取消统计查询' }).click(); release?.();
  await page.getByLabel('统计时区').fill('Asia/Shanghai');
  await page.route('**/api/requests/statistics/**', route => route.fulfill({ status: 404, json: { code: 'STATISTICS_NOT_FOUND' } }));
  await page.getByRole('button', { name: '冻结并查询统计' }).click();
  await expect(page.getByText('无统计权限或快照已不可访问；不展示旧总数')).toBeVisible();
  await expect(page.getByText('无数据', { exact: true })).toHaveCount(0); await expect(page.getByText(/固定快照 9999/)).toHaveCount(0);
});
