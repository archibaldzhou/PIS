import { signInWorkflow, handoffUsername } from './workflow-login';
import { test, expect, type Page } from '@playwright/test';

test('two synthetic users confirm handoff and keep aborted/rework task history', async ({ page, browser }) => {
  await signInWorkflow(page);
  await page.getByRole('button', { name: '申请登记工作区' }).click();
  const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
  const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scopes[0].id, number: 'SYN-TECH-001' } })).json() as { id: string; patientId: string }[];
  const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
  const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
  const created = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scopes[0].id, encounterId: encounters[0].id,
    draft: { clinicalHistory: 'Synthetic technical E2E', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic site', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic fixative', fixedAt: '2026-01-01T08:10:00Z' }] } } });
  expect(created.status()).toBe(201);
  const { receipt } = await created.json() as { receipt: { resourceId: string } };
  expect((await page.request.post(`/api/requests/${receipt.resourceId}/submit`, { headers: headers(), data: { expectedVersion: 0 } })).status()).toBe(200);
  const detail = await (await page.request.get('/api/requests/' + receipt.resourceId)).json() as { requestNumber: string; containers: { id: string }[] };
  const cid = detail.containers[0].id;
  expect((await page.request.post(`/api/receptions/${receipt.resourceId}/receive`, { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-TECH-001', containerIds: [cid] } })).status()).toBe(200);
  const grossCreated = await page.request.post('/api/grossing/requests/' + receipt.resourceId, { headers: headers(), data: { requestVersion: 2, description: 'Synthetic technical source' } });
  expect(grossCreated.status()).toBe(201);
  const grossId = (await grossCreated.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  expect((await page.request.post('/api/grossing/records/' + grossId + '/cassettes', { headers: headers(), data: { expectedVersion: 0, containerIds: [cid], site: 'Synthetic technical box', pieces: 1 } })).status()).toBe(200);
  expect((await page.request.post('/api/grossing/records/' + grossId + '/complete', { headers: headers(), data: { expectedVersion: 1, reason: 'Synthetic source released' } })).status()).toBe(200);
  const released = await (await page.request.get('/api/technical/requests/' + receipt.resourceId)).json() as { actorId: string; source: { cassettes: { id: string; number: string }[] } };
  const box = released.source.cassettes[0];
  const enter = async (p: Page) => {
    await expect(p.getByText('当前工作范围：合成申请工作范围', { exact: true })).toBeVisible();
    await p.getByTestId('request-row-' + receipt.resourceId).getByRole('button', { name: '查看 ' + detail.requestNumber, exact: true }).click();
    await p.getByRole('button', { name: '处理此病例技术任务', exact: true }).click();
  };
  await enter(page);
  await page.getByLabel('来源已释放取材盒').click(); await page.getByRole('option', { name: box.number + ' / Synthetic technical box', exact: true }).click();
  await page.getByLabel('路线 / 操作 / 交接说明').fill('Synthetic isolated route');
  const creation = page.waitForResponse(r => new URL(r.url()).pathname === '/api/technical/requests/' + receipt.resourceId && r.request().method() === 'POST');
  await page.getByRole('button', { name: '保存技术任务', exact: true }).dblclick();
  const createdTask = await creation; expect(createdTask.status()).toBe(201);
  const taskId = (await createdTask.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  await expect(page.getByLabel('技术任务身份')).toHaveAttribute('data-task-id', taskId);
  const act = async (p: Page, id: string, label: string, endpoint: string, expectedStatus = 200) => {
    await p.getByLabel('技术任务操作', { exact: true }).click(); await p.getByRole('option', { name: label, exact: true }).click();
    await p.getByLabel('核对来源取材盒 UUID').fill(box.id);
    await p.getByLabel('路线 / 操作 / 交接说明').fill('Synthetic ' + endpoint);
    const result = p.waitForResponse(r => new URL(r.url()).pathname === '/api/technical/tasks/' + id + '/' + endpoint && r.request().method() === 'POST');
    await p.getByRole('button', { name: '确认技术任务操作', exact: true }).click();
    const response = await result; expect(response.status()).toBe(expectedStatus);
    const saved = (await response.json() as { receipt: { resourceId: string; version: number } }).receipt;
    await expect(p.getByLabel('技术任务身份')).toHaveAttribute('data-task-id', saved.resourceId);
    await expect(p.getByLabel('技术任务身份')).toContainText('版本 ' + saved.version);
    return saved.resourceId;
  };
  await act(page, taskId, '核对并领取', 'claim');
  await act(page, taskId, '发起人员交接', 'offer');
  await expect(page.getByLabel('技术任务身份')).toContainText('HANDOFF_PENDING');
  const secondContext = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const receiver = await secondContext.newPage(); await receiver.goto('/');
    await receiver.getByLabel('用户名').fill(handoffUsername());
    await receiver.getByLabel('密码', { exact: true }).fill(process.env.PIS_E2E_HANDOFF_PASSWORD ?? 'Synthetic-handoff-only-42!');
    await receiver.getByRole('button', { name: '登录', exact: true }).click(); await receiver.getByRole('button', { name: '申请登记工作区' }).click();
    await enter(receiver); await receiver.getByRole('button', { name: '查看技术任务 ' + taskId, exact: true }).click();
    await act(receiver, taskId, '核对并接收交接', 'accept');
    const ownerView = await (await receiver.request.get('/api/technical/requests/' + receipt.resourceId)).json() as { actorId: string };
    expect(ownerView.actorId).not.toBe(released.actorId);
    await act(receiver, taskId, '记录合成演练完成', 'finish-simulation');
    await expect(receiver.getByLabel('技术任务身份')).toContainText('SIMULATED_DONE');
    const original = await (await receiver.request.get('/api/technical/tasks/' + taskId)).json() as { task: { ownerId: string }; events: { action: string; previousOwnerId: string | null; nextOwnerId: string | null }[] };
    expect(original.task.ownerId).toBe(ownerView.actorId);
    expect(original.events.find(e => e.action === 'ACCEPT')).toMatchObject({ previousOwnerId: released.actorId, nextOwnerId: ownerView.actorId });
    const child = await act(receiver, taskId, '建立关联返工任务', 'rework', 201); expect(child).not.toBe(taskId);
    await act(receiver, child, '中止任务', 'abort');
    const childDetail = await (await receiver.request.get('/api/technical/tasks/' + child)).json() as { task: { reworkOf: string; cassetteId: string; state: string; kind: string } };
    expect(childDetail.task).toMatchObject({ reworkOf: taskId, cassetteId: box.id, state: 'ABORTED', kind: 'PROCESSING' });
    const list = await (await receiver.request.get('/api/technical/requests/' + receipt.resourceId)).json() as { tasks: { id: string; state: string }[] };
    expect(list.tasks).toHaveLength(2); expect(list.tasks.find(t => t.id === taskId)?.state).toBe('SIMULATED_DONE');
  } finally { await secondContext.close(); }
});
