import { signInWorkflow } from './workflow-login';
import { test, expect } from '@playwright/test';

test('QC fail isolates labels, creates linked rework and requires independent new-slide QC', async ({ page }) => {
  await signInWorkflow(page);
  await page.getByRole('button', { name: '申请登记工作区' }).click();
  const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
  const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scopes[0].id, number: 'SYN-QC-001' } })).json() as { id: string; patientId: string }[];
  const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
  const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
  const created = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scopes[0].id, encounterId: encounters[0].id,
    draft: { clinicalHistory: 'Synthetic material E2E', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic site', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic fixative', fixedAt: '2026-01-01T08:10:00Z' }] } } });
  expect(created.status()).toBe(201);
  const { receipt } = await created.json() as { receipt: { resourceId: string } };
  expect((await page.request.post(`/api/requests/${receipt.resourceId}/submit`, { headers: headers(), data: { expectedVersion: 0 } })).status()).toBe(200);
  const detail = await (await page.request.get('/api/requests/' + receipt.resourceId)).json() as { requestNumber: string; containers: { id: string }[] };
  const cid = detail.containers[0].id;
  expect((await page.request.post(`/api/receptions/${receipt.resourceId}/receive`, { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-QC-001', containerIds: [cid] } })).status()).toBe(200);
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
    expect((await page.request.post('/api/technical/tasks/' + id + '/claim', { headers: headers(), data: { expectedVersion: 0, confirmedCassetteId: box, reason: 'Synthetic claim' } })).status()).toBe(200);
    expect((await page.request.post('/api/technical/tasks/' + id + '/finish-simulation', { headers: headers(), data: { expectedVersion: 1, confirmedCassetteId: box, reason: 'Synthetic manual finish only' } })).status()).toBe(200);
    tasks.push({ id, kind });
  }

  const post = async (path: string, data: Record<string, unknown>, status: number) => {
    const r = await page.request.post(path, { headers: headers(), data }); expect(r.status()).toBe(status);
    return status < 300 ? (await r.json() as { receipt: { resourceId: string } }).receipt.resourceId : '';
  };
  const block = await post('/api/materials/requests/' + receipt.resourceId + '/blocks', { requestVersion: 2, taskId: tasks[0].id, taskVersion: 2, confirmedCassetteId: box, reason: 'Synthetic block' }, 201);
  const slide = await post('/api/materials/' + block + '/slides', { blockVersion: 0, taskId: tasks[1].id, taskVersion: 2, confirmedBlockId: block, reason: 'Synthetic original' }, 201);
  await page.getByLabel('授权工作范围').click(); await page.getByText('合成申请工作范围', { exact: true }).last().click();
  await page.getByTestId('request-row-' + receipt.resourceId).getByRole('button', { name: '查看 ' + detail.requestNumber, exact: true }).click();
  await page.getByRole('button', { name: '处理此病例技术QC', exact: true }).click();
  await page.getByRole('button', { name: '质检材料 ' + slide, exact: true }).click();
  const assess = async (id: string, outcome: string, expectedQc: number) => {
    await page.getByLabel('明确质检结论').click(); await page.getByRole('option', { name: outcome, exact: true }).click();
    await page.getByLabel('核对质检材料 UUID').fill(id); await page.getByLabel('QC事实与处置原因').fill('Synthetic QC evidence');
    const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/quality/materials/' + id + '/assess' && r.request().method() === 'POST');
    await page.getByRole('button', { name: '提交明确QC操作' }).click(); expect((await response).status()).toBe(200);
    await expect(page.getByLabel('质检材料身份')).toHaveAttribute('data-material-id', id); await expect(page.getByLabel('质检材料身份')).toContainText('QC版本 ' + expectedQc);
  };
  await assess(slide, 'FAIL · 不合格隔离', 0);
  await expect(page.getByLabel('质检材料身份')).toContainText('FAIL');
  await post('/api/labels/materials/' + slide + '/jobs', { requestVersion: 2, materialVersion: 0 }, 409);
  await post('/api/quality/materials/' + slide + '/exception-release', { expectedVersion: 0, confirmedMaterialId: slide, reason: 'Synthetic prohibited release' }, 409);
  await expect(page.getByRole('button', { name: '异常放行（未批准）' })).toBeDisabled();
  await page.getByLabel('质检操作', { exact: true }).click(); await page.getByRole('option', { name: '隔离并创建返工任务', exact: true }).click();
  await page.getByLabel('核对质检材料 UUID').fill(slide); await page.getByLabel('QC事实与处置原因').fill('Synthetic linked rework');
  const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/quality/materials/' + slide + '/rework' && r.request().method() === 'POST');
  await page.getByRole('button', { name: '提交明确QC操作' }).click(); expect((await response).status()).toBe(200);
  await expect(page.getByLabel('质检材料身份')).toContainText('REWORK_REQUIRED');
  const old = await (await page.request.get('/api/quality/materials/' + slide)).json() as { item: { head: { repairTaskId: string } } };
  const repair = old.item.head.repairTaskId; expect(repair).not.toBe(tasks[1].id);
  for (const [action, version] of [['claim', 0], ['finish-simulation', 1]] as const) await post('/api/technical/tasks/' + repair + '/' + action, { expectedVersion: version, confirmedCassetteId: box, reason: 'Synthetic recovery' }, 200);
  const replacement = await post('/api/materials/' + slide + '/recut', { sourceSlideVersion: 0, taskId: repair, taskVersion: 2, confirmedSourceSlideId: slide, reason: 'Synthetic replacement' }, 201);
  expect(replacement).not.toBe(slide);
  await page.getByRole('button', { name: '刷新质检与隔离状态' }).click(); await page.getByRole('button', { name: '质检材料 ' + replacement, exact: true }).click();
  await expect(page.getByLabel('质检材料身份')).toContainText('NOT_ASSESSED');
  await assess(replacement, 'PENDING · 待定隔离', 0); await post('/api/labels/materials/' + replacement + '/jobs', { requestVersion: 2, materialVersion: 0 }, 409);
  await assess(replacement, 'PASS · 合成检查通过', 1);
  const label = await post('/api/labels/materials/' + replacement + '/jobs', { requestVersion: 2, materialVersion: 0 }, 201);
  expect((await page.request.get('/api/labels/jobs/' + label)).status()).toBe(200);
  await page.getByLabel('质检操作', { exact: true }).click(); await page.getByRole('option', { name: '撤销当前QC', exact: true }).click();
  await page.getByLabel('核对质检材料 UUID').fill(replacement); await page.getByLabel('QC事实与处置原因').fill('Synthetic QC withdrawn');
  const revoked = page.waitForResponse(r => new URL(r.url()).pathname === '/api/quality/materials/' + replacement + '/revoke' && r.request().method() === 'POST');
  await page.getByRole('button', { name: '提交明确QC操作' }).click(); expect((await revoked).status()).toBe(200);
  await expect(page.getByLabel('质检材料身份')).toContainText('REVOKED'); expect((await page.request.get('/api/labels/jobs/' + label)).status()).toBe(409);
  await expect(page.getByRole('cell', { name: 'REVOKE', exact: true })).toHaveCount(1);
});
