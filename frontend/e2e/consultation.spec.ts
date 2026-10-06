import { test, expect, type Page } from '@playwright/test';
import { signInWorkflow, handoffUsername } from './workflow-login';
import type { View, Command, Action } from '../src/features/report/consultationApi';

// Real local Spring/PG and separate synthetic actors; no HTTP mocks or invitations sent.
test('case-scoped consultation records disagreement, explicit confirmation and atomic non-signing adoption', async ({ page, browser }) => {
  await signInWorkflow(page);
  const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
  const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scopes[0].id, number: 'SYN-CONSULT-001' } })).json() as { id: string; patientId: string }[];
  const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
  const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
  const create = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scopes[0].id, encounterId: encounters[0].id, draft: { clinicalHistory: 'Synthetic diagnosis routing', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic site', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic', fixedAt: '2026-01-01T08:10:00Z' }] } } });
  expect(create.status()).toBe(201); const rid = (await create.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  expect((await page.request.post('/api/requests/' + rid + '/submit', { headers: headers(), data: { expectedVersion: 0 } })).status()).toBe(200);
  const detail = await (await page.request.get('/api/requests/' + rid)).json() as { containers: { id: string }[] };
  const cid = detail.containers[0].id;
  expect((await page.request.post('/api/receptions/' + rid + '/receive', { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-CONSULT-001', containerIds: [cid] } })).status()).toBe(200);
  const slide = await page.request.post('/api/materials/requests/' + rid + '/direct-slides', { headers: headers(), data: { requestVersion: 2, confirmedContainerId: cid, reason: 'Synthetic direct diagnosis slide' } });
  expect(slide.status()).toBe(201); const mid = (await slide.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  const caseId = (await (await page.request.get('/api/materials/' + mid)).json() as { entity: { caseId: string } }).entity.caseId;
  const path = '/api/requests/diagnosis/cases/' + caseId;
  expect((await page.request.post(path + '/claim', { headers: headers(), data: { expectedVersion: -1, confirmedCaseId: caseId, targetUserId: null, reason: 'Synthetic not ready' } })).status()).toBe(409);
  expect((await page.request.post('/api/quality/materials/' + mid + '/assess', { headers: headers(), data: { expectedVersion: -1, materialVersion: 0, taskVersion: null, confirmedMaterialId: mid, standardVersion: 'SYN-MATERIAL-QC-1', outcome: 'PASS', reason: 'Synthetic prerequisite only' } })).status()).toBe(200);
  expect((await page.request.post(path + '/claim', { headers: headers(), data: { expectedVersion: -1, confirmedCaseId: caseId, targetUserId: null, reason: 'Synthetic report owner' } })).status()).toBe(200);

  const report = '/api/requests/reports/cases/' + caseId;
  const draftBody = { expectedVersion: -1, assignmentVersion: 0, confirmedCaseId: caseId, templateCode: 'SYN-REPORT', templateVersion: 1, fields: { gross: '', microscopy: '', diagnosis: 'Synthetic manual review text', notes: '' }, reason: 'Synthetic author input' };
  expect((await page.request.post(report + '/draft', { headers: headers(), data: draftBody })).status()).toBe(200);
  const other = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const reviewer = await other.newPage(); await reviewer.goto('/'); await reviewer.getByLabel('用户名', { exact: true }).fill(handoffUsername()); await reviewer.getByLabel('密码', { exact: true }).fill(process.env.PIS_E2E_HANDOFF_PASSWORD ?? 'Synthetic-handoff-only-42!'); const login = reviewer.waitForResponse(r => new URL(r.url()).pathname === '/api/auth/login'); await reviewer.getByRole('button', { name: '登录', exact: true }).click(); expect((await login).status()).toBe(204);
    const user = await (await reviewer.request.get('/api/auth/me')).json() as { id: string };
    const url = '/api/requests/consultations/cases/' + caseId;
    expect((await reviewer.request.get(url)).status()).toBe(404);
    async function read(p: Page, id: string | null) { const r = await p.request.get(url, { params: id ? { consultation: id } : {} }); expect(r.status()).toBe(200); return await r.json() as Omit<View, 'selected'> & { selected: (Omit<NonNullable<View['selected']>, 'materialBasis'> & { materialBasis: string }) | null }; }
    async function body(p: Page, id: string | null, overrides: Partial<Command['body']> = {}): Promise<Command['body']> { const d = await read(p, id); return { confirmedCaseId: caseId, expectedVersion: d.selected?.version ?? -1, reason: 'Synthetic explicit human reason', revisionId: d.basis?.id ?? null, assignmentVersion: d.basis?.assignmentVersion ?? null, kind: null, purpose: null, expiresAt: null, invitees: [], targetId: null, disposition: null, content: '', opinionIds: d.members.flatMap(m => m.opinionId ? [m.opinionId] : []), summaryId: d.selected?.summaryId ?? null, ...overrides }; }
    async function act(p: Page, id: string | null, action: Action, overrides: Partial<Command['body']> = {}, status = 200) { const csrf = await (await p.request.get('/api/auth/csrf')).json() as { token: string }; const r = await p.request.post(url + '/' + action, { params: id ? { consultation: id } : {}, headers: { 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() }, data: await body(p, id, overrides) }); expect(r.status()).toBe(status); return r; }
    const created = await act(page, null, 'CREATE', { kind: 'REREAD', purpose: 'Synthetic reread only', expiresAt: new Date(Date.now() + 3600000).toISOString(), invitees: [user.id] }); const id = (await created.json() as { receipt: { resourceId: string } }).receipt.resourceId;
    await act(reviewer, id, 'ACCEPT'); await act(reviewer, id, 'OPINION', { disposition: 'DISAGREE', content: 'Synthetic manual disagreement' });
    await act(page, id, 'SUMMARY', { disposition: 'UNRESOLVED', content: 'Synthetic unresolved issue' }); await act(page, id, 'ADOPT', { content: 'Synthetic blocked note' }, 409); await act(reviewer, id, 'CONFIRM', {}, 409);
    await act(page, id, 'SUMMARY', { disposition: 'RESOLVED', content: 'Synthetic explicit explanation for reconciliation' }); expect((await read(page, id)).ready).toBe(false); await act(reviewer, id, 'CONFIRM'); expect((await read(page, id)).ready).toBe(true);
    const adopted = await body(page, id, { content: 'Synthetic explicitly adopted note' }), key = headers(); const endpoint = url + '/ADOPT?consultation=' + id;
    expect((await page.request.post(endpoint, { headers: key, data: adopted })).status()).toBe(200); const replay = await page.request.post(endpoint, { headers: key, data: adopted }); expect(replay.status()).toBe(200); expect(replay.headers()['idempotency-replayed']).toBe('true');
    const history = await (await page.request.get(report + '/history')).json() as { revisions: { version: number; fields: { diagnosis: string; notes: string } }[] }; expect(history.revisions).toHaveLength(2); expect(history.revisions[0].fields.diagnosis).toBe(history.revisions[1].fields.diagnosis); expect(history.revisions[0].fields.notes).toContain('Synthetic explicitly adopted note'); expect((await reviewer.request.get(url, { params: { consultation: id } })).status()).toBe(404);
    await page.getByRole('button', { name: '申请登记工作区' }).click(); await expect(page.getByText('当前工作范围：合成申请工作范围', { exact: true })).toBeVisible(); await page.getByRole('button', { name: '院内会诊与复阅', exact: true }).click(); const queue = await (await page.request.get('/api/requests/diagnosis/scopes/' + scopes[0].id + '?pageSize=50')).json() as { items: { caseId: string }[] }; const index = queue.items.findIndex(i => i.caseId === caseId); expect(index).toBeGreaterThanOrEqual(0); if (index >= 10) await page.getByTitle(String(Math.floor(index / 10) + 1), { exact: true }).click(); await page.getByRole('button', { name: '查看院内会诊 ' + caseId }).click(); await page.getByRole('button', { name: '查看会诊 ' + id }).click(); await expect(page.getByText('已追加为新草稿修订（未签署）', { exact: true })).toBeVisible();
  } finally { await other.close(); }
});
