import { expect, type Page, type Response } from '@playwright/test';
import { parseDetail } from '../src/features/report/reviewApi';
export const reviewPath = (caseId: string) => `/api/requests/reports/cases/${caseId}/review`;
export function nextReview(page: Page, caseId: string) {
 return page.waitForResponse(r => new URL(r.url()).pathname === reviewPath(caseId) && r.request().method() === 'GET');
}
/** A response header/status is not a React commit or a fresh editor. */
export async function settledReview(page: Page, caseId: string, response: Response) {
 expect(response.status()).toBe(200); expect(await response.finished()).toBeNull();
 const detail = parseDetail(await response.json(), caseId), identity = page.getByLabel('复核版本身份');
 await expect(identity.getByText(`流程版本 ${detail.version}`, { exact: true })).toBeVisible();
 await expect(identity.getByText(`状态 ${detail.state}`, { exact: true })).toBeVisible();
 await expect(identity.getByText(`报告修订 ${detail.draft?.id ?? '无'}`, { exact: true })).toBeVisible();
 await expect(page.getByRole('status', { name: '正在查询', exact: true })).toHaveCount(0);
 await expect(page.getByRole('dialog')).toHaveCount(0);
 await expect(page.getByLabel('核对复核病例 UUID')).toHaveValue('');
 await expect(page.getByLabel('复核或退回原因')).toHaveValue('');
 return detail;
}
export async function refreshDirtyReview(page: Page, caseId: string) {
 await expect(page.getByLabel('复核或退回原因')).toHaveValue('Synthetic explicit human action');
 await page.getByRole('button', { name: '刷新诊断队列与资格' }).click();
 const dialog = page.getByRole('dialog', { name: '放弃未保存的复核输入？', exact: true });
 await expect(dialog).toBeVisible(); const fresh = nextReview(page, caseId);
 await dialog.getByRole('button', { name: '放弃并切换', exact: true }).click();
 return settledReview(page, caseId, await fresh);
}
export async function reviewAction(page: Page, caseId: string, action: 'APPROVE' | 'SIMULATE_SIGN', expectedStatus = 200) {
 await expect(page.getByRole('dialog')).toHaveCount(0);
 await expect(page.getByRole('status', { name: '正在查询', exact: true })).toHaveCount(0);
 await expect(page.getByLabel('合成复核动作')).toBeEnabled();
 await expect(page.getByLabel('核对复核病例 UUID')).toHaveValue('');
 await expect(page.getByLabel('复核或退回原因')).toHaveValue('');
 if (action === 'SIMULATE_SIGN') {
  // Deliberately dirty: clearing this text must require explicit confirmation.
  await page.getByLabel('复核或退回原因').fill('Synthetic unsaved action switch');
  await page.getByLabel('合成复核动作').click();
  await page.getByRole('option', { name: '模拟签署（无临床效力）', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '切换动作并清除原因？', exact: true });
  await expect(dialog).toBeVisible(); await dialog.getByRole('button', { name: '清除并切换动作', exact: true }).click();
  await expect(dialog).toHaveCount(0); await expect(page.getByLabel('复核或退回原因')).toHaveValue('');
 }
 await page.getByLabel('核对复核病例 UUID').fill(caseId);
 await page.getByLabel('复核或退回原因').fill('Synthetic explicit human action');
 const fresh = expectedStatus === 200 ? nextReview(page, caseId) : undefined;
 const sent = page.waitForResponse(r => new URL(r.url()).pathname === `${reviewPath(caseId)}/${action}` && r.request().method() === 'POST');
 await page.getByRole('button', { name: '提交合成复核操作' }).click();
 if (action === 'SIMULATE_SIGN') {
  const dialog = page.getByRole('dialog', { name: '确认仅执行无临床效力的模拟签署？', exact: true });
  await expect(dialog).toBeVisible(); await dialog.getByRole('button', { name: '确认合成模拟', exact: true }).click();
 }
 const response = await sent; expect(response.status()).toBe(expectedStatus); expect(await response.finished()).toBeNull();
 if (fresh) await settledReview(page, caseId, await fresh);
 else {
  expect(await response.json()).toMatchObject({ code: 'REPORT_SEPARATION_REQUIRED' });
  await expect(page.getByText('当前开发策略要求作者与复核人员不同。', { exact: true })).toBeVisible();
  await expect(page.getByLabel('合成复核动作')).toBeEnabled();
  await expect(page.getByLabel('复核或退回原因')).toHaveValue('Synthetic explicit human action');
 }
}
