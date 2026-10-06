import { test, expect } from '@playwright/test';
import { signInWorkflow, handoffUsername } from './workflow-login';
import type { View } from '../src/features/statistics/api';

test('authorized saved events produce frozen statistics, open waiting, exact source drill and scoped denial', async ({ page, browser }) => {
  await signInWorkflow(page);
  const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
  expect(scopes).toHaveLength(1); const scope = scopes[0].id;
  const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scope, number: 'SYN-STATS-001' } })).json() as { id: string; patientId: string }[];
  const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
  const headers = () => ({ 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() });
  const created = await page.request.post('/api/requests', { headers: headers(), data: { scopeId: scope, encounterId: encounters[0].id, draft: { clinicalHistory: 'Synthetic statistics source', sampledAt: '2026-01-01T08:00:00Z', containers: [{ site: 'Synthetic site', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic', fixedAt: '2026-01-01T08:10:00Z' }] } } });
  expect(created.status()).toBe(201); const rid = (await created.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  const submitHeaders = headers(); const submitPath = `/api/requests/${rid}/submit`;
  expect((await page.request.post(submitPath, { headers: submitHeaders, data: { expectedVersion: 0 } })).status()).toBe(200);
  expect((await page.request.post(submitPath, { headers: submitHeaders, data: { expectedVersion: 0 } })).headers()['idempotency-replayed']).toBe('true');
  await page.getByRole('button', { name: '申请登记工作区' }).click(); await expect(page.getByText('当前工作范围：合成申请工作范围', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '工作量TAT与QC统计', exact: true }).click();
  const loaded = page.waitForResponse(r => r.url().includes('/statistics/scopes/') && r.request().method() === 'GET');
  await page.getByRole('button', { name: '冻结并查询统计' }).click(); const response = await loaded; expect(response.status()).toBe(200); const initial = await response.json() as View;
  expect(initial.summaries.find(s => s.metric === 'RECEPTION')).toMatchObject({ cohort: 1, completed: 0, open: 1, medianSeconds: null });
  await page.getByRole('button', { name: '查看接收来源' }).click(); await expect(page.getByRole('cell', { name: 'OPEN', exact: true })).toBeVisible();
  const detail = await (await page.request.get(`/api/requests/${rid}`)).json() as { containers: { id: string }[] };
  expect((await page.request.post(`/api/receptions/${rid}/receive`, { headers: headers(), data: { expectedVersion: 1, patientId: encounters[0].patientId, encounterNumber: 'SYN-STATS-001', containerIds: detail.containers.map(c => c.id) } })).status()).toBe(200);
  const path = `/api/requests/statistics/scopes/${scope}`;
  const frozen = await (await page.request.get(`${path}/${initial.id}?metric=RECEPTION`)).json() as View;
  expect(frozen.facts).toHaveLength(1); expect(frozen.facts[0]).toMatchObject({ requestId: rid, status: 'OPEN' }); expect(frozen.factsHash).toBe(initial.factsHash);
  const newer = await page.request.post(path, { headers: headers(), data: { from: initial.from, to: initial.to, zone: 'UTC' } }); expect(newer.status()).toBe(200);
  const nextId = (await newer.json() as { receipt: { resourceId: string } }).receipt.resourceId;
  const next = await (await page.request.get(`${path}/${nextId}?metric=RECEPTION`)).json() as View;
  expect(next.summaries.find(s => s.metric === 'RECEPTION')).toMatchObject({ cohort: 1, completed: 1, open: 0 }); expect(next.facts[0].endEvent).not.toBeNull();
  const other = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const receiver = await other.newPage(); await receiver.goto('/'); await receiver.getByLabel('用户名', { exact: true }).fill(handoffUsername()); await receiver.getByLabel('密码', { exact: true }).fill(process.env.PIS_E2E_HANDOFF_PASSWORD ?? 'Synthetic-handoff-only-42!');
    const accepted = receiver.waitForResponse(r => new URL(r.url()).pathname === '/api/auth/login'); await receiver.getByRole('button', { name: '登录', exact: true }).click(); expect((await accepted).status()).toBe(204);
    expect((await receiver.request.get(`${path}/${initial.id}`)).status()).toBe(404);
  } finally { await other.close(); }
});
