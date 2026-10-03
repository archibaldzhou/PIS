import { test, expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

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
test('label preview closes without printing and unknown reprint retains identity and key', async ({ page }) => {
  await start(page);
  const id = '44444444-4444-4444-8444-444444444444'; const cid = '55555555-5555-4555-8555-555555555555'; const jobId = '66666666-6666-4666-8666-666666666666';
  const barcode = 'S' + '0'.repeat(32) + 'S';
  const detail = { id, scopeId: scope, version: 2, state: 'RECEIVED', requestNumber: 'DEV-AP-LABEL', patientId: encounter.patientId, patientLabel: encounter.patientLabel,
    encounterId: encounter.id, encounterNumber: encounter.encounterNumber, department: '合成科室', requestedAt: '2026-01-01T08:00:00Z', clinicalHistory: '合成资料', sampledAt: '2026-01-01T08:00:00Z',
    containers: [{ id: cid, site: '合成部位', laterality: 'UNKNOWN', materialQuantity: 1, fixative: '合成固定液', fixedAt: '2026-01-01T08:10:00Z' }] };
  const job = { id: jobId, containerId: cid, barcode, parentJobId: null, templateVersion: 'SYN-CONTAINER-1', state: 'PREVIEW_READY', version: 0, attempts: 1,
    patientId: encounter.patientId, patientLabel: encounter.patientLabel, encounterNumber: encounter.encounterNumber, requestNumber: detail.requestNumber, caseNumber: 'DEV-P-SYNTHETIC', site: '合成部位', laterality: 'UNKNOWN', reason: 'Synthetic initial', createdBy: 'synthetic-user', createdAt: '2026-01-01T08:00:00Z' };
  await page.route('**/api/requests?*', route => route.fulfill({ json: { items: [detail], total: 1, page: 1, pageSize: 20 } }));
  await page.route('**/api/requests/' + id, route => route.fulfill({ json: detail }));
  await page.route('**/api/labels/containers/' + cid, route => route.fulfill({ json: { containerId: cid, requestVersion: 2, containerVersion: 1, requestState: 'RECEIVED', jobs: [job] } }));
  await page.route('**/api/labels/jobs/' + jobId, route => route.fulfill({ json: { job, events: [] } }));
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  await page.getByRole('button', { name: '查看 DEV-AP-LABEL', exact: true }).click();
  await page.getByRole('button', { name: '处理此申请标签', exact: true }).click();
  await page.getByLabel('选择容器实体').click(); await page.getByText(cid, { exact: true }).last().click();
  await page.getByRole('button', { name: '查看任务 ' + jobId, exact: true }).click();
  await page.getByRole('button', { name: '打开标签预览', exact: true }).click();
  await expect(page.getByLabel('合成容器标签预览')).toContainText(barcode);
  await expect(page.getByRole('img', { name: 'Code39 ' + barcode })).toBeVisible();
  await page.screenshot({ path: 'test-results/label-preview-desktop.png', fullPage: true });
  await page.getByRole('button', { name: '关闭标签预览', exact: true }).click();
  await expect(page.getByLabel('合成容器标签预览')).toHaveCount(0);
  await page.getByRole('button', { name: '同实体重打', exact: true }).click();
  await expect(page.getByText('请填写1–2000字的操作原因')).toBeVisible();
  const writes: { key: string | undefined; body: string | null }[] = [];
  await page.route('**/api/labels/jobs/' + jobId + '/reprint', async route => { writes.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() }); await route.abort(); });
  await page.getByLabel('重打 / 重试 / 取消 / 模拟失败原因').fill('合成损坏重打');
  await page.getByRole('button', { name: '同实体重打', exact: true }).dblclick();
  await expect(page.getByText('标签操作结果待确认；请保留原任务与请求键重试。')).toBeVisible(); expect(writes).toHaveLength(1);
  await expect(page.getByLabel('重打 / 重试 / 取消 / 模拟失败原因')).toBeDisabled();
  await page.getByRole('button', { name: '申请单查询', exact: true }).click();
  await expect(page.getByText('原请求结果尚未确认，请先使用页面上的原请求确认结果。')).toBeVisible();
  await page.getByRole('button', { name: '重试原标签请求', exact: true }).click();
  await expect.poll(() => writes.length).toBe(2); expect(writes[1]).toEqual(writes[0]);
});

test('grossing resets modes, revokes photo URLs and freezes an uncertain command', async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  await start(page);
  const id = '44444444-4444-4444-8444-444444444444', cid = '55555555-5555-4555-8555-555555555555', recordId = '66666666-6666-4666-8666-666666666666', photoId = '77777777-7777-4777-8777-777777777777';
  const detail = { id, scopeId: scope, version: 2, state: 'RECEIVED', requestNumber: 'DEV-AP-GROSS', patientId: encounter.patientId, patientLabel: encounter.patientLabel, encounterId: encounter.id, encounterNumber: encounter.encounterNumber,
    department: '合成科室', requestedAt: '2026-01-01T08:00:00Z', clinicalHistory: '合成资料', sampledAt: '2026-01-01T08:00:00Z', containers: [{ id: cid, site: '合成部位', laterality: 'UNKNOWN', materialQuantity: 1, fixative: '合成固定液', fixedAt: '2026-01-01T08:10:00Z' }] };
  const { readFile } = await import('node:fs/promises');
  const png = await readFile(new URL('../../backend/src/main/resources/grossing-assets/synthetic-v1.png', import.meta.url));
  const record = { id: recordId, version: 0, state: 'DRAFT', description: '合成已保存描述', cassettes: [], revisions: [], events: [], photos: [{ id: photoId, containerId: cid, caption: '合成图像', sha256: 'adaacd24ed0218efbde244a9a0c314cf26b8f27f612146bd9d164971fcb92623', bytes: 656, width: 256, height: 160, withdrawnAt: null }] };
  await page.route('**/api/requests?*', route => route.fulfill({ json: { items: [detail], total: 1, page: 1, pageSize: 20 } }));
  await page.route('**/api/requests/' + id, route => route.fulfill({ json: detail }));
  await page.route('**/api/grossing/requests/' + id, route => route.fulfill({ json: { request: detail, caseId: 'synthetic-case', caseNumber: 'DEV-P-GROSS', record } }));
  await page.route('**/api/grossing/photos/' + photoId + '/content', route => route.fulfill({ contentType: 'image/png', body: png }));
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  await page.getByRole('button', { name: '查看 DEV-AP-GROSS', exact: true }).click();
  await page.getByRole('button', { name: '处理此病例取材', exact: true }).click();
  await page.getByRole('button', { name: '查看合成图像', exact: true }).click();
  const img = page.getByRole('img', { name: '合成取材示意图，不是真实照片，无测量校准' });
  await expect(img).toBeVisible(); const url = await img.getAttribute('src');
  await page.getByRole('button', { name: '关闭合成图像', exact: true }).click(); await expect(img).toHaveCount(0);
  expect(await page.evaluate(async source => { try { await fetch(source ?? ''); return false; } catch { return true; } }, url)).toBe(true);
  const mode = async (name: string) => { await page.getByLabel('取材操作', { exact: true }).click(); await page.getByRole('option', { name, exact: true }).click(); };
  await page.getByLabel('大体描述', { exact: true }).fill('合成未保存描述'); await mode('添加合成图像');
  await page.getByRole('button', { name: '放弃并切换', exact: true }).click();
  let photoWrites = 0; await page.route('**/api/grossing/records/' + recordId + '/photos', route => { photoWrites++; return route.abort(); });
  await page.getByLabel('导入同一受控PNG').setInputFiles({ name: 'synthetic-invalid.png', mimeType: 'image/png', buffer: Buffer.from('<svg/>') });
  await expect(page.getByText('只接受随程序发布的合成PNG，不发送或保存其他照片。')).toBeVisible(); expect(photoWrites).toBe(0);
  await mode('保存大体描述'); await expect(page.getByLabel('大体描述', { exact: true })).toHaveValue('合成已保存描述');
  await page.getByLabel('大体描述', { exact: true }).fill('合成待确认描述'); await page.getByLabel('操作原因 / 更正说明').fill('合成修改原因');
  const writes: { key: string | undefined; body: string | null }[] = [];
  await page.route('**/api/grossing/records/' + recordId + '/description', async route => { writes.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() }); await route.abort(); });
  await page.getByRole('button', { name: '保存大体描述', exact: true }).dblclick();
  await expect(page.getByText('取材操作结果待确认；请保留原输入与请求键重试。')).toBeVisible(); expect(writes).toHaveLength(1);
  await expect(page.getByLabel('大体描述', { exact: true })).toBeDisabled(); await expect(page.getByLabel('取材操作', { exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '申请单查询', exact: true }).click(); await expect(page.getByText('原请求结果尚未确认，请先使用页面上的原请求确认结果。')).toBeVisible();
  await page.getByRole('button', { name: '重试原取材请求', exact: true }).click(); await expect.poll(() => writes.length).toBe(2); expect(writes[1]).toEqual(writes[0]);
  expect(JSON.parse(writes[0].body ?? '{}')).toEqual({ expectedVersion: 0, description: '合成待确认描述', reason: '合成修改原因' });
  await page.screenshot({ path: 'test-results/grossing-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/grossing-mobile.png', fullPage: true });
  expect(errors).toEqual([]);
});

test('exact request selection and saved revision wait for the correction receipt', async ({ page }) => {
  await start(page);
  const id = '88888888-8888-4888-8888-888888888888', otherId = '99999999-9999-4999-8999-999999999999';
  const recordId = '66666666-6666-4666-8666-666666666666';
  const detail = { id, scopeId: scope, version: 2, state: 'RECEIVED', requestNumber: 'DEV-AP-EXACT', patientId: encounter.patientId, patientLabel: encounter.patientLabel,
    encounterId: encounter.id, encounterNumber: encounter.encounterNumber, department: '合成科室', requestedAt: '2026-01-01T08:00:00Z', clinicalHistory: '合成资料', sampledAt: '2026-01-01T08:00:00Z', containers: [] };
  const original = { version: 0, description: '合成原始已完成描述', reason: '合成原始记录', actorId: 'synthetic-user', createdAt: '2026-01-01T08:00:00Z' };
  let record = { id: recordId, version: 0, state: 'COMPLETED', description: original.description, cassettes: [], photos: [], events: [], revisions: [original] };
  // A second simultaneous request makes accidental broad DEV-AP selection fail strictly.
  await page.route('**/api/requests?*', route => route.fulfill({ json: { items: [{ ...detail, id: otherId, requestNumber: 'DEV-AP-OTHER' }, detail], total: 2, page: 1, pageSize: 20 } }));
  await page.route('**/api/requests/' + id, route => route.fulfill({ json: detail }));
  await page.route('**/api/requests/' + otherId, route => route.fulfill({ status: 404, json: { code: 'REQUEST_NOT_FOUND' } }));
  await page.route('**/api/grossing/requests/' + id, route => route.fulfill({ json: { request: detail, caseId: 'synthetic-case', caseNumber: 'DEV-P-EXACT', record } }));
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  await expect(page.getByRole('button', { name: /^查看 DEV-AP-/ })).toHaveCount(2);
  await page.getByTestId('request-row-' + id).getByRole('button', { name: '查看 DEV-AP-EXACT', exact: true }).click();
  await page.getByRole('button', { name: '处理此病例取材', exact: true }).click();
  const revisions = page.getByRole('region', { name: '取材描述修订', exact: true });
  await expect(revisions).toHaveAttribute('data-record-id', recordId);
  await page.getByLabel('大体描述', { exact: true }).fill('合成更正尚未保存');
  await page.getByLabel('操作原因 / 更正说明').fill('合成更正原因');
  let release = () => {}; const gate = new Promise<void>(resolve => { release = resolve; }); let writes = 0;
  await page.route('**/api/grossing/records/' + recordId + '/correction', async route => {
    writes++; expect(route.request().postDataJSON()).toEqual({ expectedVersion: 0, description: '合成更正尚未保存', reason: '合成更正原因' });
    await gate;
    record = { ...record, version: 1, description: '合成更正尚未保存', revisions: [{ ...original, version: 1, description: '合成更正尚未保存', reason: '合成更正原因' }, original] };
    await route.fulfill({ json: { receipt: { resourceId: recordId, version: 1 } } });
  });
  try {
    await page.getByRole('button', { name: '更正已完成描述', exact: true }).click();
    await expect.poll(() => writes).toBe(1);
    await expect(page.getByLabel('大体描述', { exact: true })).toHaveValue('合成更正尚未保存');
    await expect(revisions.getByRole('cell', { name: '合成更正尚未保存', exact: true })).toHaveCount(0);
    await expect(revisions.getByRole('cell', { name: original.description, exact: true })).toBeVisible();
    await expect(page.getByLabel('取材病例身份')).toContainText('版本 0');
    release();
    await expect(page.getByLabel('取材病例身份')).toContainText('版本 1');
    await expect(revisions.getByRole('cell', { name: '合成更正尚未保存', exact: true })).toBeVisible();
    await expect(revisions.getByRole('cell', { name: original.description, exact: true })).toBeVisible();
  } finally { release(); }
});

test('technical task changes clear local identity and unknown claim retains original intent', async ({ page }) => {
  await start(page);
  const id = '44444444-4444-4444-8444-444444444444', box = '55555555-5555-4555-8555-555555555555';
  const taskId = '66666666-6666-4666-8666-666666666666', otherId = '77777777-7777-4777-8777-777777777777';
  const detail = { id, scopeId: scope, version: 2, state: 'RECEIVED', requestNumber: 'DEV-AP-TECH', patientId: encounter.patientId, patientLabel: encounter.patientLabel, encounterId: encounter.id, encounterNumber: encounter.encounterNumber,
    department: '合成科室', requestedAt: '2026-01-01T08:00:00Z', clinicalHistory: '合成资料', sampledAt: '2026-01-01T08:00:00Z', containers: [] };
  const task = { id: taskId, cassetteId: box, kind: 'PROCESSING', state: 'QUEUED', ownerId: null, predecessorId: null, reworkOf: null, version: 0, createdBy: 'synthetic-user', createdAt: '2026-01-01T08:00:00Z' };
  const second = { ...task, id: otherId, kind: 'EMBEDDING' };
  await page.route('**/api/requests?*', route => route.fulfill({ json: { items: [detail], total: 1, page: 1, pageSize: 20 } }));
  await page.route('**/api/requests/' + id, route => route.fulfill({ json: detail }));
  await page.route('**/api/technical/requests/' + id, route => route.fulfill({ json: { actorId: 'synthetic-user', source: { requestId: id, requestVersion: 2, caseId: 'synthetic-case', caseNumber: 'DEV-P-TECH', patientId: encounter.patientId, patientLabel: encounter.patientLabel, encounterNumber: encounter.encounterNumber, cassettes: [{ id: box, number: 'DEV-C-SYNTHETIC', site: '合成部位', containerIds: [] }] }, tasks: [task, second] } }));
  for (const t of [task, second]) await page.route('**/api/technical/tasks/' + t.id, route => route.fulfill({ json: { task: t, events: [] } }));
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  await page.getByTestId('request-row-' + id).getByRole('button', { name: '查看 DEV-AP-TECH', exact: true }).click();
  await page.getByRole('button', { name: '处理此病例技术任务', exact: true }).click();
  await page.getByRole('button', { name: '查看技术任务 ' + taskId, exact: true }).click();
  await page.getByLabel('核对来源取材盒 UUID').fill(box); await page.getByLabel('路线 / 操作 / 交接说明').fill('合成未保存交接说明');
  await page.getByRole('button', { name: '查看技术任务 ' + otherId, exact: true }).click();
  await page.getByRole('button', { name: '继续编辑', exact: true }).click();
  await expect(page.getByLabel('技术任务身份')).toHaveAttribute('data-task-id', taskId);
  await page.getByRole('button', { name: '查看技术任务 ' + otherId, exact: true }).click(); await page.getByRole('button', { name: '放弃并切换', exact: true }).click();
  await expect(page.getByLabel('技术任务身份')).toHaveAttribute('data-task-id', otherId);
  await expect(page.getByLabel('核对来源取材盒 UUID')).toHaveValue(''); await expect(page.getByLabel('路线 / 操作 / 交接说明')).toHaveValue('');
  await page.getByLabel('核对来源取材盒 UUID').fill(box); await page.getByLabel('路线 / 操作 / 交接说明').fill('合成领取');
  const writes: { key: string | undefined; body: string | null }[] = [];
  await page.route('**/api/technical/tasks/' + otherId + '/claim', async route => { writes.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() }); await route.abort(); });
  await page.getByRole('button', { name: '确认技术任务操作', exact: true }).dblclick();
  await expect(page.getByText('技术任务结果待确认；保留原任务、输入和请求键重试。')).toBeVisible(); expect(writes).toHaveLength(1);
  await expect(page.getByLabel('核对来源取材盒 UUID')).toBeDisabled();
  await expect(page.getByRole('button', { name: '查看技术任务 ' + taskId, exact: true })).toBeDisabled();
  await expect(page.getByLabel('技术任务身份')).toContainText('QUEUED');
  await page.getByRole('button', { name: '申请单查询', exact: true }).click(); await expect(page.getByText('原请求结果尚未确认，请先使用页面上的原请求确认结果。')).toBeVisible();
  await page.getByRole('button', { name: '重试原技术请求', exact: true }).click(); await expect.poll(() => writes.length).toBe(2); expect(writes[1]).toEqual(writes[0]);
  expect(JSON.parse(writes[0].body ?? '{}')).toEqual({ expectedVersion: 0, confirmedCassetteId: box, reason: '合成领取' });
  await page.screenshot({ path: 'test-results/technical-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/technical-mobile.png', fullPage: true });
});

test('material path switch discards hidden inputs and uncertain registration never creates an identity locally', async ({ page }) => {
  await start(page);
  const id = '44444444-4444-4444-8444-444444444444', cid = '55555555-5555-4555-8555-555555555555';
  const detail = { id, scopeId: scope, version: 2, state: 'RECEIVED', requestNumber: 'DEV-AP-MATERIAL', patientId: encounter.patientId, patientLabel: encounter.patientLabel, encounterId: encounter.id, encounterNumber: encounter.encounterNumber,
    department: '合成科室', requestedAt: '2026-01-01T08:00:00Z', clinicalHistory: '合成资料', sampledAt: '2026-01-01T08:00:00Z', containers: [{ id: cid, site: '合成部位', laterality: 'UNKNOWN', materialQuantity: 1, fixative: '合成固定液', fixedAt: '2026-01-01T08:10:00Z' }] };
  await page.route('**/api/requests?*', route => route.fulfill({ json: { items: [detail], total: 1, page: 1, pageSize: 20 } }));
  await page.route('**/api/requests/' + id, route => route.fulfill({ json: detail }));
  await page.route('**/api/materials/requests/' + id, route => route.fulfill({ json: { request: detail, caseNumber: 'DEV-P-MATERIAL', entities: [], tasks: [] } }));
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  await page.getByTestId('request-row-' + id).getByRole('button', { name: '查看 DEV-AP-MATERIAL', exact: true }).click();
  await page.getByRole('button', { name: '处理此病例材料谱系', exact: true }).click();
  await page.getByLabel('核对来源盒 UUID').fill('66666666-6666-4666-8666-666666666666');
  await page.getByLabel('材料登记 / 重切 / 作废原因').fill('合成旧路径原因');
  await page.getByLabel('材料操作', { exact: true }).click(); await page.getByRole('option', { name: '登记细胞学直制玻片', exact: true }).click();
  await page.getByRole('button', { name: '放弃并切换', exact: true }).click();
  await expect(page.getByLabel('核对直制容器 UUID')).toHaveValue(''); await expect(page.getByLabel('材料登记 / 重切 / 作废原因')).toHaveValue(''); await expect(page.getByLabel('前置合成技术任务')).toHaveCount(0);
  await page.getByLabel('核对直制容器 UUID').fill(cid); await page.getByLabel('材料登记 / 重切 / 作废原因').fill('合成明确直制');
  const writes: { key: string | undefined; body: string | null }[] = [];
  await page.route('**/api/materials/requests/' + id + '/direct-slides', async route => { writes.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() }); await route.abort(); });
  await page.getByRole('button', { name: '登记细胞学直制玻片', exact: true }).dblclick();
  await expect(page.getByText('材料操作结果待确认；保留原身份、输入和请求键重试。')).toBeVisible(); expect(writes).toHaveLength(1);
  await expect(page.getByLabel('材料实体身份')).toHaveCount(0); await expect(page.getByLabel('材料操作', { exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '申请单查询', exact: true }).click(); await expect(page.getByText('原请求结果尚未确认，请先使用页面上的原请求确认结果。')).toBeVisible();
  await page.getByRole('button', { name: '重试原材料请求', exact: true }).click(); await expect.poll(() => writes.length).toBe(2); expect(writes[1]).toEqual(writes[0]);
  expect(JSON.parse(writes[0].body ?? '{}')).toEqual({ requestVersion: 2, confirmedContainerId: cid, reason: '合成明确直制' });
  await page.screenshot({ path: 'test-results/materials-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 }); await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/materials-mobile.png', fullPage: true });
});

test('material label preview rechecks server invalidation before displaying cached identity', async ({ page }) => {
  await start(page);
  const rid = '44444444-4444-4444-8444-444444444444', cid = '55555555-5555-4555-8555-555555555555', mid = '66666666-6666-4666-8666-666666666666', jid = '77777777-7777-4777-8777-777777777777';
  const barcode = 'S' + '0'.repeat(32) + 'S';
  const detail = { id: rid, scopeId: scope, version: 2, state: 'RECEIVED', requestNumber: 'DEV-AP-MATERIAL-LABEL', patientId: encounter.patientId, patientLabel: encounter.patientLabel, encounterId: encounter.id, encounterNumber: encounter.encounterNumber, department: '合成科室', requestedAt: '2026-01-01T08:00:00Z', clinicalHistory: '合成资料', sampledAt: '2026-01-01T08:00:00Z', containers: [] };
  const entity = { id: mid, requestId: rid, patientId: encounter.patientId, caseId: 'synthetic-case', kind: 'SLIDE', route: 'DIRECT_CYTOLOGY', operation: 'ORIGINAL', number: 'DEV-S-SYNTHETIC', barcode, recordId: null, cassetteId: null, containerId: cid, blockId: null, sourceSlideId: null, technicalTaskId: null, state: 'ACTIVE', version: 0 };
  const job = { id: jid, containerId: null, materialId: mid, targetId: mid, barcode, parentJobId: null, templateVersion: 'SYN-MATERIAL-1', state: 'PREVIEW_READY', version: 0, attempts: 1, patientId: encounter.patientId, patientLabel: encounter.patientLabel, encounterNumber: encounter.encounterNumber, requestNumber: detail.requestNumber, caseNumber: 'DEV-P-SYNTHETIC', site: 'DEV-S-SYNTHETIC / SLIDE / DIRECT_CYTOLOGY', laterality: 'UNKNOWN', reason: 'Synthetic initial', createdBy: 'synthetic-user', createdAt: '2026-01-01T08:00:00Z' };
  await page.route('**/api/requests?*', route => route.fulfill({ json: { items: [detail], total: 1, page: 1, pageSize: 20 } }));
  await page.route('**/api/requests/' + rid, route => route.fulfill({ json: detail }));
  await page.route('**/api/materials/requests/' + rid, route => route.fulfill({ json: { request: detail, caseNumber: 'DEV-P-SYNTHETIC', entities: [entity], tasks: [] } }));
  await page.route('**/api/materials/' + mid, route => route.fulfill({ json: { entity, events: [] } }));
  await page.route('**/api/labels/materials/' + mid, route => route.fulfill({ json: { materialId: mid, requestVersion: 2, materialVersion: 0, state: 'ACTIVE', jobs: [job] } }));
  let invalidated = false; let reads = 0;
  await page.route('**/api/labels/jobs/' + jid, route => { reads++; return invalidated ? route.fulfill({ status: 409, json: { code: 'MATERIAL_INACTIVE' } }) : route.fulfill({ json: { job, events: [] } }); });
  await page.getByRole('button', { name: '刷新', exact: true }).click(); await page.getByTestId('request-row-' + rid).getByRole('button', { name: '查看 ' + detail.requestNumber, exact: true }).click();
  await page.getByRole('button', { name: '处理此病例材料谱系', exact: true }).click(); await page.getByRole('button', { name: '查看材料 ' + mid, exact: true }).click();
  await page.getByLabel('材料操作', { exact: true }).click(); await page.getByRole('option', { name: '标签预览与同实体重打', exact: true }).click();
  await page.getByLabel('选择材料实体').click(); await page.getByRole('option', { name: mid, exact: true }).click();
  await page.getByRole('button', { name: '查看任务 ' + jid, exact: true }).click();
  await page.getByRole('button', { name: '打开标签预览', exact: true }).click(); await expect(page.getByLabel('合成材料标签预览')).toContainText(mid);
  await page.getByRole('button', { name: '关闭标签预览', exact: true }).click(); invalidated = true;
  await page.getByRole('button', { name: '打开标签预览', exact: true }).click();
  await expect(page.getByText('材料或源蜡块已作废，操作被阻断')).toBeVisible(); await expect(page.getByLabel('合成材料标签预览')).toHaveCount(0);
  expect(reads).toBe(3);
});

test('quality preserves pending identity, confirms dirty operation changes and reuses uncertain intent', async ({ page }) => {
  await start(page);
  const rid = '44444444-4444-4444-8444-444444444444', mid = '66666666-6666-4666-8666-666666666666';
  const detail = { id: rid, scopeId: scope, version: 2, state: 'RECEIVED', requestNumber: 'DEV-AP-QC', patientId: encounter.patientId, patientLabel: encounter.patientLabel, encounterId: encounter.id, encounterNumber: encounter.encounterNumber, department: '合成科室', requestedAt: '2026-01-01T08:00:00Z', clinicalHistory: '合成资料', sampledAt: '2026-01-01T08:00:00Z', containers: [] };
  const item = { subject: { id: mid, requestId: rid, patientId: encounter.patientId, caseId: 'synthetic-case', number: 'DEV-S-QC', kind: 'SLIDE', route: 'DIRECT_CYTOLOGY', state: 'ACTIVE', version: 0, taskId: null, taskVersion: null, blockId: null }, head: { materialId: mid, state: 'PENDING', version: 0, assessmentId: 'synthetic-assessment', repairTaskId: null }, effectiveState: 'PENDING' };
  await page.route('**/api/requests?*', route => route.fulfill({ json: { items: [detail], total: 1, page: 1, pageSize: 20 } }));
  await page.route('**/api/requests/' + rid, route => route.fulfill({ json: detail }));
  await page.route('**/api/quality/requests/' + rid, route => route.fulfill({ json: { requestId: rid, items: [item] } }));
  await page.route('**/api/quality/materials/' + mid, route => route.fulfill({ json: { item, assessments: [], events: [], exceptionReleaseEnabled: false } }));
  await page.getByRole('button', { name: '刷新', exact: true }).click(); await page.getByTestId('request-row-' + rid).getByRole('button', { name: '查看 ' + detail.requestNumber, exact: true }).click();
  await page.getByRole('button', { name: '处理此病例技术QC', exact: true }).click(); await page.getByRole('button', { name: '质检材料 ' + mid, exact: true }).click();
  await expect(page.getByLabel('质检材料身份')).toContainText('PENDING'); await expect(page.getByRole('button', { name: '异常放行（未批准）' })).toBeDisabled();
  await page.getByLabel('核对质检材料 UUID').fill(mid); await page.getByLabel('QC事实与处置原因').fill('合成旧输入');
  await page.getByLabel('质检操作', { exact: true }).click(); await page.getByRole('option', { name: '撤销当前QC', exact: true }).click();
  await page.getByRole('button', { name: '继续编辑', exact: true }).click(); await expect(page.getByLabel('QC事实与处置原因')).toHaveValue('合成旧输入');
  await page.getByLabel('质检操作', { exact: true }).click(); await page.getByRole('option', { name: '撤销当前QC', exact: true }).click(); await page.getByRole('button', { name: '清除并切换' }).click();
  await expect(page.getByLabel('明确质检结论')).toHaveCount(0); await expect(page.getByLabel('核对质检材料 UUID')).toHaveValue('');
  await page.getByLabel('核对质检材料 UUID').fill(mid); await page.getByLabel('QC事实与处置原因').fill('合成撤销');
  const writes: { key: string | undefined; body: string | null }[] = [];
  await page.route('**/api/quality/materials/' + mid + '/revoke', async route => { writes.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() }); await route.abort(); });
  await page.getByRole('button', { name: '提交明确QC操作' }).dblclick(); await expect(page.getByText('QC结果待确认；保留原对象、输入和请求键重试。')).toBeVisible(); expect(writes).toHaveLength(1);
  await expect(page.getByLabel('质检材料身份')).toContainText('PENDING'); await expect(page.getByLabel('质检操作', { exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '申请单查询', exact: true }).click(); await expect(page.getByText('原请求结果尚未确认，请先使用页面上的原请求确认结果。')).toBeVisible();
  await page.getByRole('button', { name: '重试原QC请求' }).click(); await expect.poll(() => writes.length).toBe(2); expect(writes[1]).toEqual(writes[0]);
  expect(JSON.parse(writes[0].body ?? '{}')).toEqual({ expectedVersion: 0, confirmedMaterialId: mid, reason: '合成撤销' });
  await page.screenshot({ path: 'test-results/quality-desktop.png', fullPage: true }); await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true); await page.screenshot({ path: 'test-results/quality-mobile.png', fullPage: true });
});

test('worklist clears cross-page selections and uncertain batch retries exact original items', async ({ page }) => {
  await start(page);
  const task = (id: string) => ({ kind: 'TECHNICAL', id, requestId: '44444444-4444-4444-8444-444444444444', patientId: encounter.patientId, requestNumber: 'DEV-AP-WORK', state: 'QUEUED', version: 0, createdAt: '2026-01-01T00:00:00Z', cassetteId: '55555555-5555-4555-8555-555555555555', blocked: false, active: true, dueAt: '2026-01-01T04:00:00Z', overdue: true });
  const a = task('66666666-6666-4666-8666-666666666666'), b = task('77777777-7777-4777-8777-777777777777');
  await page.route('**/api/worklists/scopes/' + scope + '?*', route => { const params = new URL(route.request().url()).searchParams; const current = Number(params.get('page')); return route.fulfill({ json: { total: 11, page: current, pageSize: Number(params.get('pageSize')), asOf: '2026-01-01T05:00:00Z', syntheticDueMinutes: 240, items: current === 1 ? [a] : [b] } }); });
  await page.getByRole('button', { name: '工作列表与追踪', exact: true }).click();
  await page.getByRole('checkbox', { name: '选择任务 ' + a.id, exact: true }).check(); await page.getByLabel('批量领取原因').fill('合成第一页面');
  await page.getByTitle('2', { exact: true }).click(); await page.getByRole('button', { name: '清除并切换', exact: true }).click();
  await expect(page.getByText('本页明确选择：0 项（最多20项）', { exact: true })).toBeVisible(); await expect(page.getByLabel('批量领取原因')).toHaveValue('');
  await page.getByRole('checkbox', { name: '选择任务 ' + b.id, exact: true }).check(); await page.getByLabel('批量领取原因').fill('合成第二页面');
  const writes: string[] = [];
  await page.route('**/api/worklists/scopes/' + scope + '/claims', async route => { writes.push(route.request().postData() ?? ''); await route.abort(); });
  await page.getByRole('button', { name: '核对所选并批量领取' }).click(); await expect(page.getByRole('dialog')).toContainText(b.id); await expect(page.getByRole('dialog')).not.toContainText(a.id);
  await page.getByRole('button', { name: '确认逐项领取' }).dblclick(); await expect(page.getByText('批次结果待确认；不得假定整批回滚，请用原批次确认结果。')).toBeVisible(); expect(writes).toHaveLength(1);
  await expect(page.getByLabel('工作类别')).toBeDisabled(); await page.getByRole('button', { name: '申请单查询', exact: true }).click(); await expect(page.getByText('原请求结果尚未确认，请先使用页面上的原请求确认结果。')).toBeVisible();
  await page.getByRole('button', { name: '原批次确认/重试' }).click(); await expect.poll(() => writes.length).toBe(2); expect(writes[1]).toEqual(writes[0]);
  const sent = JSON.parse(writes[0]) as { items: { taskId: string; expectedVersion: number; confirmedCassetteId: string }[]; reason: string };
  expect(sent.items).toEqual([{ taskId: b.id, expectedVersion: 0, confirmedCassetteId: b.cassetteId }]); expect(sent.reason).toBe('合成第二页面');
  await page.screenshot({ path: 'test-results/worklist-desktop.png', fullPage: true }); await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true); await page.screenshot({ path: 'test-results/worklist-mobile.png', fullPage: true });
});

test('worklist partial results preserve successes while denied query is not empty data', async ({ page }) => {
  await start(page);
  const tasks = ['66666666-6666-4666-8666-666666666666', '77777777-7777-4777-8777-777777777777'].map(id => ({ kind: 'TECHNICAL', id, requestId: '44444444-4444-4444-8444-444444444444', patientId: encounter.patientId, requestNumber: 'DEV-AP-WORK', state: 'QUEUED', version: 0, createdAt: '2026-01-01T00:00:00Z', cassetteId: '55555555-5555-4555-8555-555555555555', blocked: false, active: true, dueAt: '2026-01-01T04:00:00Z', overdue: false }));
  await page.route('**/api/worklists/scopes/' + scope + '?*', route => route.fulfill({ json: { total: 2, page: 1, pageSize: 10, asOf: '2026-01-01T04:00:00Z', syntheticDueMinutes: 240, items: tasks } }));
  await page.route('**/api/worklists/scopes/' + scope + '/claims', route => { const input = route.request().postDataJSON() as { batchId: string }; return route.fulfill({ json: { batchId: input.batchId, items: [{ taskId: tasks[0].id, outcome: 'SUCCESS', status: 200, code: 'CLAIMED', version: 1, replayed: false }, { taskId: tasks[1].id, outcome: 'REJECTED', status: 409, code: 'VERSION_CONFLICT', version: null, replayed: false }] } }); });
  await page.getByRole('button', { name: '工作列表与追踪', exact: true }).click();
  for (const task of tasks) await page.getByRole('checkbox', { name: '选择任务 ' + task.id, exact: true }).check();
  await page.getByLabel('批量领取原因').fill('合成部分成功'); await page.getByRole('button', { name: '核对所选并批量领取' }).click(); await page.getByRole('button', { name: '确认逐项领取' }).click();
  await expect(page.getByRole('cell', { name: 'SUCCESS', exact: true })).toHaveCount(1); await expect(page.getByRole('cell', { name: 'REJECTED', exact: true })).toHaveCount(1);
  await expect(page.getByText('本页明确选择：0 项（最多20项）', { exact: true })).toBeVisible();
  await page.route('**/api/worklists/scopes/' + scope + '?*', route => route.fulfill({ status: 404, json: { code: 'WORKLIST_NOT_FOUND' } }));
  await page.getByRole('button', { name: '刷新工作列表' }).click(); await expect(page.getByText('无权查看', { exact: true })).toBeVisible();
  await expect(page.getByText('当前授权过滤集没有工作项；不是未来模块的零统计。')).toHaveCount(0);
});

test('diagnosis uncertain claim keeps case version and key and prevents navigation', async ({ page }) => {
  await start(page);
  const id = '88888888-8888-4888-8888-888888888888';
  let saved = false;
  const item = () => ({ caseId: id, requestId: 'synthetic-request', patientId: encounter.patientId, number: 'SYN-DIAG', state: saved ? 'ACTIVE' : 'UNASSIGNED', version: saved ? 0 : -1, ownerId: saved ? 'synthetic-user' : null, ready: true });
  await page.route('**/api/requests/diagnosis/scopes/*', r => r.fulfill({ json: { total: 1, page: 1, pageSize: 10, items: [item()] } }));
  await page.route('**/api/requests/diagnosis/cases/' + id, r => r.fulfill({ json: { item: item(), actorId: 'synthetic-user', canAssign: true, canDiagnose: true, candidates: [{ id: 'synthetic-user', name: '合成本人' }], events: [] } }));
  const sent: { key: string; body: unknown }[] = [];
  await page.route('**/api/requests/diagnosis/cases/' + id + '/claim', async r => { sent.push({ key: r.request().headers()['idempotency-key'], body: r.request().postDataJSON() as unknown }); if (sent.length === 1) await r.abort('failed'); else { saved = true; await r.fulfill({ json: { receipt: { status: 200, resourceType: 'DIAGNOSIS_ASSIGNMENT', resourceId: id, version: 0 }, replayed: true } }); } });
  await page.getByRole('button', { name: '诊断分配与领取', exact: true }).click();
  await page.getByRole('button', { name: '处理诊断分配 ' + id, exact: true }).click();
  await page.getByLabel('核对诊断病例 UUID').fill(id); await page.getByLabel('分配领取转交原因').fill('合成领取原因');
  await page.getByRole('button', { name: '提交诊断分配操作', exact: true }).dblclick();
  await expect(page.getByRole('button', { name: '重试原诊断分配请求' })).toBeVisible(); expect(sent).toHaveLength(1);
  await expect(page.getByLabel('诊断分配身份')).toContainText('UNASSIGNED');
  await page.getByRole('button', { name: '申请单查询', exact: true }).click(); await expect(page.getByText('原请求结果尚未确认，请先使用页面上的原请求确认结果。')).toBeVisible();
  await page.getByRole('button', { name: '重试原诊断分配请求' }).click(); await expect(page.getByLabel('诊断分配身份')).toContainText('ACTIVE');
  expect(sent).toHaveLength(2); expect(sent[1]).toEqual(sent[0]);
  await expect(page.getByText('原请求结果尚未确认，请先使用页面上的原请求确认结果。')).toHaveCount(0);
  await page.screenshot({ path: 'test-results/diagnosis-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 }); await page.screenshot({ path: 'test-results/diagnosis-mobile.png', fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('diagnosis dirty case switch clears hidden target and distinguishes forbidden from empty', async ({ page }) => {
  await start(page);
  const a = '88888888-8888-4888-8888-888888888888', b = '99999999-9999-4999-8999-999999999999'; let forbidden = false;
  const item = (id: string) => ({ caseId: id, requestId: 'synthetic-request-' + id, patientId: encounter.patientId, number: 'SYN-' + id, state: 'UNASSIGNED', version: -1, ownerId: null, ready: id === a });
  await page.route('**/api/requests/diagnosis/scopes/*', r => forbidden ? r.fulfill({ status: 404, json: { code: 'DIAGNOSIS_NOT_FOUND' } }) : r.fulfill({ json: { total: 2, page: 1, pageSize: 10, items: [item(a), item(b)] } }));
  for (const id of [a, b]) await page.route('**/api/requests/diagnosis/cases/' + id, r => r.fulfill({ json: { item: item(id), actorId: 'synthetic-user', canAssign: true, canDiagnose: true, candidates: [{ id: 'synthetic-target', name: '合成目标' }], events: [] } }));
  await page.getByRole('button', { name: '诊断分配与领取', exact: true }).click(); await page.getByRole('button', { name: '处理诊断分配 ' + a }).click();
  await page.getByLabel('诊断分配操作', { exact: true }).click(); await page.getByRole('option', { name: '分配给合格人员', exact: true }).click();
  await page.getByLabel('同范围合格人员').click(); await page.getByRole('option', { name: '合成目标 / synthetic-target', exact: true }).click();
  await page.getByLabel('分配领取转交原因').fill('合成未提交原因');
  await page.getByRole('button', { name: '处理诊断分配 ' + a }).click(); await expect(page.getByRole('dialog')).toHaveCount(0); await expect(page.getByLabel('分配领取转交原因')).toHaveValue('合成未提交原因');
  await page.getByLabel('诊断分配操作', { exact: true }).click(); await page.getByRole('option', { name: '本人领取', exact: true }).click();
  await page.getByRole('button', { name: '清除并切换', exact: true }).click(); await expect(page.getByLabel('同范围合格人员')).toHaveCount(0); await expect(page.getByLabel('分配领取转交原因')).toHaveValue('');
  await page.getByLabel('分配领取转交原因').fill('另一个未提交原因'); await page.getByRole('button', { name: '处理诊断分配 ' + b }).click();
  await page.getByRole('button', { name: '放弃并切换', exact: true }).click(); await expect(page.getByLabel('诊断分配身份')).toContainText(b); await expect(page.getByLabel('诊断分配身份')).not.toContainText(a);
  await expect(page.getByLabel('分配领取转交原因')).toHaveValue(''); await expect(page.getByRole('button', { name: '提交诊断分配操作' })).toBeDisabled();
  forbidden = true; await page.getByRole('button', { name: '刷新诊断队列与资格' }).click(); await expect(page.getByText('诊断对象或范围不可用，或没有诊断授权。')).toBeVisible();
});

test('late diagnosis detail cannot replace the newly selected case', async ({ page }) => {
  await start(page);
  const a = '88888888-8888-4888-8888-888888888888', b = '99999999-9999-4999-8999-999999999999';
  const item = (id: string) => ({ caseId: id, requestId: 'request-' + id, patientId: encounter.patientId, number: 'SYN-' + id, state: 'UNASSIGNED', version: -1, ownerId: null, ready: false });
  const detail = (id: string) => ({ item: item(id), actorId: 'synthetic-user', canAssign: true, canDiagnose: true, candidates: [], events: [] });
  await page.route('**/api/requests/diagnosis/scopes/*', r => r.fulfill({ json: { total: 2, page: 1, pageSize: 10, items: [item(a), item(b)] } }));
  let release: () => void = () => {}; const hold = new Promise<void>(resolve => { release = resolve; });
  let finish: () => void = () => {}; const finished = new Promise<void>(resolve => { finish = resolve; });
  await page.route('**/api/requests/diagnosis/cases/' + a, async r => { await hold; try { await r.fulfill({ json: detail(a) }); } finally { finish(); } });
  await page.route('**/api/requests/diagnosis/cases/' + b, r => r.fulfill({ json: detail(b) }));
  await page.getByRole('button', { name: '诊断分配与领取', exact: true }).click();
  const started = page.waitForRequest(r => r.url().endsWith('/cases/' + a)); await page.getByRole('button', { name: '处理诊断分配 ' + a }).click(); await started;
  await page.getByRole('button', { name: '处理诊断分配 ' + b }).click(); await expect(page.getByLabel('诊断分配身份')).toContainText(b);
  release(); await finished; await expect(page.getByLabel('诊断分配身份')).toContainText(b); await expect(page.getByLabel('诊断分配身份')).not.toContainText(a);
});

test('report template cancel preserves draft; switch clears fields; uncertain save retries exact revision', async ({ page }) => {
  await start(page); const id = '88888888-8888-4888-8888-888888888888'; let saved = false;
  const item = { caseId: id, requestId: 'synthetic-request', patientId: encounter.patientId, number: 'SYN-REPORT', state: 'ACTIVE', version: 0, ownerId: 'synthetic-user', ready: true };
  const templates = [{ code: 'SYN-REPORT', version: 1, title: '合成文本草稿', schemaCode: 'SYN-TEXT-1' }, { code: 'SYN-REPORT', version: 2, title: '合成结构草稿', schemaCode: 'SYN-STRUCTURED-2' }];
  const fields = { gross: '', microscopy: '', diagnosis: '合成人工输入二', notes: '', sampleCount: 2, manualChecked: false };
  const revision = { id: 'synthetic-revision', caseId: id, version: 0, templateCode: 'SYN-REPORT', templateVersion: 2, fields, assignmentVersion: 0, authorId: 'synthetic-user', reason: '合成修订', createdAt: '2026-10-03T00:00:00Z' };
  await page.route('**/api/requests/diagnosis/scopes/*', r => r.fulfill({ json: { total: 1, page: 1, pageSize: 10, items: [item] } }));
  await page.route('**/api/requests/reports/cases/' + id, r => r.fulfill({ json: { context: { ...item, assignmentVersion: 0 }, current: saved ? revision : null, templates } }));
  await page.route('**/api/requests/reports/cases/' + id + '/history?*', r => r.fulfill({ json: { caseId: id, page: 1, revisions: saved ? [revision] : [] } }));
  const sent: { body: unknown; key: string }[] = [];
  await page.route('**/api/requests/reports/cases/' + id + '/draft', async r => { sent.push({ body: r.request().postDataJSON() as unknown, key: r.request().headers()['idempotency-key'] }); if (sent.length === 1) await r.abort('failed'); else { saved = true; await r.fulfill({ json: { receipt: { resourceId: id, resourceType: 'REPORT_DRAFT', status: 200, version: 0 }, replayed: true } }); } });
  await page.getByRole('button', { name: '报告草稿', exact: true }).click(); await page.getByRole('button', { name: '编辑报告草稿 ' + id }).click();
  await page.getByLabel('不可变模板版本').click(); await page.getByRole('option', { name: '合成文本草稿 / SYN-REPORT v1', exact: true }).click();
  await page.getByLabel('诊断草稿（人工）').fill('合成人工输入一');
  await page.getByLabel('不可变模板版本').click(); await page.getByRole('option', { name: '合成结构草稿 / SYN-REPORT v2', exact: true }).click();
  await page.getByRole('button', { name: '保留当前草稿', exact: true }).click(); await expect(page.getByLabel('诊断草稿（人工）')).toHaveValue('合成人工输入一');
  await page.getByLabel('不可变模板版本').click(); await page.getByRole('option', { name: '合成结构草稿 / SYN-REPORT v2', exact: true }).click(); await page.getByRole('button', { name: '清除并切换模板' }).click();
  await expect(page.getByLabel('诊断草稿（人工）')).toHaveValue(''); await page.getByLabel('诊断草稿（人工）').fill('合成人工输入二'); await page.getByLabel('合成样本计数').fill('2');
  await page.getByLabel('合成字段已人工核对').click(); await page.getByRole('option', { name: '否', exact: true }).click();
  await page.getByLabel('核对报告病例 UUID').fill(id); await page.getByLabel('草稿修订原因').fill('合成修订');
  await page.getByRole('button', { name: '保存人工草稿' }).dblclick(); await expect(page.getByRole('button', { name: '确认原草稿请求' })).toBeVisible(); expect(sent).toHaveLength(1);
  await page.getByRole('button', { name: '申请单查询', exact: true }).click(); await expect(page.getByText('原请求结果尚未确认，请先使用页面上的原请求确认结果。')).toBeVisible();
  await page.getByRole('button', { name: '确认原草稿请求' }).click(); await expect(page.getByLabel('报告草稿身份')).toContainText('草稿版本 0'); expect(sent[1]).toEqual(sent[0]);
  await page.screenshot({ path: 'test-results/report-desktop.png', fullPage: true }); await page.setViewportSize({ width: 390, height: 844 }); await page.screenshot({ path: 'test-results/report-mobile.png', fullPage: true }); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('report case switch discards late responses and confirms dirty leave or cancellation', async ({ page }) => {
  await start(page); const a = '88888888-8888-4888-8888-888888888888', b = '99999999-9999-4999-8999-999999999999';
  const item = (id: string) => ({ caseId: id, requestId: 'request-' + id, patientId: encounter.patientId, number: 'SYN-' + id, state: 'ACTIVE', version: 0, ownerId: 'synthetic-user', ready: true });
  const detail = (id: string) => ({ context: { ...item(id), assignmentVersion: 0 }, templates: [{ code: 'SYN-REPORT', version: 1, title: '合成文本', schemaCode: 'SYN-TEXT-1' }], current: { id: 'revision-' + id, caseId: id, version: 0, templateCode: 'SYN-REPORT', templateVersion: 1, assignmentVersion: 0, fields: { gross: '', microscopy: '', diagnosis: '合成原稿-' + id, notes: '' }, authorId: 'synthetic-user', reason: 'Synthetic', createdAt: '2026-10-03T00:00:00Z' } });
  await page.route('**/api/requests/diagnosis/scopes/*', r => r.fulfill({ json: { total: 2, page: 1, pageSize: 10, items: [item(a), item(b)] } }));
  for (const id of [a, b]) await page.route('**/api/requests/reports/cases/' + id + '/history?*', r => r.fulfill({ json: { caseId: id, page: 1, revisions: [] } }));
  let release: () => void = () => {}; const hold = new Promise<void>(r => { release = r; }); let finish: () => void = () => {}; const finished = new Promise<void>(r => { finish = r; }); let slow = true;
  await page.route('**/api/requests/reports/cases/' + a, async r => { if (slow) { slow = false; await hold; try { await r.fulfill({ json: detail(a) }); } finally { finish(); } } else await r.fulfill({ json: detail(a) }); });
  await page.route('**/api/requests/reports/cases/' + b, r => r.fulfill({ json: detail(b) }));
  await page.getByRole('button', { name: '报告草稿', exact: true }).click(); const started = page.waitForRequest(r => r.url().endsWith('/reports/cases/' + a)); await page.getByRole('button', { name: '编辑报告草稿 ' + a }).click(); await started;
  await page.getByRole('button', { name: '编辑报告草稿 ' + b }).click(); await expect(page.getByLabel('报告草稿身份')).toContainText(b); release(); await finished; await expect(page.getByLabel('诊断草稿（人工）')).toHaveValue('合成原稿-' + b);
  await page.getByLabel('诊断草稿（人工）').fill('合成未保存B'); await page.getByRole('button', { name: '编辑报告草稿 ' + a }).click(); await page.getByRole('button', { name: '继续编辑', exact: true }).click(); await expect(page.getByLabel('诊断草稿（人工）')).toHaveValue('合成未保存B');
  await page.getByRole('button', { name: '编辑报告草稿 ' + a }).click(); await page.getByRole('button', { name: '放弃并切换', exact: true }).click(); await expect(page.getByLabel('诊断草稿（人工）')).toHaveValue('合成原稿-' + a);
  await page.getByLabel('诊断草稿（人工）').fill('合成未保存A'); await page.getByRole('button', { name: '申请单查询', exact: true }).click(); await page.getByRole('button', { name: '继续编辑', exact: true }).click(); await expect(page.getByLabel('诊断草稿（人工）')).toHaveValue('合成未保存A');
});

function reviewFixture(id: string, state = 'DRAFT', version = -1) {
 return { caseId: id, patientId: encounter.patientId, number: 'SYN-REVIEW', assignmentVersion: 0, version, state, ready: state === 'APPROVED', canReview: true, canSimulateSign: true, dependencyToken: 'a'.repeat(64), policy: { code: 'SYN-REVIEW-1', separateAuthorReview: false, separateReviewSign: false }, draft: { id: 'revision-' + id, caseId: id, version: 0, templateCode: 'SYN-REPORT', templateVersion: 1, fields: { gross: '', microscopy: '', diagnosis: '合成人工正文', notes: '' }, assignmentVersion: 0, authorId: 'synthetic-author', reason: 'Synthetic', createdAt: '2026-10-03T00:00:00Z' }, events: [] };
}
test('review retries original key, cancels action and simulation, preserves conflict input and freezes confirmed simulation', async ({ page }) => {
 await start(page); await page.route('**/review/history?*', r => r.fulfill({ json: { caseId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', page: 1, events: [] } })); const id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'; let state = 'DRAFT', version = -1;
 const item = { caseId: id, requestId: 'synthetic-request', patientId: encounter.patientId, number: 'SYN-REVIEW', state: 'ACTIVE', version: 0, ownerId: 'synthetic-user', ready: true };
 await page.route('**/api/requests/diagnosis/scopes/*', r => r.fulfill({ json: { total: 1, page: 1, pageSize: 10, items: [item] } }));
 await page.route('**/api/requests/reports/cases/' + id + '/review', r => r.fulfill({ json: reviewFixture(id, state, version) }));
 const sent: { body: unknown; key: string }[] = []; let signing = 0;
 await page.route('**/api/requests/reports/cases/' + id + '/review/*', async r => { sent.push({ body: r.request().postDataJSON() as unknown, key: r.request().headers()['idempotency-key'] }); if (sent.length === 1) return r.abort('failed'); if (r.request().url().endsWith('SIMULATE_SIGN') && ++signing === 1) return r.fulfill({ status: 409, json: { code: 'REPORT_REVIEW_STALE', title: 'Synthetic conflict' } }); state = r.request().url().endsWith('APPROVE') ? 'APPROVED' : 'SIMULATED_SIGNED'; version++; await r.fulfill({ json: { receipt: { status: 200, resourceType: 'REPORT_REVIEW', resourceId: id, version }, replayed: sent.length === 2 } }); });
 await page.route('**/review/history?*', r => r.fulfill({ json: { caseId: id, page: 1, events: [] } }));
 await page.getByRole('button', { name: '复核与模拟签署', exact: true }).click(); await page.getByRole('button', { name: '复核合成报告 ' + id }).click();
 await page.getByLabel('复核或退回原因').fill('合成复核意见'); await page.getByLabel('合成复核动作').click(); await page.getByRole('option', { name: '退回修改', exact: true }).click(); await page.getByRole('button', { name: '保留当前输入' }).click(); await expect(page.getByLabel('复核或退回原因')).toHaveValue('合成复核意见');
 await page.getByLabel('核对复核病例 UUID').fill(id); await page.getByRole('button', { name: '提交合成复核操作' }).dblclick(); await expect(page.getByRole('button', { name: '确认原复核请求' })).toBeVisible(); expect(sent).toHaveLength(1);
 await page.getByRole('button', { name: '申请单查询', exact: true }).click(); await expect(page.getByText('原请求结果尚未确认，请先使用页面上的原请求确认结果。')).toBeVisible();
 await page.getByRole('button', { name: '确认原复核请求' }).click(); await expect(page.getByLabel('复核版本身份')).toContainText('状态 APPROVED'); expect(sent[1]).toEqual(sent[0]);
 await page.getByLabel('合成复核动作').click(); await page.getByRole('option', { name: '模拟签署（无临床效力）', exact: true }).click(); await page.getByRole('button', { name: '清除并切换动作' }).click(); await expect(page.getByLabel('复核或退回原因')).toHaveValue('');
 await page.getByLabel('核对复核病例 UUID').fill(id); await page.getByLabel('复核或退回原因').fill('合成模拟意见'); await page.getByRole('button', { name: '提交合成复核操作' }).click(); await page.getByRole('button', { name: '取消模拟' }).click(); expect(sent).toHaveLength(2);
 await page.getByRole('button', { name: '提交合成复核操作' }).click(); await page.getByRole('button', { name: '确认合成模拟' }).click(); await expect(page.getByText('复核依赖已变化或职责分离不满足，请重新核对。', { exact: true })).toBeVisible(); await expect(page.getByLabel('复核或退回原因')).toHaveValue('合成模拟意见');
 await page.getByRole('button', { name: '提交合成复核操作' }).click(); await page.getByRole('button', { name: '确认合成模拟' }).click(); await expect(page.getByLabel('复核版本身份')).toContainText('状态 SIMULATED_SIGNED'); await expect(page.getByRole('button', { name: '提交合成复核操作' })).toBeDisabled();
 await page.screenshot({ path: 'test-results/review-desktop.png', fullPage: true }); await page.setViewportSize({ width: 390, height: 844 }); await page.screenshot({ path: 'test-results/review-mobile.png', fullPage: true }); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
test('review late case responses cannot overwrite selection; dirty switching and leaving are cancellable', async ({ page }) => {
 await start(page); await page.route('**/review/history?*', r => r.fulfill({ json: { caseId: new URL(r.request().url()).pathname.split('/')[5], page: 1, events: [] } })); const a = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', b = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
 const item = (id: string) => ({ caseId: id, requestId: 'request-' + id, patientId: encounter.patientId, number: 'SYN-' + id, state: 'ACTIVE', version: 0, ownerId: 'synthetic-user', ready: true });
 await page.route('**/api/requests/diagnosis/scopes/*', r => r.fulfill({ json: { total: 2, page: 1, pageSize: 10, items: [item(a), item(b)] } }));
 let release: () => void = () => {}; const hold = new Promise<void>(r => { release = r; }); let finish: () => void = () => {}; const finished = new Promise<void>(r => { finish = r; }); let slow = true;
 await page.route('**/api/requests/reports/cases/' + a + '/review', async r => { if (slow) { slow = false; await hold; try { await r.fulfill({ json: reviewFixture(a) }); } finally { finish(); } } else await r.fulfill({ json: reviewFixture(a) }); });
 await page.route('**/api/requests/reports/cases/' + b + '/review', r => r.fulfill({ json: reviewFixture(b) }));
 await page.getByRole('button', { name: '复核与模拟签署', exact: true }).click(); const started = page.waitForRequest(r => r.url().endsWith('/' + a + '/review')); await page.getByRole('button', { name: '复核合成报告 ' + a }).click(); await started;
 await page.getByRole('button', { name: '复核合成报告 ' + b }).click(); await expect(page.getByLabel('复核版本身份')).toContainText(b); release(); await finished; await expect(page.getByLabel('复核版本身份')).not.toContainText(a);
 await page.getByLabel('复核或退回原因').fill('B合成意见'); await page.getByRole('button', { name: '复核合成报告 ' + a }).click(); await page.getByRole('button', { name: '继续编辑', exact: true }).click(); await expect(page.getByLabel('复核或退回原因')).toHaveValue('B合成意见');
 await page.getByRole('button', { name: '复核合成报告 ' + a }).click(); await page.getByRole('button', { name: '放弃并切换' }).click(); await expect(page.getByLabel('复核版本身份')).toContainText(a); await expect(page.getByLabel('复核或退回原因')).toHaveValue('');
 await page.getByLabel('复核或退回原因').fill('A合成意见'); await page.getByRole('button', { name: '申请单查询', exact: true }).click(); await page.getByRole('button', { name: '继续编辑', exact: true }).click(); await expect(page.getByLabel('复核或退回原因')).toHaveValue('A合成意见');
});

const fixedPdf = readFileSync(new URL('./fixtures/synthetic-fixed-report.pdf', import.meta.url));
const fixedHash = createHash('sha256').update(fixedPdf).digest('hex');
const outputArtifactId = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
function outputFixture(id: string, exists = true, activityVersion = -1) {
 const signatureId = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', revisionId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
 return { caseId: id, signatureId, signatureVersion: 1, revisionId, dependenciesCurrent: true, activityVersion, artifact: exists ? { id: outputArtifactId, caseId: id, version: 0, signatureId, signatureVersion: 1, revisionId, draftVersion: 1, templateCode: 'SYN-REPORT', templateVersion: 2, schemaCode: 'SYN-STRUCTURED-2', dependencyToken: 'a'.repeat(64), rendererVersion: 'SYN-RASTER-PDF-1', fontHash: '56f62f6e18eabb294a0598bd0fadaed372541189ce03ccbdce3b21cb1d3ebc5b', sha256: fixedHash, byteSize: fixedPdf.length, pages: 4, createdAt: '2026-10-03T00:00:00Z' } : null };
}
test('fixed PDF generation and unknown preview retry keep original bytes and key; preview closes and print cancellation preserves input', async ({ page }) => {
 await start(page); const id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'; let exists = false, version = -1;
 const item = { caseId: id, requestId: 'request', patientId: encounter.patientId, number: 'SYN-OUTPUT', state: 'ACTIVE', version: 0, ownerId: 'synthetic-user', ready: true };
 await page.route('**/api/requests/diagnosis/scopes/*', r => r.fulfill({ json: { total: 1, page: 1, pageSize: 10, items: [item] } }));
 await page.route('**/output/' + outputArtifactId + '/history?*', r => r.fulfill({ json: { caseId: id, artifactId: outputArtifactId, page: 1, events: [] } }));
 await page.route('**/api/requests/reports/cases/' + id + '/output', async r => { if (r.request().method() === 'GET') await r.fulfill({ json: outputFixture(id, exists, version) }); else { exists = true; await r.fulfill({ json: { receipt: { status: 200, resourceType: 'REPORT_ARTIFACT', resourceId: outputArtifactId, version: 0 } } }); } });
 const sent: { body: unknown; key: string }[] = [];
 await page.route('**/output/' + outputArtifactId + '/bytes/PREVIEW', async r => { sent.push({ body: r.request().postDataJSON() as unknown, key: r.request().headers()['idempotency-key'] }); if (sent.length === 1) return r.abort('failed'); version = 0; await r.fulfill({ contentType: 'application/pdf', headers: { 'Content-Length': String(fixedPdf.length), 'X-Artifact-Id': outputArtifactId, 'X-Artifact-SHA256': fixedHash, 'X-Artifact-Version': '0' }, body: fixedPdf }); });
 await page.getByRole('button', { name: '固定PDF与打印记录', exact: true }).click(); await page.getByRole('button', { name: '查看固定产物 ' + id }).click();
 await page.getByLabel('核对输出病例 UUID').fill(id); await page.getByLabel('产物访问或打印记录原因').fill('合成固定产物'); await page.getByRole('button', { name: '生成固定合成PDF' }).dblclick(); await expect(page.getByText('不可变产物', { exact: true })).toBeVisible();
 await page.getByLabel('核对输出病例 UUID').fill(id); await page.getByLabel('产物访问或打印记录原因').fill('合成预览'); await page.getByRole('button', { name: '提交产物操作（不物理打印）' }).dblclick(); await expect(page.getByRole('button', { name: '确认原产物请求' })).toBeVisible(); expect(sent).toHaveLength(1);
 await page.getByRole('button', { name: '申请单查询', exact: true }).click(); await expect(page.getByText('原请求结果尚未确认，请先使用页面上的原请求确认结果。')).toBeVisible();
 await page.getByRole('button', { name: '确认原产物请求' }).click(); await expect(page.getByTitle('合成固定PDF预览')).toBeVisible(); expect(sent[1]).toEqual(sent[0]); await expect(page.getByLabel('输出固定版本')).toContainText('活动版本 0');
 await page.getByRole('button', { name: '关闭PDF预览' }).click(); await expect(page.getByTitle('合成固定PDF预览')).toHaveCount(0);
 await page.getByLabel('产物访问或打印记录原因').fill('合成待登记'); await page.getByLabel('产物操作', { exact: true }).click(); await page.getByRole('option', { name: '登记打印请求（不连接打印机）', exact: true }).click(); await page.getByRole('button', { name: '保留当前输入' }).click(); await expect(page.getByLabel('产物访问或打印记录原因')).toHaveValue('合成待登记');
 await expect(page.getByRole('dialog')).toBeHidden(); await expect(page.getByRole('option', { name: '登记打印请求（不连接打印机）', exact: true })).toBeHidden(); await page.getByText('不可变产物', { exact: true }).click();
 await page.screenshot({ path: 'test-results/output-desktop.png', fullPage: true }); await page.setViewportSize({ width: 390, height: 844 }); await page.screenshot({ path: 'test-results/output-mobile.png', fullPage: true }); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
test('output rejects corrupt binary without displaying PDF and cannot mix late responses or dirty case input', async ({ page }) => {
 await start(page); const a = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', b = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
 const item = (id: string) => ({ caseId: id, requestId: 'request-' + id, patientId: encounter.patientId, number: 'SYN-' + id, state: 'ACTIVE', version: 0, ownerId: 'synthetic-user', ready: true });
 await page.route('**/api/requests/diagnosis/scopes/*', r => r.fulfill({ json: { total: 2, page: 1, pageSize: 10, items: [item(a), item(b)] } }));
 await page.route('**/output/' + outputArtifactId + '/history?*', r => r.fulfill({ json: { caseId: new URL(r.request().url()).pathname.split('/')[5], artifactId: outputArtifactId, page: 1, events: [] } }));
 let release: () => void = () => {}; const hold = new Promise<void>(r => { release = r; }); let finish: () => void = () => {}; const finished = new Promise<void>(r => { finish = r; }); let slow = true;
 await page.route('**/api/requests/reports/cases/' + a + '/output', async r => { if (slow) { slow = false; await hold; try { await r.fulfill({ json: outputFixture(a) }); } finally { finish(); } } else await r.fulfill({ json: outputFixture(a) }); });
 await page.route('**/api/requests/reports/cases/' + b + '/output', r => r.fulfill({ json: outputFixture(b) }));
 await page.getByRole('button', { name: '固定PDF与打印记录', exact: true }).click(); const started = page.waitForRequest(r => r.url().endsWith('/' + a + '/output')); await page.getByRole('button', { name: '查看固定产物 ' + a }).click(); await started; await page.getByRole('button', { name: '查看固定产物 ' + b }).click(); await expect(page.getByLabel('输出固定版本')).toContainText(b); release(); await finished; await expect(page.getByLabel('输出固定版本')).not.toContainText(a);
 await page.getByLabel('产物访问或打印记录原因').fill('B合成意见'); await page.getByRole('button', { name: '查看固定产物 ' + a }).click(); await page.getByRole('button', { name: '继续编辑', exact: true }).click(); await expect(page.getByLabel('产物访问或打印记录原因')).toHaveValue('B合成意见');
 await page.getByRole('button', { name: '查看固定产物 ' + a }).click(); await page.getByRole('button', { name: '放弃并切换' }).click(); await expect(page.getByLabel('产物访问或打印记录原因')).toHaveValue('');
 const corrupted = Buffer.from(fixedPdf); corrupted[100] ^= 1;
 await page.route('**/output/' + outputArtifactId + '/bytes/PREVIEW', r => r.fulfill({ contentType: 'application/pdf', headers: { 'Content-Length': String(corrupted.length), 'X-Artifact-Id': outputArtifactId, 'X-Artifact-SHA256': fixedHash, 'X-Artifact-Version': '0' }, body: corrupted }));
 await page.getByLabel('核对输出病例 UUID').fill(a); await page.getByLabel('产物访问或打印记录原因').fill('合成完整性校验'); await page.getByRole('button', { name: '提交产物操作（不物理打印）' }).click(); await expect(page.getByRole('button', { name: '确认原产物请求' })).toBeVisible(); await expect(page.getByTitle('合成固定PDF预览')).toHaveCount(0);
});

test('output print conflict preserves input; refresh requires review; user self-report and download keep fixed artifact', async ({ page }) => {
 await start(page); const id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', requestId = 'ffffffff-ffff-4fff-8fff-ffffffffffff'; let version = -1, attempts = 0; const events: { id: string; version: number; kind: string; requestId: string | null; actorId: string; reason: string; occurredAt: string }[] = [];
 await page.route('**/api/requests/diagnosis/scopes/*', r => r.fulfill({ json: { total: 1, page: 1, pageSize: 10, items: [{ caseId: id, requestId: 'request', patientId: encounter.patientId, number: 'SYN-OUTPUT', state: 'ACTIVE', version: 0, ownerId: 'synthetic-user', ready: true }] } }));
 await page.route('**/api/requests/reports/cases/' + id + '/output', r => r.fulfill({ json: outputFixture(id, true, version) }));
 await page.route('**/output/' + outputArtifactId + '/history?*', r => r.fulfill({ json: { caseId: id, artifactId: outputArtifactId, page: 1, events: [...events].reverse() } }));
 await page.route('**/output/' + outputArtifactId + '/events/*', async r => { const body = r.request().postDataJSON() as { expectedVersion: number; requestId: string | null; reason: string }; const kind = new URL(r.request().url()).pathname.split('/').at(-1) ?? ''; if (++attempts === 1) { version = 0; return r.fulfill({ status: 409, json: { code: 'VERSION_CONFLICT' } }); } expect(body.expectedVersion).toBe(version); if (kind === 'USER_REPORTED_CANCELLED') expect(body.requestId).toBe(requestId); version++; events.push({ id: kind === 'PRINT_REQUEST' ? requestId : 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', version, kind, requestId: body.requestId, actorId: 'synthetic-user', reason: body.reason, occurredAt: '2026-10-03T00:00:00Z' }); await r.fulfill({ json: { receipt: { status: 200, resourceType: 'REPORT_OUTPUT_EVENT', resourceId: events.at(-1)?.id, version } } }); });
 await page.route('**/output/' + outputArtifactId + '/bytes/DOWNLOAD', r => r.fulfill({ contentType: 'application/pdf', headers: { 'Content-Length': String(fixedPdf.length), 'X-Artifact-Id': outputArtifactId, 'X-Artifact-SHA256': fixedHash, 'X-Artifact-Version': '0' }, body: fixedPdf }));
 await page.getByRole('button', { name: '固定PDF与打印记录', exact: true }).click(); await page.getByRole('button', { name: '查看固定产物 ' + id }).click();
 async function action(label: string) { await page.getByLabel('产物操作', { exact: true }).click(); await page.getByRole('option', { name: label, exact: true }).click(); await page.getByRole('button', { name: '清除并切换操作' }).click(); await page.getByLabel('核对输出病例 UUID').fill(id); await page.getByLabel('产物访问或打印记录原因').fill('合成请求与自报，非硬件成功'); }
 await action('登记打印请求（不连接打印机）'); await page.getByRole('button', { name: '提交产物操作（不物理打印）' }).click(); await expect(page.getByText('版本已变化，请刷新并复核', { exact: true })).toBeVisible(); await expect(page.getByLabel('产物访问或打印记录原因')).toHaveValue('合成请求与自报，非硬件成功');
 await page.getByRole('button', { name: '刷新诊断队列与资格' }).click(); await page.getByRole('button', { name: '放弃并切换' }).click(); await expect(page.getByLabel('输出固定版本')).toContainText('活动版本 0');
 await action('登记打印请求（不连接打印机）'); await page.getByRole('button', { name: '提交产物操作（不物理打印）' }).click(); await expect(page.getByLabel('输出固定版本')).toContainText('活动版本 1');
 await action('用户自报取消'); await page.getByLabel('原打印请求 UUID（见历史）').fill(requestId); await page.getByRole('button', { name: '提交产物操作（不物理打印）' }).click(); await expect(page.getByRole('cell', { name: '用户自报取消', exact: true })).toBeVisible();
 await action('下载固定PDF'); const saved = page.waitForEvent('download'); await page.getByRole('button', { name: '提交产物操作（不物理打印）' }).click(); const download = await saved; expect(download.suggestedFilename()).toBe('synthetic-' + outputArtifactId + '-v0.pdf'); const file = await download.path(); if (!file) throw new Error('Synthetic download missing'); expect(createHash('sha256').update(readFileSync(file)).digest('hex')).toBe(fixedHash);
});

function chainFixture(id: string, pending = false) {
 const node = { id: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', version: 1, kind: 'ADDENDUM', parentId: null, baseSignatureId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', baseRevisionId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', baseDraftVersion: 0, startRevisionId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', startVersion: 1, actorId: 'synthetic-user', reason: '合成人工补充', createdAt: '2026-10-03T00:00:00Z', newSignatureId: null, downstreamState: null };
 return { caseId: id, version: pending ? 1 : 0, assignmentVersion: 0, ready: true, draftId: pending ? node.id : null, revisionId: pending ? node.startRevisionId : node.baseRevisionId, draftVersion: pending ? 1 : 0, frozenSignatureId: node.baseSignatureId, frozenRevisionId: node.baseRevisionId, pending, canCreate: !pending, page: 1, nodes: pending ? [node] : [] };
}
async function startChain(page: Page, ids: string[]) {
 await start(page); const items = ids.map(caseId => ({ caseId, requestId: 'synthetic-request', patientId: encounter.patientId, number: 'SYN-AMEND', state: 'ACTIVE', version: 0, ownerId: 'synthetic-user', ready: true }));
 await page.route('**/api/requests/diagnosis/scopes/**', r => r.fulfill({ json: { items, page: 1, pageSize: 10, total: items.length } })); await page.getByRole('button', { name: '报告补充与更正', exact: true }).click();
}
test('amendment double click and unknown retry preserve base version and key; type cancel and dirty history navigation are explicit', async ({ page }) => {
 const id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'; let pending = false; const sent: { body: unknown; key: string }[] = [];
 await startChain(page, [id]); await page.route('**/cases/' + id + '/amendments?*', r => r.fulfill({ json: chainFixture(id, pending) }));
 await page.route('**/cases/' + id + '/amendments', async r => { sent.push({ body: r.request().postDataJSON() as unknown, key: r.request().headers()['idempotency-key'] }); if (sent.length === 1) return r.abort('failed'); pending = true; await r.fulfill({ json: { receipt: { status: 200, resourceType: 'REPORT_AMENDMENT', resourceId: 'branch', version: 1 }, replayed: true } }); });
 await page.getByRole('button', { name: '查看报告版本链 ' + id }).click(); await page.getByLabel('核对版本链病例 UUID').fill(id); await page.getByLabel('补充／更正强制原因').fill('合成补充理由');
 await page.getByLabel('新版本类型').click(); await page.getByRole('option', { name: '更正报告', exact: true }).click(); await page.getByRole('button', { name: '保留当前类型和输入' }).click(); await expect(page.getByLabel('补充／更正强制原因')).toHaveValue('合成补充理由');
 await page.getByRole('button', { name: '查看当前冻结版' }).click(); await page.getByRole('button', { name: '继续当前操作' }).click(); await expect(page.getByLabel('补充／更正强制原因')).toHaveValue('合成补充理由');
 await page.getByRole('button', { name: '创建新版本草稿' }).dblclick(); await expect(page.getByRole('button', { name: '确认原新版本请求' })).toBeVisible(); expect(sent).toHaveLength(1);
 await page.getByRole('button', { name: '确认原新版本请求' }).click(); await expect(page.getByLabel('版本链身份')).toContainText('链版本 1'); expect(sent[1]).toEqual(sent[0]); await expect(page.getByRole('button', { name: '创建新版本草稿' })).toBeDisabled(); await expect(page.getByText('存在尚未完成的新草稿；旧冻结版尚未被替代')).toBeVisible();
 await page.screenshot({ path: 'test-results/amendment-desktop.png', fullPage: true }); await page.setViewportSize({ width: 390, height: 844 }); await page.screenshot({ path: 'test-results/amendment-mobile.png', fullPage: true }); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
test('amendment late case response is discarded; conflicts preserve reason and case cancellation preserves draft', async ({ page }) => {
 const a = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', b = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'; let release: (() => void) | undefined;
 await startChain(page, [a, b]); await page.route('**/cases/' + a + '/amendments?*', async r => { await new Promise<void>(resolve => { release = resolve; }); await r.fulfill({ json: chainFixture(a) }); }); await page.route('**/cases/' + b + '/amendments?*', r => r.fulfill({ json: chainFixture(b) }));
 await page.getByRole('button', { name: '查看报告版本链 ' + a }).click(); await expect.poll(() => !!release).toBe(true); await page.getByRole('button', { name: '查看报告版本链 ' + b }).click(); release?.(); await expect(page.getByLabel('版本链身份')).toContainText(b);
 await page.getByLabel('核对版本链病例 UUID').fill(b); await page.getByLabel('补充／更正强制原因').fill('合成更正保留'); await page.route('**/cases/' + b + '/amendments', r => r.fulfill({ status: 409, json: { code: 'VERSION_CONFLICT', detail: 'Conflict' } }));
 await page.getByRole('button', { name: '创建新版本草稿' }).click(); await expect(page.getByLabel('补充／更正强制原因')).toHaveValue('合成更正保留'); await page.getByRole('button', { name: '查看报告版本链 ' + a }).click(); await page.getByRole('button', { name: '继续编辑', exact: true }).click(); await expect(page.getByLabel('核对版本链病例 UUID')).toHaveValue(b);
 await page.getByRole('button', { name: '申请单查询', exact: true }).click(); await expect(page.getByRole('dialog')).toBeVisible();
});
test('historical amendment snapshot stays read only and audited PDF uses the selected old artifact', async ({ page }) => {
 const id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', signature = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'; await startChain(page, [id]);
 await page.route('**/cases/' + id + '/amendments?*', r => r.fulfill({ json: chainFixture(id, true) }));
 await page.route('**/amendments/snapshots/' + signature, r => r.fulfill({ json: { caseId: id, signatureId: signature, currentFrozen: false, artifactId: outputArtifactId, revision: { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', caseId: id, version: 0, templateCode: 'SYN-REPORT', templateVersion: 1, fields: { gross: '', microscopy: '', diagnosis: '合成旧版不可改写正文', notes: '' }, assignmentVersion: 0, authorId: 'synthetic-user', reason: '合成原版', createdAt: '2026-10-03T00:00:00Z' } } }));
 await page.route('**/output/' + outputArtifactId, r => r.fulfill({ json: { ...outputFixture(id), artifact: { ...outputFixture(id).artifact, draftVersion: 0, templateVersion: 1 }, dependenciesCurrent: false } })); await page.route('**/output/' + outputArtifactId + '/history?*', r => r.fulfill({ json: { caseId: id, artifactId: outputArtifactId, page: 1, events: [] } }));
 await page.route('**/output/' + outputArtifactId + '/bytes/PREVIEW', r => r.fulfill({ contentType: 'application/pdf', headers: { 'Content-Length': String(fixedPdf.length), 'X-Artifact-Id': outputArtifactId, 'X-Artifact-SHA256': fixedHash, 'X-Artifact-Version': '0' }, body: fixedPdf }));
 await page.getByRole('button', { name: '查看报告版本链 ' + id }).click(); await page.getByRole('button', { name: '查看原冻结版 0' }).click(); await expect(page.getByText('正在查看历史旧版；已被新版替代，原文与PDF未改写')).toBeVisible(); await expect(page.locator('pre')).toContainText('合成旧版不可改写正文');
 await page.getByLabel('产物操作', { exact: true }).click(); await expect(page.getByRole('option', { name: '登记打印请求（不连接打印机）', exact: true })).toHaveCount(0); await page.keyboard.press('Escape');
 await page.getByLabel('核对输出病例 UUID').fill(id); await page.getByLabel('产物访问或打印记录原因').fill('合成旧版预览'); await page.getByRole('button', { name: '提交产物操作（不物理打印）' }).click(); await expect(page.getByTitle('合成固定PDF预览')).toBeVisible(); await page.getByRole('button', { name: '返回新版本草稿操作' }).click(); await expect(page.getByTitle('合成固定PDF预览')).toHaveCount(0);
});

function deliveryFixture(id: string, state = 'QUEUED', version = 0) { return { caseId: id, caStatus: 'NOT_CONFIGURED', items: [{ id: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', artifactId: outputArtifactId, signatureId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', revisionId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', sha256: fixedHash, destination: 'LOCAL_SIM', predecessor: null, version, state, attempts: state === 'QUEUED' ? 0 : 1, attemptId: state === 'QUEUED' ? null : 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', leaseUntil: state === 'ATTEMPTING' ? '2026-10-03T23:00:00Z' : null, nextAt: '2026-10-03T00:00:00Z' }] }; }
async function startDelivery(page: Page, ids: string[]) { await startChain(page, ids); await page.getByRole('button', { name: '本地投递与回执', exact: true }).click(); }
test('delivery unknown claim preserves original key and prevents navigation; HTTP receipt only advances to attempting', async ({ page }) => {
 const id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', op = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'; let state = 'QUEUED', version = 0; const sent: { body: unknown; key: string }[] = [];
 await startDelivery(page, [id]); await page.route('**/cases/' + id + '/deliveries?*', r => r.fulfill({ json: deliveryFixture(id, state, version) })); await page.route('**/deliveries/' + op + '/history?*', r => r.fulfill({ json: [] }));
 await page.route('**/deliveries/' + op + '/CLAIM', async r => { sent.push({ body: r.request().postDataJSON() as unknown, key: r.request().headers()['idempotency-key'] }); if (sent.length === 1) return r.abort('failed'); state = 'ATTEMPTING'; version = 1; await r.fulfill({ json: { receipt: { status: 200, resourceType: 'REPORT_DELIVERY', resourceId: op, version: 1 } } }); });
 await page.getByRole('button', { name: '查看本地投递 ' + id }).click(); await page.getByRole('button', { name: '处理投递 ' + op }).click(); await page.getByLabel('核对投递病例UUID').fill(id); await page.getByLabel('本地投递操作原因').fill('合成领取尝试'); await page.getByRole('button', { name: '提交本地投递操作' }).dblclick(); await expect(page.getByRole('button', { name: '确认原投递请求' })).toBeVisible(); expect(sent).toHaveLength(1);
 await page.getByRole('button', { name: '申请单查询', exact: true }).click(); await expect(page.getByText('原请求结果尚未确认，请先使用页面上的原请求确认结果。')).toBeVisible(); await page.getByRole('button', { name: '确认原投递请求' }).click(); await expect(page.getByText('ATTEMPTING', { exact: true })).toBeVisible(); expect(sent[1]).toEqual(sent[0]); await expect(page.getByText('RECONCILED', { exact: true })).toHaveCount(0);
 await page.screenshot({ path: 'test-results/delivery-desktop.png', fullPage: true }); await page.setViewportSize({ width: 390, height: 844 }); await page.screenshot({ path: 'test-results/delivery-mobile.png', fullPage: true }); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
test('delivery wrong ACK conflict keeps input; dirty cancel and late case response cannot switch objects silently', async ({ page }) => {
 const a = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', b = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', op = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'; let release: (() => void) | undefined;
 await startDelivery(page, [a, b]); await page.route('**/cases/' + a + '/deliveries?*', async r => { await new Promise<void>(resolve => { release = resolve; }); await r.fulfill({ json: deliveryFixture(a) }); }); await page.route('**/cases/' + b + '/deliveries?*', r => r.fulfill({ json: deliveryFixture(b, 'ATTEMPTING', 1) })); await page.route('**/deliveries/' + op + '/history?*', r => r.fulfill({ json: [] }));
 await page.getByRole('button', { name: '查看本地投递 ' + a }).click(); await expect.poll(() => !!release).toBe(true); await page.getByRole('button', { name: '查看本地投递 ' + b }).click(); release?.(); await expect(page.getByText('病例 ' + b + ' · CA NOT_CONFIGURED')).toBeVisible();
 await page.getByRole('button', { name: '处理投递 ' + op }).click(); await page.getByLabel('本地投递动作').click(); await page.getByRole('option', { name: '验证本地业务ACK', exact: true }).click(); await page.getByLabel('核对投递病例UUID').fill(b); await page.getByLabel('本地投递操作原因').fill('合成错误ACK保留原因'); await page.route('**/deliveries/' + op + '/ACK', r => r.fulfill({ status: 409, json: { code: 'DELIVERY_ACK_MISMATCH' } })); await page.getByRole('button', { name: '提交本地投递操作' }).click(); await expect(page.getByText('缺少匹配的本地业务接收证据，不能确认ACK。')).toBeVisible();
 await page.getByRole('button', { name: '新建本地投递', exact: true }).click(); await page.getByRole('button', { name: '继续当前投递' }).click(); await expect(page.getByLabel('本地投递操作原因')).toHaveValue('合成错误ACK保留原因'); await page.getByRole('button', { name: '查看本地投递 ' + a }).click(); await page.getByRole('button', { name: '继续编辑', exact: true }).click(); await expect(page.getByLabel('核对投递病例UUID')).toHaveValue(b);
});

async function startFrozen(page: Page, ids: string[]) {
 await start(page);
 const rid = '44444444-4444-4444-8444-444444444444', cid = '55555555-5555-4555-8555-555555555555';
 const detail = { id: rid, scopeId: scope, version: 2, state: 'RECEIVED', requestNumber: 'DEV-AP-FROZEN', patientId: encounter.patientId, patientLabel: encounter.patientLabel, encounterId: encounter.id, encounterNumber: encounter.encounterNumber, department: '合成科室', requestedAt: '2026-01-01T08:00:00Z', clinicalHistory: '合成资料', sampledAt: '2026-01-01T08:00:00Z', containers: [{ id: cid, site: '合成冰冻部位', laterality: 'UNKNOWN', materialQuantity: 1, fixative: '合成', fixedAt: '2026-01-01T08:10:00Z' }] };
 await page.route('**/api/requests?*', r => r.fulfill({ json: { items: [detail], total: 1, page: 1, pageSize: 20 } }));
 await page.route('**/api/requests/' + rid, r => r.fulfill({ json: detail }));
 await page.route('**/api/requests/' + rid + '/frozen-cases', r => r.fulfill({ json: ids.map(id => ({ id, number: 'SYN-FROZEN' })) }));
 await page.getByRole('button', { name: '刷新', exact: true }).click(); await page.getByRole('button', { name: '查看 DEV-AP-FROZEN', exact: true }).click(); await page.getByRole('button', { name: '处理此申请冰冻', exact: true }).click();
}
function frozenView(id: string) { return { reviewToken: null, caseId: id, number: 'SYN-FROZEN', actorId: 'synthetic-user', head: null, gateReady: true, reviewValid: false, receivedAt: null, preparedAt: null, elapsedSeconds: null, page: 1, sources: [{ id: '55555555-5555-4555-8555-555555555555', site: '合成冰冻部位' }], candidates: [], events: [] }; }
test('frozen dirty stage and case navigation can cancel; switching clears inputs and mobile stays bounded', async ({ page }) => {
 const a = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', b = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
 await page.route('**/api/requests/frozen/cases/*?page=1', route => route.fulfill({ json: frozenView(new URL(route.request().url()).pathname.split('/').pop() ?? '') }));
 await startFrozen(page, [a, b]); await page.getByRole('button', { name: 'SYN-FROZEN / ' + a, exact: true }).click();
 await page.getByLabel('本次记录／迟补／更正原因').fill('Synthetic unsaved frozen text');
 await page.getByLabel('冰冻阶段操作').click(); await page.getByRole('option', { name: '保存人工结果新修订', exact: true }).click();
 await expect(page.getByRole('dialog')).toBeVisible(); await page.getByRole('button', { name: '继续编辑', exact: true }).click(); await expect(page.getByLabel('本次记录／迟补／更正原因')).toHaveValue('Synthetic unsaved frozen text');
 await page.getByRole('button', { name: 'SYN-FROZEN / ' + b, exact: true }).click(); await page.getByRole('button', { name: '放弃并切换', exact: true }).click();
 await expect(page.getByLabel('冰冻病例身份')).toContainText(b); await expect(page.getByLabel('本次记录／迟补／更正原因')).toBeEmpty();
 await page.screenshot({ path: 'test-results/frozen-desktop.png', fullPage: true, animations: 'disabled' });
 await page.setViewportSize({ width: 390, height: 844 }); await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true); await page.screenshot({ path: 'test-results/frozen-mobile.png', fullPage: true, animations: 'disabled' });
});
test('frozen unknown double click keeps original identity time and idempotency key; conflict permits explicit refresh', async ({ page }) => {
 const id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
 await page.route('**/api/requests/frozen/cases/' + id + '?page=1', r => r.fulfill({ json: frozenView(id) }));
 const writes: { key: string | undefined; body: string | null }[] = [];
 await page.route('**/api/requests/frozen/cases/' + id + '/RECEIVE', async r => { writes.push({ key: r.request().headers()['idempotency-key'], body: r.request().postData() }); if (writes.length === 1) await r.abort(); else await r.fulfill({ status: 409, json: { code: 'VERSION_CONFLICT', detail: 'Synthetic stale version' } }); });
 await startFrozen(page, [id]); await page.getByRole('button', { name: 'SYN-FROZEN / ' + id, exact: true }).click();
 await page.getByLabel('核对冰冻病例 UUID').fill(id); await page.getByLabel('人工发生时间（含 offset）').fill('2026-01-01T12:00:00+08:00'); await page.getByLabel('发生地 IANA 时区').fill('Asia/Shanghai'); await page.getByLabel('核对来源容器').click(); await page.getByRole('option', { name: /合成冰冻部位/ }).click(); await page.getByLabel('冰冻材料人工部位').fill('Synthetic frozen source'); await page.getByLabel('本次记录／迟补／更正原因').fill('Synthetic late entry');
 await page.getByRole('button', { name: '登记冰冻接收并保存', exact: true }).dblclick(); await expect(page.getByRole('button', { name: '确认原冰冻请求' })).toBeVisible(); expect(writes).toHaveLength(1);
 await expect(page.getByLabel('冰冻阶段操作')).toBeDisabled(); await page.getByRole('button', { name: '申请单查询', exact: true }).click(); await expect(page.getByText('原请求结果尚未确认，请先使用页面上的原请求确认结果。')).toBeVisible();
 await page.getByRole('button', { name: '确认原冰冻请求' }).click(); await expect.poll(() => writes.length).toBe(2); expect(writes[1]).toEqual(writes[0]); await expect(page.getByLabel('冰冻阶段操作')).toBeEnabled();
 await page.getByRole('button', { name: '重新读取冰冻版本' }).click(); await page.getByRole('button', { name: '清除并继续' }).click(); await expect(page.getByLabel('本次记录／迟补／更正原因')).toBeEmpty();
});
test('frozen late case response is discarded and read failure remains distinct', async ({ page }) => {
 const a = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', b = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'; let release: (() => void) | undefined; const wait = new Promise<void>(resolve => { release = resolve; }); let called = false;
 await page.route('**/api/requests/frozen/cases/' + a + '?page=1', async r => { called = true; await wait; await r.fulfill({ json: frozenView(a) }).catch(() => {}); });
 await page.route('**/api/requests/frozen/cases/' + b + '?page=1', r => r.fulfill({ status: 404, json: { code: 'FROZEN_NOT_FOUND' } }));
 await startFrozen(page, [a, b]); await page.getByRole('button', { name: 'SYN-FROZEN / ' + a, exact: true }).click(); await expect.poll(() => called).toBe(true); await page.getByRole('button', { name: 'SYN-FROZEN / ' + b, exact: true }).click(); release?.(); await expect(page.getByLabel('冰冻病例身份')).toHaveCount(0);
 await page.route('**/api/requests/frozen/cases/' + b + '?page=1', r => r.fulfill({ json: frozenView(b) })); await page.getByRole('button', { name: '重新读取冰冻版本' }).click(); await expect(page.getByLabel('冰冻病例身份')).toContainText(b); await expect(page.getByLabel('冰冻病例身份')).not.toContainText(a);
});
test('frozen history exposes exact revisions and local readback without inferring confirmation or delivery', async ({ page }) => {
 const id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
 const detail = { ...frozenView(id), head: { id: 'frozen-synthetic', materialId: 'frozen-material', containerId: 'source', site: 'Synthetic', version: 5, ownerId: 'synthetic-user', ownerActive: true, receivedId: 'receive', preparedId: 'prepare', revisionId: 'draft-exact', reviewId: 'review-exact', qcId: 'qc', qcState: 'PASS', stage: 'REVIEWED' }, reviewValid: true, receivedAt: '2026-01-01T08:00:00Z', preparedAt: '2026-01-01T09:00:00Z', elapsedSeconds: 3600, events: [{ id: 'readback-exact', version: 5, action: 'READBACK', actorId: 'receiver-synthetic', targetUserId: 'receiver-synthetic', resultId: 'draft-exact', relatedId: 'communication-exact', occurredAt: '2026-01-01T12:00:00Z', recordedAt: '2026-01-01T13:00:00Z', zoneId: 'UTC', offsetSeconds: 0, reason: 'Synthetic evidence only', content: 'Synthetic manual readback', dependency: null, routineSignatureId: null, routineRevisionId: null, routineTemplateCode: null, routineTemplateVersion: null, comparison: null, communicationMethod: 'LOCAL_SIMULATION' }] };
 await page.route('**/api/requests/frozen/cases/' + id + '?page=1', r => r.fulfill({ json: detail })); await startFrozen(page, [id]); await page.getByRole('button', { name: 'SYN-FROZEN / ' + id, exact: true }).click();
 const history = page.getByRole('row').filter({ hasText: 'readback-exact' }); await expect(history).toContainText('接收者记录合成回读'); await expect(history).toContainText('draft-exact'); await expect(history).toContainText('communication-exact'); await expect(history).toContainText('方式：本地合成记录'); await expect(history).not.toContainText('接收者记录合成确认'); await expect(history).not.toContainText('已送达');
 await expect(page.getByText(/两个记录间隔 3600 秒/)).toBeVisible();
});
