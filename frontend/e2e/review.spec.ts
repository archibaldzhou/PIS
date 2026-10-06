import { test, expect, type Page } from '@playwright/test';
import { signInWorkflow, handoffUsername } from './workflow-login';
import { nextReview, settledReview, refreshDirtyReview, reviewAction } from './review-actions';

test('separated synthetic review and simulation bind exact revisions and freeze the original', async ({ page, browser }) => {
  await signInWorkflow(page);
  const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
  const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scopes[0].id, number: 'SYN-REVIEW-001' } })).json() as { id: string; patientId: string }[];
  const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
  const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
  const create = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scopes[0].id, encounterId: encounters[0].id, draft: { clinicalHistory: 'Synthetic diagnosis routing', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic site', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic', fixedAt: '2026-01-01T08:10:00Z' }] } } });
  expect(create.status()).toBe(201); const rid = (await create.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  expect((await page.request.post('/api/requests/' + rid + '/submit', { headers: headers(), data: { expectedVersion: 0 } })).status()).toBe(200);
  const detail = await (await page.request.get('/api/requests/' + rid)).json() as { containers: { id: string }[] };
  const cid = detail.containers[0].id;
  expect((await page.request.post('/api/receptions/' + rid + '/receive', { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-REVIEW-001', containerIds: [cid] } })).status()).toBe(200);
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
  async function open(p: Page) {
    await p.getByRole('button', { name: '申请登记工作区' }).click(); await expect(p.getByText('当前工作范围：合成申请工作范围', { exact: true })).toBeVisible();
    const queue = await (await p.request.get('/api/requests/diagnosis/scopes/' + scopes[0].id + '?pageSize=50')).json() as { items: { caseId: string }[] }; const index = queue.items.findIndex(i => i.caseId === caseId); expect(index).toBeGreaterThanOrEqual(0);
    await p.getByRole('button', { name: '复核与模拟签署', exact: true }).click(); if (index >= 10) await p.getByTitle(String(Math.floor(index / 10) + 1), { exact: true }).click(); const fresh = nextReview(p, caseId); await p.getByRole('button', { name: '复核合成报告 ' + caseId }).click(); await settledReview(p, caseId, await fresh);
  }
  const act = (p: Page, action: 'APPROVE' | 'SIMULATE_SIGN', expectedStatus = 200) => reviewAction(p, caseId, action, expectedStatus);
  await open(page); await act(page, 'APPROVE', 409); // Author separation is enforced server-side.
  const other = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const reviewer = await other.newPage(); await reviewer.goto('/'); await reviewer.getByLabel('用户名', { exact: true }).fill(handoffUsername()); await reviewer.getByLabel('密码', { exact: true }).fill(process.env.PIS_E2E_HANDOFF_PASSWORD ?? 'Synthetic-handoff-only-42!');
    const login = reviewer.waitForResponse(r => new URL(r.url()).pathname === '/api/auth/login'); await reviewer.getByRole('button', { name: '登录', exact: true }).click(); expect((await login).status()).toBe(204);
    await open(reviewer); await act(reviewer, 'APPROVE'); await expect(reviewer.getByLabel('复核版本身份')).toContainText('状态 APPROVED');
    await refreshDirtyReview(page, caseId); await expect(page.getByLabel('复核版本身份')).toContainText('状态 APPROVED');
    await act(page, 'SIMULATE_SIGN'); await expect(page.getByLabel('复核版本身份')).toContainText('状态 SIMULATED_SIGNED');
    expect((await page.request.post(report + '/draft', { headers: headers(), data: { ...draftBody, expectedVersion: 0 } })).status()).toBe(409);
    const history = await (await page.request.get(report + '/review')).json() as { events: { action: string; reviewId: string | null; id: string }[] }; expect(history.events.map(e => e.action)).toEqual(['SIMULATE_SIGN', 'APPROVE']); expect(history.events[0].reviewId).toBe(history.events[1].id);
  } finally { await other.close(); }
});
