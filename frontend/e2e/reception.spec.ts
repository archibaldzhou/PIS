import { test, expect, type Page } from '@playwright/test';

async function setup(page: Page, encounterNumber: string) {
  await page.goto('/');
  await page.getByLabel('用户名').fill(process.env.PIS_E2E_USERNAME ?? 'synthetic.reader');
  await page.getByLabel('密码', { exact: true }).fill(process.env.PIS_E2E_PASSWORD ?? 'Synthetic-test-only-42!');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('button', { name: '申请登记工作区' }).click();
  const scopes = await (await page.request.get('/api/requests/scopes')).json() as { id: string }[];
  const encounters = await (await page.request.get('/api/requests/encounters', { params: { scopeId: scopes[0].id, number: encounterNumber } })).json() as { id: string }[];
  const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
  const created = await page.request.post('/api/requests', { headers: { 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() }, data: {
    scopeId: scopes[0].id, encounterId: encounters[0].id, draft: { clinicalHistory: 'Synthetic reception E2E', sampledAt: '2026-01-01T08:00:00Z',
      containers: [{ site: 'Synthetic site', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic fixative', fixedAt: '2026-01-01T08:10:00Z' }] },
  } });
  expect(created.status()).toBe(201);
  const { receipt } = await created.json() as { receipt: { resourceId: string } };
  expect((await page.request.post(`/api/requests/${receipt.resourceId}/submit`, { headers: { 'X-CSRF-TOKEN': csrf.token, 'Idempotency-Key': crypto.randomUUID() }, data: { expectedVersion: 0 } })).status()).toBe(200);
  const detail = await (await page.request.get('/api/requests/' + receipt.resourceId)).json() as { requestNumber: string; patientId: string; encounterNumber: string; containers: { id: string }[] };
  await page.getByLabel('授权工作范围').click(); await page.getByText('合成申请工作范围', { exact: true }).last().click();
  await page.getByRole('button', { name: '查看 ' + detail.requestNumber, exact: true }).click();
  await page.getByRole('button', { name: '处理此申请接收与异常' }).click();
  await expect(page.getByLabel('接收申请身份')).toBeVisible();
  return detail;
}
async function scan(page: Page, detail: { patientId: string; encounterNumber: string; containers: { id: string }[] }) {
  await page.getByLabel('实物患者 UUID', { exact: true }).fill(detail.patientId);
  await page.getByLabel('实物就诊号', { exact: true }).fill(detail.encounterNumber);
  await page.getByLabel('逐个容器 UUID（空白分隔）', { exact: true }).fill(detail.containers.map(c => c.id).join('\n'));
}
test('checked reception creates a case once and survives refresh', async ({ page }) => {
  const detail = await setup(page, 'SYN-RECEIVE-001'); await scan(page, detail);
  await page.getByRole('button', { name: '核对并接收', exact: true }).dblclick();
  await expect(page.getByText(/^病理号：DEV-P-/)).toBeVisible();
  await expect(page.getByRole('button', { name: '核对并接收', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '放弃本地输入并刷新接收状态' }).click();
  await expect(page.getByText(/^病理号：DEV-P-/)).toBeVisible();
  await expect(page.getByRole('cell', { name: 'RECEIVE', exact: true })).toHaveCount(1);
});
test('identity mismatch blocks reception and preserves return history', async ({ page }) => {
  const detail = await setup(page, 'SYN-RETURN-001'); await scan(page, { ...detail, patientId: '00000000-0000-4000-8000-000000000001' });
  await page.getByRole('button', { name: '核对并接收', exact: true }).click();
  await expect(page.getByText('接收已阻断，进入异常处理；尚未生成病例')).toBeVisible();
  await expect(page.getByRole('button', { name: '补充后恢复待接收' })).toBeDisabled();
  await page.getByLabel('异常事实 / 补充说明 / 退回原因', { exact: true }).fill('Synthetic identity mismatch return');
  await page.getByRole('button', { name: '记录退回', exact: true }).click();
  await expect(page.getByLabel('接收申请身份')).toContainText('已退回');
  await expect(page.getByRole('cell', { name: 'IDENTITY', exact: true })).toHaveCount(1);
  await expect(page.getByRole('cell', { name: 'RETURN', exact: true })).toHaveCount(1);
  await expect(page.getByText(/^病理号：DEV-P-/)).toHaveCount(0);
});
test('information exception requires explanation and fresh reception check', async ({ page }) => {
  const detail = await setup(page, 'SYN-RESOLVE-001');
  await page.getByLabel('异常事实 / 补充说明 / 退回原因', { exact: true }).fill('Synthetic missing supporting information');
  await page.getByRole('button', { name: '登记异常', exact: true }).click();
  await expect(page.getByText('接收已阻断，进入异常处理；尚未生成病例')).toBeVisible();
  await page.getByLabel('异常事实 / 补充说明 / 退回原因', { exact: true }).fill('Synthetic information checked');
  await page.getByRole('button', { name: '补充后恢复待接收' }).click();
  await expect(page.getByLabel('接收申请身份')).toContainText('待接收');
  await scan(page, detail); await page.getByRole('button', { name: '核对并接收', exact: true }).click();
  await expect(page.getByText(/^病理号：DEV-P-/)).toBeVisible();
  await expect(page.getByRole('cell', { name: 'RESOLVE', exact: true })).toHaveCount(1);
  await expect(page.getByRole('cell', { name: 'EXCEPTION', exact: true })).toHaveCount(1);
});
