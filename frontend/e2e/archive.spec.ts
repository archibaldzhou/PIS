import { test, expect, type Page } from '@playwright/test';
import { signInWorkflow, handoffUsername } from './workflow-login';
import type { View, Body, Action } from '../src/features/archive/api';

// Real local Spring/PG and separate synthetic actors; no HTTP mocks or invitations sent.
test('archive local roles reserve, approve, scan, return and preserve inventory history', async ({ page, browser }) => {
  await signInWorkflow(page);
  const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
  const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scopes[0].id, number: 'SYN-ARCHIVE-001' } })).json() as { id: string; patientId: string }[];
  const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
  const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
  const create = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scopes[0].id, encounterId: encounters[0].id, draft: { clinicalHistory: 'Synthetic diagnosis routing', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic site', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic', fixedAt: '2026-01-01T08:10:00Z' }] } } });
  expect(create.status()).toBe(201); const rid = (await create.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  expect((await page.request.post('/api/requests/' + rid + '/submit', { headers: headers(), data: { expectedVersion: 0 } })).status()).toBe(200);
  const detail = await (await page.request.get('/api/requests/' + rid)).json() as { containers: { id: string }[] };
  const cid = detail.containers[0].id;
  expect((await page.request.post('/api/receptions/' + rid + '/receive', { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-ARCHIVE-001', containerIds: [cid] } })).status()).toBe(200);
  const slide = await page.request.post('/api/materials/requests/' + rid + '/direct-slides', { headers: headers(), data: { requestVersion: 2, confirmedContainerId: cid, reason: 'Synthetic direct diagnosis slide' } });
  expect(slide.status()).toBe(201); const mid = (await slide.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  const caseId = (await (await page.request.get('/api/materials/' + mid)).json() as { entity: { caseId: string } }).entity.caseId;
  const path = '/api/requests/diagnosis/cases/' + caseId;
  expect((await page.request.post(path + '/claim', { headers: headers(), data: { expectedVersion: -1, confirmedCaseId: caseId, targetUserId: null, reason: 'Synthetic not ready' } })).status()).toBe(409);
  expect((await page.request.post('/api/quality/materials/' + mid + '/assess', { headers: headers(), data: { expectedVersion: -1, materialVersion: 0, taskVersion: null, confirmedMaterialId: mid, standardVersion: 'SYN-MATERIAL-QC-1', outcome: 'PASS', reason: 'Synthetic prerequisite only' } })).status()).toBe(200);
  const url = '/api/requests/' + rid + '/archive';
  async function read(p: Page) { const r = await p.request.get(url); expect(r.status()).toBe(200); return await r.json() as View; }
  async function body(p: Page, fields: Partial<Body> = {}): Promise<Body> { const v = await read(p); return { confirmedRequestId: rid, expectedVersion: v.version, reason: 'Synthetic human custody entry', itemId: null, itemVersion: null, barcode: null, sourceId: null, sourceVersion: null, locationId: null, code: null, policyLabel: null, legalHold: null, loanId: null, borrowerId: null, purpose: null, dueAt: null, items: [], inventoryId: null, referenceId: null, observation: null, ...fields }; }
  async function act(p: Page, action: Action, fields: Partial<Body> = {}, status = 200) { const csrf = await (await p.request.get('/api/auth/csrf')).json() as { token: string }; const r = await p.request.post(url + '/' + action, { headers: { 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() }, data: await body(p, fields) }); expect(r.status()).toBe(status); return r; }
  const source = (await read(page)).sources.find(s => s.id === mid); expect(source).toBeDefined(); if (!source) throw new Error('Synthetic source missing');
  await act(page, 'REGISTER', { sourceId: mid, sourceVersion: 0, barcode: source.barcode, policyLabel: 'SYN-ONLY', legalHold: true });
  const itemId = (await read(page)).items[0].id;
  await act(page, 'INVENTORY'); const inv = (await read(page)).inventories[0].id;
  const self = await (await page.request.get('/api/auth/me')).json() as { id: string };
  await act(page, 'LOAN', { borrowerId: self.id, purpose: 'Synthetic local reread custody', dueAt: new Date(Date.now() + 3600000).toISOString(), items: [{ id: itemId, version: 0 }] });
  const loan = (await read(page)).loans[0].id; await act(page, 'APPROVE', { loanId: loan }, 409);
  const other = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  try {
    const reviewer = await other.newPage(); await reviewer.goto('/'); await reviewer.getByLabel('用户名', { exact: true }).fill(handoffUsername()); await reviewer.getByLabel('密码', { exact: true }).fill(process.env.PIS_E2E_HANDOFF_PASSWORD ?? 'Synthetic-handoff-only-42!'); const login = reviewer.waitForResponse(r => new URL(r.url()).pathname === '/api/auth/login'); await reviewer.getByRole('button', { name: '登录', exact: true }).click(); expect((await login).status()).toBe(204);
    await act(reviewer, 'APPROVE', { loanId: loan }); const reserved = (await read(page)).items[0];
    await act(page, 'CHECKOUT', { itemId, itemVersion: reserved.version, barcode: 'WRONG', loanId: loan }, 409);
    await act(page, 'CHECKOUT', { itemId, itemVersion: reserved.version, barcode: reserved.barcode, loanId: loan }); const out = (await read(page)).items[0];
    await act(page, 'CHECK', { itemId, itemVersion: out.version, barcode: out.barcode, inventoryId: inv, observation: 'MATCH' }, 409);
    const returned = await body(page, { itemId, itemVersion: out.version, barcode: out.barcode, loanId: loan }); const key = headers();
    expect((await page.request.post(url + '/RETURN', { headers: key, data: returned })).status()).toBe(200); const replay = await page.request.post(url + '/RETURN', { headers: key, data: returned }); expect(replay.status()).toBe(200); expect(replay.headers()['idempotency-replayed']).toBe('true');
    const end = await read(page); expect(end.loans[0].state).toBe('CLOSED'); expect(end.loanItems[0].state).toBe('RETURNED'); expect(end.events.every(e => e.physicalConfirmation === 'UNVERIFIED')).toBe(true);
    await page.getByRole('button', { name: '申请登记工作区' }).click(); await page.getByLabel('授权工作范围').click(); await page.getByText('合成申请工作范围', { exact: true }).last().click(); await page.getByLabel('申请号 / 就诊号 / 患者双标识').fill((await (await page.request.get('/api/requests/' + rid)).json() as { requestNumber: string }).requestNumber); await page.getByRole('button', { name: '查询', exact: true }).click(); const number = (await (await page.request.get('/api/requests/' + rid)).json() as { requestNumber: string }).requestNumber; await page.getByRole('button', { name: '查看 ' + number, exact: true }).click(); await page.getByRole('button', { name: '处理此申请档案', exact: true }).click(); await expect(page.getByText('人工登记已归还', { exact: false }).first()).toBeVisible();
  } finally { await other.close(); }
});
