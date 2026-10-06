import { test, expect } from '@playwright/test';
import { createHash } from 'node:crypto';
import { signInWorkflow, handoffUsername } from './workflow-login';
import type { Version, View } from '../src/features/storage/api';
test('private original stages, survives finish replay, returns exact ranges and denies ungranted receiver', async ({ page, browser }) => {
  await signInWorkflow(page);
  const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
  expect(scopes).toHaveLength(1); const scope = scopes[0].id;
  const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scope, number: 'SYN-STORAGE-001' } })).json() as { id: string; patientId: string }[];
  const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
  const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
  const created = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scope, encounterId: encounters[0].id, draft: { clinicalHistory: 'Synthetic storage source', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic site', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic', fixedAt: '2026-01-01T08:10:00Z' }] } } });
  expect(created.status()).toBe(201); const rid = (await created.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  const submitHeaders = headers(); const submitPath = `/api/requests/${rid}/submit`;
  expect((await page.request.post(submitPath, { headers: submitHeaders, data: { expectedVersion: 0 } })).status()).toBe(200);
  expect((await page.request.post(submitPath, { headers: submitHeaders, data: { expectedVersion: 0 } })).headers()['idempotency-replayed']).toBe('true');
  const detail = await (await page.request.get(`/api/requests/${rid}`)).json() as { requestNumber: string; containers: { id: string }[] };
  expect((await page.request.post(`/api/receptions/${rid}/receive`, { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-STORAGE-001', containerIds: detail.containers.map(c => c.id) } })).status()).toBe(200);

  const path = `/api/requests/${rid}/storage`;
  const listing = await page.request.get(path); expect(listing.status()).toBe(200); const view = await listing.json() as View;
  const data = Buffer.alloc(2097152); for (let i = 0; i < data.length; i++) data[i] = i % 251; data.set(Buffer.from('PIS-SYNTHETIC-STORAGE-V1\n'));
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
  const received: Buffer[] = []; for (const start of [0, 1048576]) { const slice = await page.request.get(`${path}/${id}/bytes`, { headers: { 'X-Storage-Purpose': 'DOWNLOAD', Range: `bytes=${start}-${start + 1048575}` } }); expect(slice.status()).toBe(206); expect(slice.headers()['cache-control']).toBe('no-store'); received.push(await slice.body()); }
  expect(createHash('sha256').update(Buffer.concat(received)).digest('hex')).toBe(sha256);
  expect((await page.request.get(`${path}/${id}/bytes`, { headers: { 'X-Storage-Purpose': 'DOWNLOAD' } })).status()).toBe(416);
  await page.getByRole('button', { name: '申请登记工作区' }).click(); await expect(page.getByText('当前工作范围：合成申请工作范围', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: `查看 ${detail.requestNumber}`, exact: true }).click(); await page.getByRole('button', { name: '处理此申请原件' }).click();
  await expect(page.getByRole('cell', { name: 'READY / 4', exact: true })).toBeVisible(); await page.getByRole('button', { name: '预览前128字节' }).click(); await expect(page.getByLabel('固定版本十六进制预览')).toContainText(id);
  const other = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const receiver = await other.newPage(); await receiver.goto('/'); await receiver.getByLabel('用户名', { exact: true }).fill(handoffUsername()); await receiver.getByLabel('密码', { exact: true }).fill(process.env.PIS_E2E_HANDOFF_PASSWORD ?? 'Synthetic-handoff-only-42!');
    const accepted = receiver.waitForResponse(r => new URL(r.url()).pathname === '/api/auth/login'); await receiver.getByRole('button', { name: '登录', exact: true }).click(); expect((await accepted).status()).toBe(204);
    expect((await receiver.request.get(`${path}/${id}`)).status()).toBe(404);
  } finally { await other.close(); }
});
