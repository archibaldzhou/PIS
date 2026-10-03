import { test, expect, type Page } from '@playwright/test';
import { signInWorkflow, handoffUsername } from './workflow-login';
import type { Detail, Command, Action } from '../src/features/frozen/api';
// Real local Spring/PG requests: no route mocking, no phone/message/CA or external recipient.
test('independent frozen manual workflow requires separate qualified review and receiver evidence', async ({ page, browser }) => {
 await signInWorkflow(page);
 const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
 const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scopes[0].id, number: 'SYN-FROZEN-001' } })).json() as { id: string; patientId: string }[];
 const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
 const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
 const create = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scopes[0].id, encounterId: encounters[0].id, draft: { clinicalHistory: 'Synthetic frozen source only', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic frozen site', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic', fixedAt: '2026-01-01T08:10:00Z' }] } } });
 expect(create.status()).toBe(201); const requestId = (await create.json() as { receipt: { resourceId: string } }).receipt.resourceId;
 expect((await page.request.post('/api/requests/' + requestId + '/submit', { headers: headers(), data: { expectedVersion: 0 } })).status()).toBe(200);
 const source = await (await page.request.get('/api/requests/' + requestId)).json() as { containers: { id: string }[] }; const containerId = source.containers[0].id;
 expect((await page.request.post('/api/receptions/' + requestId + '/receive', { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-FROZEN-001', containerIds: [containerId] } })).status()).toBe(200);
 const items = await (await page.request.get('/api/requests/' + requestId + '/frozen-cases')).json() as { id: string }[]; const id = items[0].id, path = '/api/requests/frozen/cases/' + id;
 async function read(p: Page) { const r = await p.request.get(path); expect(r.status()).toBe(200); return await r.json() as Detail; }
 async function body(p: Page, overrides: Partial<Command['body']> = {}): Promise<Command['body']> { const d = await read(p); return { reviewToken: d.reviewToken, confirmedCaseId: id, expectedVersion: d.head?.version ?? -1, occurredAt: '2026-01-01T12:00:00Z', zoneId: 'UTC', reason: 'Synthetic explicit human record', containerId, site: 'Synthetic frozen site', resultId: d.head?.revisionId ?? null, relatedId: null, targetUserId: null, content: 'Synthetic manually entered workflow text', routineSignatureId: null, comparison: null, ...overrides }; }
 async function act(p: Page, action: Action, overrides: Partial<Command['body']> = {}, status = 200) { const token = await (await p.request.get('/api/auth/csrf')).json() as { token: string }; const r = await p.request.post(path + '/' + action, { headers: { 'X-CSRF-TOKEN': token.token, 'Idempotency-Key': crypto.randomUUID() }, data: await body(p, overrides) }); expect(r.status()).toBe(status); return r; }
 await act(page, 'RECEIVE'); await act(page, 'PREPARE'); await act(page, 'DRAFT', {}, 409); await act(page, 'QC_PASS'); await act(page, 'DRAFT'); await act(page, 'REVIEW', {}, 409);
 const other = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
 try {
  const receiver = await other.newPage(); await receiver.goto('/'); await receiver.getByLabel('用户名', { exact: true }).fill(handoffUsername()); await receiver.getByLabel('密码', { exact: true }).fill(process.env.PIS_E2E_HANDOFF_PASSWORD ?? 'Synthetic-handoff-only-42!'); const login = receiver.waitForResponse(r => new URL(r.url()).pathname === '/api/auth/login'); await receiver.getByRole('button', { name: '登录', exact: true }).click(); expect((await login).status()).toBe(204);
  const receiverId = (await read(receiver)).actorId; await act(receiver, 'REVIEW'); expect((await read(page)).reviewValid).toBe(true);
  await act(page, 'COMMUNICATE', { targetUserId: receiverId }); const relatedId = (await read(page)).events[0].id;
  await act(receiver, 'CONFIRM', { relatedId }, 409); await act(page, 'READBACK', { relatedId }, 409); await act(receiver, 'READBACK', { relatedId }); expect((await read(receiver)).events.some(e => e.action === 'CONFIRM')).toBe(false);
  await act(receiver, 'CONFIRM', { relatedId }); await act(page, 'DRAFT'); expect((await read(page)).reviewValid).toBe(false); await act(receiver, 'CONFIRM', { relatedId }, 409);
  const command = await body(page); const key = headers(); const first = await page.request.post(path + '/DRAFT', { headers: key, data: command }); expect(first.status()).toBe(200); const replay = await page.request.post(path + '/DRAFT', { headers: key, data: command }); expect(replay.status()).toBe(200); expect(replay.headers()['idempotency-replayed']).toBe('true'); expect((await page.request.post(path + '/DRAFT', { headers: headers(), data: command })).status()).toBe(409);
  await act(page, 'TRANSFER', { targetUserId: receiverId }); await act(receiver, 'DRAFT', {}, 404); await act(receiver, 'CLAIM'); await act(receiver, 'DRAFT'); await act(page, 'REVIEW'); expect((await read(page)).reviewValid).toBe(true);
  await act(receiver, 'QC_FAIL'); expect((await read(page)).reviewValid).toBe(false); await act(receiver, 'COMMUNICATE', { targetUserId: (await read(page)).actorId }, 409);
 } finally { await other.close(); }
 await page.getByRole('button', { name: '申请登记工作区' }).click(); await page.getByLabel('授权工作范围').click(); await page.getByText('合成申请工作范围', { exact: true }).last().click();
 const requestDetail = await (await page.request.get('/api/requests/' + requestId)).json() as { requestNumber: string }; await page.getByRole('button', { name: '查看 ' + requestDetail.requestNumber, exact: true }).click(); await page.getByRole('button', { name: '处理此申请冰冻', exact: true }).click(); await page.getByRole('button', { name: new RegExp(id) }).click(); await expect(page.getByLabel('冰冻病例身份')).toContainText('QC FAIL');
});
