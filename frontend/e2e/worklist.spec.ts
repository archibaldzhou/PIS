import { signInWorkflow } from './workflow-login';
import { test, expect } from '@playwright/test';

test('worklist claims selected tasks with partial failure and replays original batch', async ({ page }) => {
  await signInWorkflow(page);
  await page.getByRole('button', { name: '申请登记工作区' }).click();
  const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
  const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scopes[0].id, number: 'SYN-WORKLIST-001' } })).json() as { id: string; patientId: string }[];
  const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
  const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
  const created = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scopes[0].id, encounterId: encounters[0].id,
    draft: { clinicalHistory: 'Synthetic material E2E', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic site', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic fixative', fixedAt: '2026-01-01T08:10:00Z' }] } } });
  expect(created.status()).toBe(201);
  const { receipt } = await created.json() as { receipt: { resourceId: string } };
  expect((await page.request.post(`/api/requests/${receipt.resourceId}/submit`, { headers: headers(), data: { expectedVersion: 0 } })).status()).toBe(200);
  const detail = await (await page.request.get('/api/requests/' + receipt.resourceId)).json() as { requestNumber: string; containers: { id: string }[] };
  const cid = detail.containers[0].id;
  expect((await page.request.post(`/api/receptions/${receipt.resourceId}/receive`, { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-WORKLIST-001', containerIds: [cid] } })).status()).toBe(200);
  const grossCreated = await page.request.post('/api/grossing/requests/' + receipt.resourceId, { headers: headers(), data: { requestVersion: 2, description: 'Synthetic technical source' } });
  expect(grossCreated.status()).toBe(201);
  const grossId = (await grossCreated.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  expect((await page.request.post('/api/grossing/records/' + grossId + '/cassettes', { headers: headers(), data: { expectedVersion: 0, containerIds: [cid], site: 'Synthetic technical box', pieces: 1 } })).status()).toBe(200);
  expect((await page.request.post('/api/grossing/records/' + grossId + '/complete', { headers: headers(), data: { expectedVersion: 1, reason: 'Synthetic source released' } })).status()).toBe(200);
  const sources = await (await page.request.get('/api/technical/requests/' + receipt.resourceId)).json() as { source: { cassettes: { id: string }[] } };
  const box = sources.source.cassettes[0].id;
  const tasks: { id: string; kind: string }[] = [];
  for (const kind of ['EMBEDDING', 'SECTIONING']) {
    const created = await page.request.post('/api/technical/requests/' + receipt.resourceId, { headers: headers(), data: { requestVersion: 2, cassetteId: box, kind, predecessorId: null, reason: 'Synthetic material prerequisite' } });
    expect(created.status()).toBe(201); const id = (await created.json() as { receipt: { resourceId: string } }).receipt.resourceId;
    tasks.push({ id, kind });
  }

  await page.getByLabel('授权工作范围').click(); await page.getByText('合成申请工作范围', { exact: true }).last().click();
  await page.getByRole('button', { name: '工作列表与追踪', exact: true }).click();
  await page.getByLabel('工作类别', { exact: true }).click(); await page.getByRole('option', { name: 'TECHNICAL', exact: true }).click();
  await page.getByLabel('工作排序', { exact: true }).click(); await page.getByRole('option', { name: '最近创建', exact: true }).click();
  for (const task of tasks) await page.getByRole('checkbox', { name: '选择任务 ' + task.id, exact: true }).check();
  // A competing command advances one version after the page snapshot and before the batch.
  expect((await page.request.post('/api/technical/tasks/' + tasks[1].id + '/claim', { headers: headers(), data: { expectedVersion: 0, confirmedCassetteId: box, reason: 'Synthetic competing claim' } })).status()).toBe(200);
  await page.getByLabel('批量领取原因').fill('Synthetic partial batch');
  await page.getByRole('button', { name: '核对所选并批量领取', exact: true }).click();
  const modal = page.getByRole('dialog');
  for (const task of tasks) await expect(modal).toContainText(task.id);
  const path = '/api/worklists/scopes/' + scopes[0].id + '/claims';
  const pending = page.waitForResponse(r => new URL(r.url()).pathname === path && r.request().method() === 'POST');
  await modal.getByRole('button', { name: '确认逐项领取', exact: true }).click();
  const response = await pending; expect(response.status()).toBe(200);
  type Results = { items: { taskId: string; outcome: string; code: string; version: number | null; replayed: boolean }[] };
  const results = await response.json() as Results;
  expect(results.items).toHaveLength(2);
  expect(results.items.find(i => i.taskId === tasks[0].id)).toMatchObject({ outcome: 'SUCCESS', version: 1, replayed: false });
  expect(results.items.find(i => i.taskId === tasks[1].id)).toMatchObject({ outcome: 'REJECTED', code: 'VERSION_CONFLICT', version: null });
  await expect(page.getByRole('cell', { name: 'SUCCESS', exact: true })).toHaveCount(1);
  await expect(page.getByRole('cell', { name: 'REJECTED', exact: true })).toHaveCount(1);
  await expect(page.getByText('本页明确选择：0 项（最多20项）', { exact: true })).toBeVisible();
  const replay = await page.request.post(path, { headers: headers(), data: response.request().postDataJSON() as unknown });
  expect(replay.status()).toBe(200);
  const replayed = await replay.json() as Results;
  expect(replayed.items.find(i => i.taskId === tasks[0].id)).toMatchObject({ outcome: 'SUCCESS', version: 1, replayed: true });
  expect(replayed.items.find(i => i.taskId === tasks[1].id)).toMatchObject({ outcome: 'REJECTED', code: 'VERSION_CONFLICT' });
  await page.getByRole('button', { name: '追踪申请 ' + receipt.resourceId, exact: true }).first().click();
  await expect(page.getByRole('cell', { name: 'CLAIM', exact: true })).toHaveCount(2);
  const trace = await (await page.request.get('/api/worklists/requests/' + receipt.resourceId + '/trace')).json() as { events: { domain: string; action: string; entityId: string }[] };
  expect(trace.events.filter(e => e.domain === 'TECHNICAL' && e.action === 'CLAIM').map(e => e.entityId).sort()).toEqual(tasks.map(t => t.id).sort());
});
