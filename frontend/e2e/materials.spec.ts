import { signInWorkflow } from './workflow-login';
import { test, expect } from '@playwright/test';

test('material identity, recut/deeper, same-entity reprint and source void survive real API refresh', async ({ page }) => {
  await signInWorkflow(page);
  await page.getByRole('button', { name: '申请登记工作区' }).click();
  const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
  const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scopes[0].id, number: 'SYN-MATERIAL-001' } })).json() as { id: string; patientId: string }[];
  const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
  const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
  const created = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scopes[0].id, encounterId: encounters[0].id,
    draft: { clinicalHistory: 'Synthetic material E2E', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic site', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic fixative', fixedAt: '2026-01-01T08:10:00Z' }] } } });
  expect(created.status()).toBe(201);
  const { receipt } = await created.json() as { receipt: { resourceId: string } };
  expect((await page.request.post(`/api/requests/${receipt.resourceId}/submit`, { headers: headers(), data: { expectedVersion: 0 } })).status()).toBe(200);
  const detail = await (await page.request.get('/api/requests/' + receipt.resourceId)).json() as { requestNumber: string; containers: { id: string }[] };
  const cid = detail.containers[0].id;
  expect((await page.request.post(`/api/receptions/${receipt.resourceId}/receive`, { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-MATERIAL-001', containerIds: [cid] } })).status()).toBe(200);
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
  await expect(page.getByText('当前工作范围：合成申请工作范围', { exact: true })).toBeVisible();
  await page.getByTestId('request-row-' + receipt.resourceId).getByRole('button', { name: '查看 ' + detail.requestNumber, exact: true }).click();
  await page.getByRole('button', { name: '处理此病例材料谱系', exact: true }).click();
  const mode = async (name: string) => { await page.getByLabel('材料操作', { exact: true }).click(); await page.getByRole('option', { name, exact: true }).click(); };
  const selectTask = async (kind: string) => {
    const t = tasks.find(t => t.kind === kind); expect(t).toBeDefined();
    await page.getByLabel('前置合成技术任务').click(); await page.getByRole('option', { name: kind + ' / ' + t?.id + ' / 盒 ' + box, exact: true }).click();
  };
  const save = async (label: string, path: string, status = 201) => {
    const pending = page.waitForResponse(r => new URL(r.url()).pathname === path && r.request().method() === 'POST');
    await page.getByRole('button', { name: label, exact: true }).click(); const response = await pending; expect(response.status()).toBe(status);
    const result = (await response.json() as { receipt: { resourceId: string; version: number } }).receipt;
    await expect(page.getByLabel('材料实体身份')).toHaveAttribute('data-material-id', result.resourceId);
    await expect(page.getByLabel('材料实体身份')).toContainText('版本 ' + result.version); return result.resourceId;
  };
  await selectTask('EMBEDDING'); await page.getByLabel('核对来源盒 UUID').fill(box); await page.getByLabel('材料登记 / 重切 / 作废原因').fill('Synthetic block');
  const block = await save('登记合成蜡块', '/api/materials/requests/' + receipt.resourceId + '/blocks');
  await selectTask('SECTIONING'); await page.getByLabel('核对当前材料 UUID').fill(block); await page.getByLabel('材料登记 / 重切 / 作废原因').fill('Synthetic first slide');
  const slide = await save('登记原始玻片', '/api/materials/' + block + '/slides');
  await selectTask('SECTIONING'); await page.getByLabel('核对当前材料 UUID').fill(slide); await page.getByLabel('材料登记 / 重切 / 作废原因').fill('Synthetic recut');
  const recut = await save('登记重切新玻片', '/api/materials/' + slide + '/recut'); expect(recut).not.toBe(slide);
  await mode('登记加深新玻片'); await selectTask('SECTIONING'); await page.getByLabel('核对当前材料 UUID').fill(recut); await page.getByLabel('材料登记 / 重切 / 作废原因').fill('Synthetic deeper');
  const deeper = await save('登记加深新玻片', '/api/materials/' + recut + '/deeper'); expect(new Set([block, slide, recut, deeper]).size).toBe(4);
  const material = await (await page.request.get('/api/materials/' + deeper)).json() as { entity: { barcode: string; blockId: string; sourceSlideId: string; operation: string } };
  expect(material.entity).toMatchObject({ blockId: block, sourceSlideId: recut, operation: 'DEEPER' });
  await mode('标签预览与同实体重打');
  await page.getByLabel('选择材料实体').click(); await page.getByRole('option', { name: deeper, exact: true }).click();
  await page.getByRole('button', { name: '创建标签任务', exact: true }).dblclick(); await page.getByRole('button', { name: '打开标签预览', exact: true }).click();
  await expect(page.getByLabel('合成材料标签预览')).toContainText(deeper); await expect(page.getByRole('img', { name: 'Code39 ' + material.entity.barcode })).toBeVisible();
  await page.getByRole('button', { name: '关闭标签预览', exact: true }).click();
  await page.getByLabel('重打 / 重试 / 取消 / 模拟失败原因').fill('Synthetic same-entity reprint'); await page.getByRole('button', { name: '同实体重打', exact: true }).click();
  await expect(page.getByText(/^来源任务：/)).toBeVisible();
  const labels = await (await page.request.get('/api/labels/materials/' + deeper)).json() as { jobs: { materialId: string; barcode: string }[] };
  expect(labels.jobs).toHaveLength(2); expect(labels.jobs.every(j => j.materialId === deeper && j.barcode === material.entity.barcode)).toBe(true);
  const entities = await (await page.request.get('/api/materials/requests/' + receipt.resourceId)).json() as { entities: { id: string; number: string; barcode: string }[] };
  expect(entities.entities).toHaveLength(4); expect(new Set(entities.entities.map(e => e.barcode)).size).toBe(4); expect(new Set(entities.entities.map(e => e.number)).size).toBe(4);
  await page.getByRole('button', { name: '查看材料 ' + block, exact: true }).click(); await mode('作废材料并保留历史');
  await page.getByLabel('核对当前材料 UUID').fill(block); await page.getByLabel('材料登记 / 重切 / 作废原因').fill('Synthetic block invalidation');
  await save('作废材料并保留历史', '/api/materials/' + block + '/void', 200);
  const after = await (await page.request.get('/api/materials/requests/' + receipt.resourceId)).json() as { entities: { id: string; state: string }[] };
  expect(after.entities).toHaveLength(4); expect(after.entities.every(e => e.state === 'VOID')).toBe(true);
  const lookup = await page.request.get('/api/materials/lookup', { params: { barcode: material.entity.barcode } }); expect(lookup.status()).toBe(200);
  expect((await lookup.json() as { entity: { id: string; state: string } }).entity).toMatchObject({ id: deeper, state: 'VOID' });
});

test('explicit direct cytology registers a slide without inventing a block or technical task', async ({ page }) => {
  await signInWorkflow(page);
  await page.getByRole('button', { name: '申请登记工作区' }).click();
  const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
  const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scopes[0].id, number: 'SYN-DIRECT-001' } })).json() as { id: string; patientId: string }[];
  const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
  const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
  const created = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scopes[0].id, encounterId: encounters[0].id,
    draft: { clinicalHistory: 'Synthetic material E2E', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic site', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic fixative', fixedAt: '2026-01-01T08:10:00Z' }] } } });
  expect(created.status()).toBe(201);
  const { receipt } = await created.json() as { receipt: { resourceId: string } };
  expect((await page.request.post(`/api/requests/${receipt.resourceId}/submit`, { headers: headers(), data: { expectedVersion: 0 } })).status()).toBe(200);
  const detail = await (await page.request.get('/api/requests/' + receipt.resourceId)).json() as { requestNumber: string; containers: { id: string }[] };
  const cid = detail.containers[0].id;
  expect((await page.request.post(`/api/receptions/${receipt.resourceId}/receive`, { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-DIRECT-001', containerIds: [cid] } })).status()).toBe(200);
  await expect(page.getByText('当前工作范围：合成申请工作范围', { exact: true })).toBeVisible();
  await page.getByTestId('request-row-' + receipt.resourceId).getByRole('button', { name: '查看 ' + detail.requestNumber, exact: true }).click();
  await page.getByRole('button', { name: '处理此病例材料谱系', exact: true }).click();
  await page.getByLabel('材料操作', { exact: true }).click(); await page.getByRole('option', { name: '登记细胞学直制玻片', exact: true }).click();
  await expect(page.getByLabel('前置合成技术任务')).toHaveCount(0);
  await page.getByLabel('核对直制容器 UUID').fill(cid); await page.getByLabel('材料登记 / 重切 / 作废原因').fill('Synthetic explicit direct route');
  const pending = page.waitForResponse(r => new URL(r.url()).pathname === '/api/materials/requests/' + receipt.resourceId + '/direct-slides' && r.request().method() === 'POST');
  await page.getByRole('button', { name: '登记细胞学直制玻片', exact: true }).click(); const saved = await pending; expect(saved.status()).toBe(201);
  const id = (await saved.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  await expect(page.getByLabel('材料实体身份')).toHaveAttribute('data-material-id', id);
  const entity = (await (await page.request.get('/api/materials/' + id)).json() as { entity: { route: string; containerId: string; blockId: string | null; cassetteId: string | null; technicalTaskId: string | null } }).entity;
  expect(entity).toMatchObject({ route: 'DIRECT_CYTOLOGY', containerId: cid, blockId: null, cassetteId: null, technicalTaskId: null });
});
