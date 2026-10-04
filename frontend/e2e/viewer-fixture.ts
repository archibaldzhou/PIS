import { test as base, expect, type Page, type Browser, type Response } from '@playwright/test';
import { createHash } from 'node:crypto';
import { signInWorkflow, handoffUsername } from './workflow-login';
import type { Version, View } from '../src/features/storage/api';
import type { Job } from '../src/features/scan/api';
async function prepareViewer(page: Page) {
  await signInWorkflow(page);
  const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
  expect(scopes).toHaveLength(1); const scope = scopes[0].id;
  const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scope, number: 'SYN-SCAN-001' } })).json() as { id: string; patientId: string }[];
  const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
  const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
  const created = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scope, encounterId: encounters[0].id, draft: { clinicalHistory: 'Synthetic storage source', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic site', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic', fixedAt: '2026-01-01T08:10:00Z' }] } } });
  expect(created.status()).toBe(201); const rid = (await created.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  const submitHeaders = headers(); const submitPath = `/api/requests/${rid}/submit`;
  expect((await page.request.post(submitPath, { headers: submitHeaders, data: { expectedVersion: 0 } })).status()).toBe(200);
  expect((await page.request.post(submitPath, { headers: submitHeaders, data: { expectedVersion: 0 } })).headers()['idempotency-replayed']).toBe('true');
  const detail = await (await page.request.get(`/api/requests/${rid}`)).json() as { requestNumber: string; containers: { id: string }[] };
  expect((await page.request.post(`/api/receptions/${rid}/receive`, { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-SCAN-001', containerIds: detail.containers.map(c => c.id) } })).status()).toBe(200);

  const path = `/api/requests/${rid}/storage`;
  const listing = await page.request.get(path); expect(listing.status()).toBe(200); const view = await listing.json() as View;
  const direct = await page.request.post(`/api/materials/requests/${rid}/direct-slides`, { headers: headers(), data: { requestVersion: 2, confirmedContainerId: detail.containers[0].id, reason: 'Synthetic scan source' } }); expect(direct.status()).toBe(201);
  const slide = (await direct.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  expect((await page.request.post(`/api/quality/materials/${slide}/assess`, { headers: headers(), data: { expectedVersion: -1, materialVersion: 0, taskVersion: null, confirmedMaterialId: slide, standardVersion: 'SYN-MATERIAL-QC-1', outcome: 'PASS', reason: 'Synthetic source QC' } })).status()).toBe(200);
  const material = await (await page.request.get(`/api/materials/${slide}`)).json() as { entity: { barcode: string } };
  const headerSize = Buffer.byteLength('PIS-SYNTHETIC-STORAGE-V1\n') + 98; const data = Buffer.alloc(headerSize + 512 * 384 * 3); let offset = data.write('PIS-SYNTHETIC-STORAGE-V1\nPISRGB1\n');
  for (const value of [encounters[0].patientId, view.caseId, slide]) { Buffer.from(value.replaceAll('-', ''), 'hex').copy(data, offset); offset += 16; }
  offset += data.write(material.entity.barcode, offset, 'ascii'); data.writeInt32BE(512, offset); data.writeInt32BE(384, offset + 4);
  for (let y = 0; y < 384; y++) for (let x = 0; x < 512; x++) { const i = headerSize + (y * 512 + x) * 3; const dot = (x % 64 - 32) ** 2 + (y % 64 - 32) ** 2 < 400; data[i] = dot ? 160 : 238; data[i + 1] = dot ? 72 : 210; data[i + 2] = dot ? 157 : 232; }
  const sha256 = createHash('sha256').update(data).digest('hex');
  const reserveHeaders = headers(); const body = { assetId: null, expectedHead: -1, confirmedCaseId: view.caseId, byteSize: data.length, sha256, purpose: 'SYNTHETIC_ORIGINAL', mediaType: 'application/octet-stream' };
  const createdVersion = await page.request.post(path, { headers: reserveHeaders, data: body }); expect(createdVersion.status()).toBe(201);
  const id = (await createdVersion.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  expect((await page.request.post(path, { headers: reserveHeaders, data: body })).headers()['idempotency-replayed']).toBe('true');
  expect((await page.request.get(`${path}/${id}/bytes`, { headers: { 'X-Storage-Purpose': 'DOWNLOAD', Range: 'bytes=0-127' } })).status()).toBe(409);
  const staged = await page.request.put(`${path}/${id}/bytes`, { headers: { 'X-CSRF-TOKEN': csrf.token, 'X-Storage-Version': '0', 'Content-Type': 'application/octet-stream' }, data }); expect(staged.status()).toBe(200);
  const version = await staged.json() as Version; expect(version.state).toBe('STAGED'); const finishHeaders = headers(); const finish = { confirmedCaseId: view.caseId, expectedVersion: version.version };
  const ready = await page.request.post(`${path}/${id}/finish`, { headers: finishHeaders, data: finish }); expect(ready.status()).toBe(200); const readyVersion = await ready.json() as Version; expect(readyVersion.state).toBe('READY');
  expect(await (await page.request.post(`${path}/${id}/finish`, { headers: finishHeaders, data: finish })).json()).toEqual(readyVersion);
  const scanPath = `/api/requests/${rid}/scans`;
  const scanInput = { confirmedCaseId: view.caseId, patientId: encounters[0].patientId, slideId: slide, objectId: id, expectedHead: -1, previousId: null, barcode: material.entity.barcode, sourceCode: 'SYN-LOCAL', scannerCode: 'SYN-DEVICE', reason: 'Synthetic E2E import' };
  const batchKey = headers(); const imported = await page.request.post(scanPath, { headers: batchKey, data: { items: [scanInput] } }); expect(imported.status()).toBe(200);
  const results = await imported.json() as { status: number; scanId: string }[]; expect(results[0].status).toBe(201); const scanId = results[0].scanId;
  expect(await (await page.request.post(scanPath, { headers: batchKey, data: { items: [scanInput] } })).json()).toEqual(results);
  expect((await page.request.post(`${scanPath}/${scanId}/CLAIM`, { headers: headers(), data: { expectedVersion: 0, leaseId: null, reason: 'Synthetic claim' } })).status()).toBe(200);
  const running = await (await page.request.get(`${scanPath}/${scanId}`)).json() as Job; expect(running.attempts).toBe(1);
  const completion = { expectedVersion: running.version, leaseId: running.leaseId, reason: 'Synthetic local header parse' }; const completedKey = headers();
  expect((await page.request.post(`${scanPath}/${scanId}/PROCESS`, { headers: completedKey, data: completion })).status()).toBe(200);
  expect((await (await page.request.post(`${scanPath}/${scanId}/PROCESS`, { headers: completedKey, data: completion })).json() as { replayed: boolean }).replayed).toBe(true);
  const completed = await (await page.request.get(`${scanPath}/${scanId}`)).json() as Job; expect(completed.state).toBe('PENDING_DIGITAL_QC'); expect(completed.objectHash).toBe(sha256); expect(completed.errorCode).toBe('DIGITAL_QC_REQUIRED');
  const qcPath = `${scanPath}/${scanId}/digital-qc`;
  expect((await page.request.get(`${qcPath}/bytes?publicationVersion=0`)).status()).toBe(409);
  const evaluation = { expectedVersion: -1, scanVersion: completed.version, objectId: id, slideId: slide, objectHash: sha256, checklist: 'SYN-DIGITAL-QC-1', coverage: 'PASS', focus: 'UNKNOWN', missing: 'PASS', coveragePercent: 100, missingTiles: 0, regions: [], note: 'Synthetic unknown focus' };
  expect((await page.request.post(qcPath, { headers: headers(), data: evaluation })).status()).toBe(200);
  expect((await page.request.post(`${qcPath}/PUBLISH`, { headers: headers(), data: { expectedVersion: 0, assessmentVersion: 0, reason: 'Synthetic blocked publish' } })).status()).toBe(409);
  const revised = { ...evaluation, expectedVersion: 0, focus: 'PASS', note: 'Synthetic explicit pass' }; const qcKey = headers();
  expect((await page.request.post(qcPath, { headers: qcKey, data: revised })).status()).toBe(200);
  expect((await (await page.request.post(qcPath, { headers: qcKey, data: revised })).json() as { replayed: boolean }).replayed).toBe(true);
  await page.getByRole('button', { name: '申请登记工作区' }).click(); await page.getByLabel('授权工作范围').click(); await page.getByText('合成申请工作范围', { exact: true }).last().click();
  await page.getByRole('button', { name: `查看 ${detail.requestNumber}`, exact: true }).click(); await page.getByRole('button', { name: '处理此申请扫描导入' }).click(); await page.getByRole('button', { name: '数字QC 0', exact: true }).click();
  await expect(page.getByText('EVALUATED_NOT_PUBLISHED / 当前依赖有效', { exact: true })).toBeVisible();
  await page.getByLabel('QC人工备注或原因', { exact: true }).fill('Synthetic explicit publication'); await page.getByRole('button', { name: '发布合成契约版本' }).click();
  await expect(page.getByText('PUBLISHED_SYNTHETIC_CONTRACT / 当前依赖有效', { exact: true })).toBeVisible();
  const download = await page.request.get(`${qcPath}/bytes?publicationVersion=2`, { headers: { Range: 'bytes=0-31' } }); expect(download.status()).toBe(206); expect(download.headers()['x-pis-capability']).toBe('SYNTHETIC_CONTRACT_ONLY_NO_VIEWER'); expect(await download.body()).toEqual(data.subarray(0, 32));
  const viewerPath = `${scanPath}/${scanId}/viewer`;
  await page.getByRole('button', { name: '打开合成瓦片阅片器' }).click();
  await page.getByRole('button', { name: '生成或原键确认合成瓦片' }).click();
  const canvas = page.getByLabel('合成瓦片画布', { exact: true }).locator('canvas').first();
  await expect.poll(async () => canvas.evaluate(node => { const c = node as HTMLCanvasElement; const ctx = c.getContext('2d'); if (!ctx) return 0; const p = ctx.getImageData(0, 0, c.width, c.height).data; let pink = 0; for (let i = 0; i < p.length; i += 16) if (p[i] > 140 && p[i + 2] > 120 && p[i] > p[i + 1] + 15) pink++; return pink; })).toBeGreaterThan(500);
  return {scope, rid, headers, scanPath, scanId, qcPath, viewerPath, canvas};
}
export const test=base.extend<{source: Awaited<ReturnType<typeof prepareViewer>>; transportEvidence: void}>({
  transportEvidence: [async ({page},use,info)=>{
    const started=Date.now(),events:{elapsedMs:number;resource:string;status:number}[]=[];
    const record=(response:Response)=>{const path=new URL(response.url()).pathname;
      const resource=path.includes('/synthetic-results/')?(path.includes('/tiles/')?'result-png':'result-metadata'):path.includes('/viewer')?(path.includes('/tiles/')?'viewer-tile':'viewer-manifest'):undefined;
      if(resource){events.push({elapsedMs:Date.now()-started,resource,status:response.status()});if(events.length>40)events.shift();}
    };
    page.on('response',record);
    await use();page.off('response',record);
    await info.attach('bounded synthetic viewer transport statuses',{body:JSON.stringify(events),contentType:'application/json'});
  },{auto:true}],
  source: [async ({page}, use) => { await use(await prepareViewer(page)); }, {timeout:30_000}],
});
export async function forbidden(browser: Browser, resources: string[]) {
  const other = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  try {
    const receiver = await other.newPage(); await signInReceiver(receiver);
    for (const resource of resources) expect((await receiver.request.get(resource)).status()).toBe(404);
  } finally { await other.close(); }
}
async function signInReceiver(receiver: Page) {
  await receiver.goto('/'); await receiver.getByLabel('用户名', {exact:true}).fill(handoffUsername());
  await receiver.getByLabel('密码', {exact:true}).fill(process.env.PIS_E2E_HANDOFF_PASSWORD ?? 'Synthetic-handoff-only-42!');
  const accepted=receiver.waitForResponse(r=>new URL(r.url()).pathname==='/api/auth/login');
  await receiver.getByRole('button',{name:'登录',exact:true}).click(); expect((await accepted).status()).toBe(204);
}
