import { test, expect } from '@playwright/test';
import { signInWorkflow } from './workflow-login';
import type { Detail, Command, Action } from '../src/features/materials/cytologyApi';
// Real local Spring/PG workflow; synthetic fixture only, no mocked HTTP or clinical output.
test('cytology direct, liquid and cell-block preparations retain exact lineage and fail closed after source QC changes', async ({ page }) => {
 await signInWorkflow(page);
 const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
 const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scopes[0].id, number: 'SYN-CYTOLOGY-001' } })).json() as { id: string; patientId: string }[];
 const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string }; const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
 const create = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scopes[0].id, encounterId: encounters[0].id, draft: { clinicalHistory: 'Synthetic cytology source', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic cytology', laterality: 'UNKNOWN', materialQuantity: 5, fixative: 'Synthetic', fixedAt: '2026-01-01T08:10:00Z' }] } } });
 expect(create.status()).toBe(201); const rid = (await create.json() as { receipt: { resourceId: string } }).receipt.resourceId;
 expect((await page.request.post('/api/requests/' + rid + '/submit', { headers: headers(), data: { expectedVersion: 0 } })).status()).toBe(200);
 const source = await (await page.request.get('/api/requests/' + rid)).json() as { containers: { id: string }[] }; const cid = source.containers[0].id;
 expect((await page.request.post('/api/receptions/' + rid + '/receive', { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-CYTOLOGY-001', containerIds: [cid] } })).status()).toBe(200);
 const url = '/api/materials/requests/' + rid + '/cytology/' + cid;
 async function read() { const r = await page.request.get(url); expect(r.status()).toBe(200); return await r.json() as Detail; }
 async function command(overrides: Partial<Command['body']> = {}): Promise<Command['body']> { const d = await read(); return { confirmedContainerId: cid, expectedVersion: d.specimen?.version ?? -1, reason: 'Synthetic manual reason', metadata: 'Synthetic manually entered method and medium', path: null, transferred: 0, repeatOf: null, preparationId: null, preparationVersion: -1, consumed: 0, discarded: 0, returned: 0, slides: 0, ...overrides }; }
 async function act(action: Action, overrides: Partial<Command['body']> = {}, status = 200) { const r = await page.request.post(url + '/' + action, { headers: headers(), data: await command(overrides) }); expect(r.status()).toBe(status); return r; }
 await act('REGISTER'); await act('QC_PASS'); let previous: string | null = null;
 for (const path of ['DIRECT_SMEAR', 'LIQUID_BASED', 'CELL_BLOCK'] as const) {
  await act('PREPARE', { path, transferred: 1, repeatOf: previous }); const d = await read(), p = d.preparations[0]; expect(p.id).not.toBe(previous); expect(p.repeatOf).toBe(previous);
  const body = await command({ preparationId: p.id, preparationVersion: 0, consumed: 1, slides: 1 }); const key = headers(); expect((await page.request.post(url + '/COMPLETE', { headers: key, data: body })).status()).toBe(200); const replay = await page.request.post(url + '/COMPLETE', { headers: key, data: body }); expect(replay.status()).toBe(200); expect(replay.headers()['idempotency-replayed']).toBe('true');
  const materials = (await read()).materials.filter(m => m.cytologyPreparationId === p.id); expect(materials).toHaveLength(path === 'CELL_BLOCK' ? 2 : 1); const slide = materials.find(m => m.kind === 'SLIDE'); expect(slide?.patientId).toBe(encounters[0].patientId); if (path === 'CELL_BLOCK') expect(slide?.blockId).toBe(materials.find(m => m.kind === 'BLOCK')?.id); else expect(slide?.blockId).toBeNull(); previous = p.id;
 }
 const shared = await command({ path: 'DIRECT_SMEAR', transferred: 1, repeatOf: previous }); const race = await Promise.all([page.request.post(url + '/PREPARE', { headers: headers(), data: shared }), page.request.post(url + '/PREPARE', { headers: headers(), data: shared })]); expect(race.map(r => r.status()).sort()).toEqual([200, 409]); const p = (await read()).preparations[0]; await act('QC_FAIL'); await act('QC_PASS'); await act('COMPLETE', { preparationId: p.id, preparationVersion: 0, consumed: 1, slides: 1 }, 409); await act('FAIL', { preparationId: p.id, preparationVersion: 0, returned: 1 }); expect((await read()).specimen?.remaining).toBe(2);
 expect((await page.request.get('/api/materials/requests/' + rid + '/cytology/' + crypto.randomUUID())).status()).toBe(404);
 await page.getByRole('button', { name: '申请登记工作区' }).click(); await page.getByLabel('授权工作范围').click(); await page.getByText('合成申请工作范围', { exact: true }).last().click(); const r = await (await page.request.get('/api/requests/' + rid)).json() as { requestNumber: string }; await page.getByRole('button', { name: '查看 ' + r.requestNumber, exact: true }).click(); await page.getByRole('button', { name: '处理此申请细胞学', exact: true }).click(); await page.getByRole('button', { name: new RegExp(cid) }).click(); await expect(page.getByLabel('细胞学身份')).toContainText(cid); await expect(page.getByText('来源失效，禁止后续消费').first()).toBeVisible();
});
