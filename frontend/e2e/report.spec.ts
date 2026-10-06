import { test, expect } from '@playwright/test';
import { signInWorkflow } from './workflow-login';

test('manual draft revisions bind template versions and reject stale or quarantined saves', async ({ page }) => {
  await signInWorkflow(page);
  const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
  const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scopes[0].id, number: 'SYN-REPORT-001' } })).json() as { id: string; patientId: string }[];
  const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
  const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
  const create = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scopes[0].id, encounterId: encounters[0].id, draft: { clinicalHistory: 'Synthetic diagnosis routing', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic site', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic', fixedAt: '2026-01-01T08:10:00Z' }] } } });
  expect(create.status()).toBe(201); const rid = (await create.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  expect((await page.request.post('/api/requests/' + rid + '/submit', { headers: headers(), data: { expectedVersion: 0 } })).status()).toBe(200);
  const detail = await (await page.request.get('/api/requests/' + rid)).json() as { containers: { id: string }[] };
  const cid = detail.containers[0].id;
  expect((await page.request.post('/api/receptions/' + rid + '/receive', { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-REPORT-001', containerIds: [cid] } })).status()).toBe(200);
  const slide = await page.request.post('/api/materials/requests/' + rid + '/direct-slides', { headers: headers(), data: { requestVersion: 2, confirmedContainerId: cid, reason: 'Synthetic direct diagnosis slide' } });
  expect(slide.status()).toBe(201); const mid = (await slide.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  const caseId = (await (await page.request.get('/api/materials/' + mid)).json() as { entity: { caseId: string } }).entity.caseId;
  const path = '/api/requests/diagnosis/cases/' + caseId;
  expect((await page.request.post(path + '/claim', { headers: headers(), data: { expectedVersion: -1, confirmedCaseId: caseId, targetUserId: null, reason: 'Synthetic not ready' } })).status()).toBe(409);
  expect((await page.request.post('/api/quality/materials/' + mid + '/assess', { headers: headers(), data: { expectedVersion: -1, materialVersion: 0, taskVersion: null, confirmedMaterialId: mid, standardVersion: 'SYN-MATERIAL-QC-1', outcome: 'PASS', reason: 'Synthetic prerequisite only' } })).status()).toBe(200);
  expect((await page.request.post(path + '/claim', { headers: headers(), data: { expectedVersion: -1, confirmedCaseId: caseId, targetUserId: null, reason: 'Synthetic report owner' } })).status()).toBe(200);
  await page.getByRole('button', { name: '申请登记工作区' }).click(); await expect(page.getByText('当前工作范围：合成申请工作范围', { exact: true })).toBeVisible();
  const queue = await (await page.request.get('/api/requests/diagnosis/scopes/' + scopes[0].id + '?pageSize=50')).json() as { items: { caseId: string }[] };
  const index = queue.items.findIndex(i => i.caseId === caseId); expect(index).toBeGreaterThanOrEqual(0);
  await page.getByRole('button', { name: '报告草稿', exact: true }).click(); if (index >= 10) await page.getByTitle(String(Math.floor(index / 10) + 1), { exact: true }).click();
  await page.getByRole('button', { name: '编辑报告草稿 ' + caseId }).click();
  const reportPath = '/api/requests/reports/cases/' + caseId;
  let firstBody: unknown;
  for (const version of [1, 2]) {
    await page.getByLabel('不可变模板版本').click(); await page.getByRole('option', { name: (version === 1 ? '合成文本草稿' : '合成结构草稿') + ' / SYN-REPORT v' + version, exact: true }).click();
    if (version === 2) await page.getByRole('button', { name: '清除并切换模板' }).click();
    await page.getByLabel('诊断草稿（人工）').fill('Synthetic manual draft ' + version);
    if (version === 2) { await page.getByLabel('合成样本计数').fill('2'); await page.getByLabel('合成字段已人工核对').click(); await page.getByRole('option', { name: '否', exact: true }).click(); }
    await page.getByLabel('核对报告病例 UUID').fill(caseId); await page.getByLabel('草稿修订原因').fill('Synthetic manual reason');
    const saved = page.waitForResponse(r => new URL(r.url()).pathname === reportPath + '/draft' && r.request().method() === 'POST'); await page.getByRole('button', { name: '保存人工草稿' }).click(); const response = await saved; expect(response.status()).toBe(200);
    if (version === 1) firstBody = response.request().postDataJSON() as unknown;
    await expect(page.getByLabel('报告草稿身份')).toContainText('草稿版本 ' + (version - 1));
  }
  expect((await page.request.post(reportPath + '/draft', { headers: headers(), data: firstBody })).status()).toBe(409);
  const history = await (await page.request.get(reportPath + '/history')).json() as { revisions: { templateVersion: number; fields: { diagnosis: string } }[] };
  expect(history.revisions.map(r => r.templateVersion)).toEqual([2, 1]); expect(history.revisions[1].fields.diagnosis).toBe('Synthetic manual draft 1');
  expect((await page.request.post('/api/quality/materials/' + mid + '/revoke', { headers: headers(), data: { expectedVersion: 0, confirmedMaterialId: mid, reason: 'Synthetic withdrawal' } })).status()).toBe(200);
  await page.getByRole('button', { name: '刷新诊断队列与资格' }).click(); await expect(page.getByText('QC或身份门禁未就绪，禁止保存草稿', { exact: true })).toBeVisible(); await expect(page.getByRole('button', { name: '保存人工草稿' })).toBeDisabled();
});
