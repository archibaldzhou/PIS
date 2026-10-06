import { test, expect, type Page } from '@playwright/test';
import { signInWorkflow, handoffUsername } from './workflow-login';
import { nextReview, settledReview, refreshDirtyReview, reviewAction } from './review-actions';

test('local persistent delivery requires receiver evidence before ACK and independent reconciliation', async ({ page, browser }) => {
  await signInWorkflow(page);
  const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
  const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scopes[0].id, number: 'SYN-DELIVERY-001' } })).json() as { id: string; patientId: string }[];
  const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
  const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
  const create = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scopes[0].id, encounterId: encounters[0].id, draft: { clinicalHistory: 'Synthetic diagnosis routing', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic site', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic', fixedAt: '2026-01-01T08:10:00Z' }] } } });
  expect(create.status()).toBe(201); const rid = (await create.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  expect((await page.request.post('/api/requests/' + rid + '/submit', { headers: headers(), data: { expectedVersion: 0 } })).status()).toBe(200);
  const detail = await (await page.request.get('/api/requests/' + rid)).json() as { containers: { id: string }[] };
  const cid = detail.containers[0].id;
  expect((await page.request.post('/api/receptions/' + rid + '/receive', { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-DELIVERY-001', containerIds: [cid] } })).status()).toBe(200);
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
  await page.getByRole('button', { name: '固定PDF与打印记录', exact: true }).click();
  const outputQueue = await (await page.request.get('/api/requests/diagnosis/scopes/' + scopes[0].id + '?pageSize=50')).json() as { items: { caseId: string }[] }; const outputPosition = outputQueue.items.findIndex(i => i.caseId === caseId); if (outputPosition >= 10) await page.getByTitle(String(Math.floor(outputPosition / 10) + 1), { exact: true }).click();
  await page.getByRole('button', { name: '查看固定产物 ' + caseId }).click();
  await page.getByLabel('核对输出病例 UUID').fill(caseId); await page.getByLabel('产物访问或打印记录原因').fill('Synthetic fixed PDF');
  const generated = page.waitForResponse(r => new URL(r.url()).pathname === report + '/output' && r.request().method() === 'POST'); await page.getByRole('button', { name: '生成固定合成PDF' }).click(); expect((await generated).status()).toBe(200);
  await expect(page.getByText('不可变产物', { exact: true })).toBeVisible();
  const metadata = await (await page.request.get(report + '/output')).json() as { artifact: { id: string; sha256: string; byteSize: number }; activityVersion: number }; const artifact = metadata.artifact;
  const operation = { confirmedCaseId: caseId, artifactVersion: 0, sha256: artifact.sha256, expectedVersion: metadata.activityVersion, requestId: null, reason: 'Synthetic audited download' };
  const binaryHeaders = headers(); const binaryPath = report + '/output/' + artifact.id + '/bytes/DOWNLOAD';
  const download = await page.request.post(binaryPath, { headers: binaryHeaders, data: operation }); expect(download.status()).toBe(200); const pdf = await download.body(); expect(pdf.subarray(0, 8).toString()).toBe('%PDF-1.4'); expect(pdf.length).toBe(artifact.byteSize); expect(download.headers()['cache-control']).toBe('no-store'); expect(download.headers()['x-artifact-sha256']).toBe(artifact.sha256);
  const replay = await page.request.post(binaryPath, { headers: binaryHeaders, data: operation }); expect(replay.status()).toBe(200); expect(replay.headers()['idempotency-replayed']).toBe('true'); expect(await replay.body()).toEqual(pdf);
  expect((await page.request.get(binaryPath)).status()).not.toBe(200);
  const current = await (await page.request.get(report + '/output')).json() as { activityVersion: number };
  const print = await page.request.post(report + '/output/' + artifact.id + '/events/PRINT_REQUEST', { headers: headers(), data: { ...operation, expectedVersion: current.activityVersion, reason: 'Synthetic print request only' } }); expect(print.status()).toBe(200); const receipt = (await print.json() as { receipt: { resourceId: string; version: number } }).receipt;
  expect((await page.request.post(report + '/output/' + artifact.id + '/events/USER_REPORTED_CANCELLED', { headers: headers(), data: { ...operation, expectedVersion: receipt.version, requestId: receipt.resourceId, reason: 'Synthetic user cancelled; no hardware feedback' } })).status()).toBe(200);
  const events = await (await page.request.get(report + '/output/' + artifact.id + '/history')).json() as { events: { kind: string }[] }; expect(events.events.map(e => e.kind)).toEqual(['USER_REPORTED_CANCELLED', 'PRINT_REQUEST', 'DOWNLOAD']);
  expect((await page.request.post(binaryPath, { headers: headers(), data: { ...operation, sha256: '0'.repeat(64) } })).status()).toBe(409);

  const queueBody = { confirmedCaseId: caseId, artifactId: artifact.id, signatureId: (await (await page.request.get(report + '/output')).json() as { signatureId: string }).signatureId, revisionId: (await (await page.request.get(report + '/output')).json() as { revisionId: string }).revisionId, sha256: artifact.sha256, destination: 'LOCAL_SIM', expectedVersion: 0, attemptId: null, reason: 'Synthetic local delivery only' };
  const queued = await page.request.post(report + '/deliveries', { headers: headers(), data: queueBody }); expect(queued.status()).toBe(200); const deliveryId = (await queued.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  async function stage(action: string, expected = 200) {
    const d = await (await page.request.get(report + '/deliveries')).json() as { caStatus: string; items: { id: string; version: number; attemptId: string | null }[] }; expect(d.caStatus).toBe('NOT_CONFIGURED'); const i = d.items.find(i => i.id === deliveryId); expect(i).toBeDefined(); if (!i) throw new Error('Missing local delivery');
    const result = await page.request.post(report + '/deliveries/' + deliveryId + '/' + action, { headers: headers(), data: { ...queueBody, expectedVersion: i.version, attemptId: action === 'CLAIM' ? null : i.attemptId } }); expect(result.status()).toBe(expected);
  }
  await stage('CLAIM'); await stage('ACK', 409); await stage('RECEIVE'); await stage('RECEIVE'); await stage('ACK');
  const acked = await (await page.request.get(report + '/deliveries')).json() as { items: { state: string }[] }; expect(acked.items[0].state).toBe('ACKED'); await stage('RECONCILE');
  await page.getByRole('button', { name: '本地投递与回执', exact: true }).click(); if (outputPosition >= 10) await page.getByTitle(String(Math.floor(outputPosition / 10) + 1), { exact: true }).click(); await page.getByRole('button', { name: '查看本地投递 ' + caseId }).click(); await expect(page.getByText('RECONCILED', { exact: true })).toBeVisible();
  const journal = await (await page.request.get(report + '/deliveries/' + deliveryId + '/history')).json() as { action: string }[]; expect(journal.map(e => e.action)).toEqual(['RECONCILE', 'ACK', 'RECEIVE', 'RECEIVE', 'CLAIM', 'QUEUE']);

});
