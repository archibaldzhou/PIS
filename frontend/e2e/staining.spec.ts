import { test, expect } from '@playwright/test';
import { signInWorkflow } from './workflow-login';
import type { Detail, Body, Action } from '../src/features/materials/stainApi';
// Real Spring/PG, local synthetic fixture only. No HTTP mocks or instrument calls.
test('staining freezes new identities, binds control versions, rejects stale writes and preserves revoked results', async ({ page }) => {
 await signInWorkflow(page);
 const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
 const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scopes[0].id, number: 'SYN-STAIN-001' } })).json() as { id: string; patientId: string }[];
 const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
 const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
 const created = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scopes[0].id, encounterId: encounters[0].id, draft: { clinicalHistory: 'Synthetic staining', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic', laterality: 'UNKNOWN', materialQuantity: 2, fixative: 'Synthetic', fixedAt: '2026-01-01T08:10:00Z' }] } } });
 expect(created.status()).toBe(201); const rid = (await created.json() as { receipt: { resourceId: string } }).receipt.resourceId;
 expect((await page.request.post('/api/requests/' + rid + '/submit', { headers: headers(), data: { expectedVersion: 0 } })).status()).toBe(200);
 const request = await (await page.request.get('/api/requests/' + rid)).json() as { requestNumber: string; containers: { id: string }[] };
 expect((await page.request.post('/api/receptions/' + rid + '/receive', { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-STAIN-001', containerIds: [request.containers[0].id] } })).status()).toBe(200);
 const slides: string[] = [];
 for (let i = 0; i < 2; i++) {
  const r = await page.request.post('/api/materials/requests/' + rid + '/direct-slides', { headers: headers(), data: { requestVersion: 2, confirmedContainerId: request.containers[0].id, reason: 'Synthetic independent source' } });
  expect(r.status()).toBe(201); const id = (await r.json() as { receipt: { resourceId: string } }).receipt.resourceId; slides.push(id);
  expect((await page.request.post('/api/quality/materials/' + id + '/assess', { headers: headers(), data: { expectedVersion: -1, confirmedMaterialId: id, materialVersion: 0, taskVersion: null, standardVersion: 'SYN-MATERIAL-QC-1', outcome: 'PASS', reason: 'Synthetic manual QC' } })).status()).toBe(200);
 }
 const base = '/api/materials/requests/' + rid + '/staining';
 async function read(b: string | null): Promise<Detail> { const r = await page.request.get(base, { params: b ? { batch: b } : {} }); expect(r.status()).toBe(200); return await r.json() as Detail; }
 async function body(b: string | null, overrides: Partial<Body> = {}): Promise<Body> { const d = await read(b); return { confirmedRequestId: rid, expectedVersion: d.selected?.version ?? -1, reason: 'Synthetic manual reason', sources: [], kind: null, projectCode: null, projectVersion: null, schemeCode: null, schemeVersion: null, metadata: null, reagentLot: null, expiresOn: null, controlReference: null, rerunOf: null, orderId: null, frozenVersion: d.selected?.frozenVersion ?? null, controlEventId: d.selected?.controlEventId ?? null, technicalQc: null, content: 'Synthetic manual evidence', ...overrides }; }
 async function act(b: string | null, action: Action, overrides: Partial<Body> = {}, status = 200) { const r = await page.request.post(base + '/' + action, { params: b ? { batch: b } : {}, headers: headers(), data: await body(b, overrides) }); expect(r.status()).toBe(status); return r; }
 let previous: string | null = null;
 for (const [i, kind] of (['IHC', 'SPECIAL'] as const).entries()) {
  const r = await act(null, 'CREATE', { sources: [{ id: slides[i], version: 0 }], kind, projectCode: 'SYN-P', projectVersion: 1, schemeCode: 'SYN-S', schemeVersion: 1, metadata: 'Synthetic metadata; no vendor protocol', reagentLot: 'SYN-LOT', expiresOn: '2099-12-31', controlReference: 'Synthetic non-patient control', rerunOf: previous });
  const b = (await r.json() as { receipt: { resourceId: string } }).receipt.resourceId; expect(b).not.toBe(previous);
  const freeze = await body(b), key = headers(); const url = base + '/FREEZE?batch=' + b;
  expect((await page.request.post(url, { headers: key, data: freeze })).status()).toBe(200);
  const replay = await page.request.post(url, { headers: key, data: freeze }); expect(replay.headers()['idempotency-replayed']).toBe('true'); expect(replay.status()).toBe(200);
  let d = await read(b); const order = d.orders[0]; expect(order.sourceId).toBe(slides[i]); expect(order.outputId).not.toBe(slides[i]); expect(order.effectiveState).toBe('SOURCE_QUARANTINED');
  await act(b, 'ADD', { sources: [{ id: slides[i], version: 0 }] }, 409);
  await act(b, 'CONTROL_PASS', { content: '' }, 409); await act(b, 'CONTROL_PASS');
  const result = await body(b, { orderId: order.id, technicalQc: 'TECH_PASS' });
  const race = await Promise.all([page.request.post(base + '/RESULT?batch=' + b, { headers: headers(), data: result }), page.request.post(base + '/RESULT?batch=' + b, { headers: headers(), data: result })]); expect(race.map(r => r.status()).sort()).toEqual([200, 409]);
  d = await read(b); expect(d.orders[0].effectiveState).toBe('PASS'); const resultId = d.orders[0].resultId;
  await act(b, 'REVOKE'); d = await read(b); expect(d.orders[0].resultId).toBe(resultId); expect(d.orders[0].invalidReason).toBe('CONTROL_REVOKED'); expect(d.orders[0].effectiveState).toBe('SOURCE_QUARANTINED'); previous = b;
 }
 expect((await page.request.get(base, { params: { batch: crypto.randomUUID() } })).status()).toBe(404);
 await page.getByRole('button', { name: '申请登记工作区' }).click(); await page.getByLabel('授权工作范围').click(); await page.getByText('合成申请工作范围', { exact: true }).last().click(); await page.getByRole('button', { name: '查看 ' + request.requestNumber, exact: true }).click(); await page.getByRole('button', { name: '处理此申请染色批次', exact: true }).click(); await page.getByRole('button', { name: '查看染色批次 ' + previous, exact: true }).click(); await expect(page.getByLabel('染色病例身份')).toContainText(rid); await expect(page.getByRole('cell', { name: '未就绪／已隔离 对照已撤销，旧接受记录失效', exact: true })).toBeVisible();
});
