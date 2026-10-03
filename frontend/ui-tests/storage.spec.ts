import { test, expect, type Page } from '@playwright/test';
const rid = '44444444-4444-4444-8444-444444444444', vid = '55555555-5555-4555-8555-555555555555';
const v = { id: vid, assetId: 'asset-synthetic', caseId: 'case-synthetic', rootId: 'root-synthetic', ordinal: 0, version: 0, state: 'RESERVED', byteSize: 65536, sha256: 'a'.repeat(64), purpose: 'SYNTHETIC_ORIGINAL', createdAt: '2026-10-03T00:00:00Z' };
const view = { requestId: rid, caseId: v.caseId, provider: 'CONFIGURED_LOCAL', providerRootId: v.rootId, s3: 'NOT_CONFIGURED', page: 1, versions: [v] };
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
async function openStorage(page: Page) {
 await start(page); const d = { id: rid, scopeId: scope, version: 2, state: 'RECEIVED', requestNumber: 'DEV-AP-STORAGE', patientId: encounter.patientId, patientLabel: encounter.patientLabel, encounterId: encounter.id, encounterNumber: encounter.encounterNumber, department: '合成科室', requestedAt: '2026-01-01T08:00:00Z', clinicalHistory: '合成资料', sampledAt: '2026-01-01T08:00:00Z', containers: [{ id: vid, site: 'Synthetic', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic', fixedAt: '2026-01-01T08:10:00Z' }] };
 await page.route('**/api/requests?*', r => r.fulfill({ json: { items: [d], total: 1, page: 1, pageSize: 20 } })); await page.route('**/api/requests/' + rid, r => r.fulfill({ json: d })); await page.getByRole('button', { name: '刷新', exact: true }).click(); await page.getByRole('button', { name: '查看 DEV-AP-STORAGE', exact: true }).click(); await page.getByRole('button', { name: '处理此申请原件', exact: true }).click();
}

test('storage uncertain reserve and double click reuse exact key before staged completion', async ({ page }) => {
 const writes: { key: string | undefined; body: string | null }[] = []; let state = 'RESERVED';
 await page.route('**/storage?*', r => r.fulfill({ json: { ...view, versions: [] } }));
 await page.route('**/storage', async r => { writes.push({ key: r.request().headers()['idempotency-key'], body: r.request().postData() }); if (writes.length === 1) await r.abort(); else await r.fulfill({ status: 201, json: { receipt: { status: 201, resourceType: 'STORAGE_VERSION', resourceId: vid, version: 0 } } }); });
 await page.route('**/storage/' + vid, r => r.fulfill({ json: { ...v, state } }));
 await page.route('**/storage/*/bytes', r => { state = 'STAGED'; return r.fulfill({ json: { ...v, state, version: 2 } }); });
 await page.route('**/storage/*/finish', r => { state = 'READY'; return r.fulfill({ json: { ...v, state, version: 4 } }); });
 await openStorage(page); await page.getByRole('button', { name: '生成并上传合成原件' }).dblclick();
 await expect(page.getByRole('button', { name: '原键确认上传与完成' })).toBeEnabled(); expect(writes).toHaveLength(1);
 await page.getByRole('button', { name: '原键确认上传与完成' }).click(); await expect(page.getByText('已校验并登记固定合成原件；不表示WSI或临床有效。')).toBeVisible(); expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]);
});
test('storage dirty selection requires explicit discard and cancellation rejects late capacity', async ({ page }) => {
 await page.route('**/storage?*', r => r.fulfill({ json: view })); let release: (() => void) | undefined;
 await page.route('**/storage/capacity', async r => { await new Promise<void>(resolve => { release = resolve; }); await r.fulfill({ json: { provider: 'CONFIGURED_LOCAL', volumeTotal: 987654321, volumeUsable: 12345, reservedBytes: 0, syntheticQuota: 536870912, measuredAt: '2026-10-03T00:00:00Z' } }).catch(() => {}); });
 await openStorage(page); await page.getByRole('button', { name: '以此头新建版本' }).click(); await page.getByRole('button', { name: '申请单查询', exact: true }).click(); await expect(page.getByRole('dialog')).toBeVisible(); await page.getByRole('button', { name: '继续编辑' }).click();
 await page.getByRole('button', { name: '测量根目录容量' }).click(); await expect.poll(() => !!release).toBe(true); await page.getByRole('button', { name: '停止等待' }).click(); release?.(); await expect(page.getByText(/987654321/)).toHaveCount(0);
 await page.getByRole('button', { name: '取消本地选择' }).click(); await page.getByRole('button', { name: '申请单查询', exact: true }).click(); await expect(page.getByRole('dialog')).toHaveCount(0);
});
test('storage unavailable does not invent capacity or accept cross-case versions', async ({ page }) => {
 await page.route('**/storage?*', r => r.fulfill({ json: { ...view, provider: 'NOT_CONFIGURED', providerRootId: null, versions: [] } }));
 await page.route('**/storage/capacity', r => r.fulfill({ json: { provider: 'NOT_CONFIGURED', volumeTotal: null, volumeUsable: null, reservedBytes: 0, syntheticQuota: 536870912, measuredAt: null } }));
 await openStorage(page); await expect(page.getByRole('button', { name: '生成并上传合成原件' })).toBeDisabled(); await page.getByRole('button', { name: '测量根目录容量' }).click(); await expect(page.getByText(/文件系统总量：未配置/)).toBeVisible();
 await page.route('**/storage?*', r => r.fulfill({ json: { ...view, versions: [{ ...v, caseId: 'foreign-case' }] } })); await page.getByRole('button', { name: '刷新确切版本' }).click(); await expect(page.getByText('存储版本或病例不匹配')).toBeVisible(); await expect(page.getByRole('cell', { name: vid })).toHaveCount(0);
});
test('storage stopping uncertain tracking is explicit and never claims server rollback', async ({ page }) => {
 await page.route('**/storage?*', r => r.fulfill({ json: { ...view, versions: [] } })); await page.route('**/storage', r => r.abort()); await openStorage(page);
 await page.getByRole('button', { name: '生成并上传合成原件' }).click(); await expect(page.getByRole('button', { name: '原键确认上传与完成' })).toBeEnabled();
 await page.getByRole('button', { name: '停止跟踪原请求' }).click(); await page.getByRole('button', { name: '继续确认原请求', exact: true }).click(); await expect(page.getByRole('button', { name: '原键确认上传与完成' })).toBeEnabled();
 await page.getByRole('button', { name: '停止跟踪原请求' }).click(); await page.getByRole('button', { name: '保留服务器记录并停止跟踪' }).click(); await expect(page.getByText(/服务器记录与配额均保留，没有撤销上传/)).toBeVisible(); await expect(page.getByRole('button', { name: '生成并上传合成原件' })).toBeEnabled();
});
