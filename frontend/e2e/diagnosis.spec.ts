import { test, expect, type Page } from '@playwright/test';
import { signInWorkflow } from './workflow-login';

test('qualified assignment, explicit claim, transfer and receiving user claim preserve versions and QC gate', async ({ page, browser }) => {
  await signInWorkflow(page);
  const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
  const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scopes[0].id, number: 'SYN-DIAGNOSIS-001' } })).json() as { id: string; patientId: string }[];
  const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
  const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
  const create = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scopes[0].id, encounterId: encounters[0].id, draft: { clinicalHistory: 'Synthetic diagnosis routing', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic site', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic', fixedAt: '2026-01-01T08:10:00Z' }] } } });
  expect(create.status()).toBe(201); const rid = (await create.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  expect((await page.request.post('/api/requests/' + rid + '/submit', { headers: headers(), data: { expectedVersion: 0 } })).status()).toBe(200);
  const detail = await (await page.request.get('/api/requests/' + rid)).json() as { containers: { id: string }[] };
  const cid = detail.containers[0].id;
  expect((await page.request.post('/api/receptions/' + rid + '/receive', { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-DIAGNOSIS-001', containerIds: [cid] } })).status()).toBe(200);
  const slide = await page.request.post('/api/materials/requests/' + rid + '/direct-slides', { headers: headers(), data: { requestVersion: 2, confirmedContainerId: cid, reason: 'Synthetic direct diagnosis slide' } });
  expect(slide.status()).toBe(201); const mid = (await slide.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  const caseId = (await (await page.request.get('/api/materials/' + mid)).json() as { entity: { caseId: string } }).entity.caseId;
  const path = '/api/requests/diagnosis/cases/' + caseId;
  expect((await page.request.post(path + '/claim', { headers: headers(), data: { expectedVersion: -1, confirmedCaseId: caseId, targetUserId: null, reason: 'Synthetic not ready' } })).status()).toBe(409);
  expect((await page.request.post('/api/quality/materials/' + mid + '/assess', { headers: headers(), data: { expectedVersion: -1, materialVersion: 0, taskVersion: null, confirmedMaterialId: mid, standardVersion: 'SYN-MATERIAL-QC-1', outcome: 'PASS', reason: 'Synthetic prerequisite only' } })).status()).toBe(200);
  const d = await (await page.request.get(path)).json() as { actorId: string; candidates: { id: string; name: string }[] };
  const target = d.candidates.find(c => c.name === '合成交接用户'); expect(target).toBeDefined(); if (!target) throw new Error('Synthetic qualified receiver missing');
  async function open(p: Page) {
    await p.getByRole('button', { name: '申请登记工作区' }).click(); await p.getByLabel('授权工作范围').click(); await p.getByText('合成申请工作范围', { exact: true }).last().click();
    const queue = await (await p.request.get('/api/requests/diagnosis/scopes/' + scopes[0].id + '?pageSize=50')).json() as { items: { caseId: string }[] };
    const position = queue.items.findIndex(i => i.caseId === caseId); expect(position).toBeGreaterThanOrEqual(0);
    const loaded = p.waitForResponse(r => r.url().includes('/api/requests/diagnosis/scopes/') && r.request().method() === 'GET');
    await p.getByRole('button', { name: '诊断分配与领取', exact: true }).click(); await loaded;
    const pageNumber = Math.floor(position / 10) + 1;
    if (pageNumber > 1) await p.getByTitle(String(pageNumber), { exact: true }).click();
    await p.getByRole('button', { name: '处理诊断分配 ' + caseId, exact: true }).click();
  }
  async function act(p: Page, label: string, action: string, version: number, selected?: string) {
    await p.getByLabel('诊断分配操作', { exact: true }).click(); await p.getByRole('option', { name: label, exact: true }).click();
    if (selected) { await p.getByLabel('同范围合格人员').click(); await p.getByRole('option', { name: selected, exact: true }).click(); }
    await p.getByLabel('核对诊断病例 UUID').fill(caseId); await p.getByLabel('分配领取转交原因').fill('Synthetic explicit ' + action);
    const response = p.waitForResponse(r => new URL(r.url()).pathname === path + '/' + action && r.request().method() === 'POST');
    await p.getByRole('button', { name: '提交诊断分配操作' }).click(); expect((await response).status()).toBe(200);
    await expect(p.getByLabel('诊断分配身份')).toContainText('版本 ' + version);
  }
  await open(page);
  await act(page, '分配给合格人员', 'assign', 0, '合成工作流用户 / ' + d.actorId);
  await act(page, '本人领取', 'claim', 1);
  await act(page, '转交并等待对方领取', 'transfer', 2, target.name + ' / ' + target.id);
  await expect(page.getByLabel('诊断分配身份')).toContainText('ASSIGNED');
  const other = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  try {
    const receiver = await other.newPage(); await receiver.goto('/');
    await receiver.getByLabel('用户名', { exact: true }).fill(process.env.PIS_E2E_HANDOFF_USERNAME ?? 'synthetic.technician');
    await receiver.getByLabel('密码', { exact: true }).fill(process.env.PIS_E2E_HANDOFF_PASSWORD ?? 'Synthetic-handoff-only-42!');
    const login = receiver.waitForResponse(r => new URL(r.url()).pathname === '/api/auth/login'); await receiver.getByRole('button', { name: '登录', exact: true }).click(); expect((await login).status()).toBe(204);
    await open(receiver); await act(receiver, '本人领取', 'claim', 3);
    await expect(receiver.getByLabel('诊断分配身份')).toContainText('ACTIVE'); await expect(receiver.getByRole('cell', { name: 'CLAIM', exact: true })).toHaveCount(2);
    expect((await page.request.post('/api/quality/materials/' + mid + '/revoke', { headers: headers(), data: { expectedVersion: 0, confirmedMaterialId: mid, reason: 'Synthetic revoke stops next transfer' } })).status()).toBe(200);
    await receiver.getByRole('button', { name: '刷新诊断队列与资格' }).click(); await expect(receiver.getByText('材料或身份门禁未就绪，分配、领取和转交均被阻断', { exact: true })).toBeVisible();
  } finally { await other.close(); }
});
