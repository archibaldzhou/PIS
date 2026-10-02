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
