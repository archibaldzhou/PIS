import { expect, test, type Page } from '@playwright/test';

import { workflowAccountName } from './workflow-account-name';

export function handoffUsername() {
  return workflowAccountName(test.info().file, process.env.PIS_E2E_HANDOFF_USERNAME ?? 'synthetic.technician');
}

/** Real login in each isolated browser context, using a file-specific synthetic identity. */
export async function signInWorkflow(page: Page) {
  const username = workflowAccountName(test.info().file, process.env.PIS_E2E_WORKFLOW_USERNAME ?? 'synthetic.workflow');
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '登录 PIS', exact: true })).toBeVisible();
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(process.env.PIS_E2E_WORKFLOW_PASSWORD ?? 'Synthetic-workflow-only-42!');
  const accepted = page.waitForResponse(r => new URL(r.url()).pathname === '/api/auth/login' && r.request().method() === 'POST');
  const identity = page.waitForResponse(r => new URL(r.url()).pathname === '/api/auth/me' && r.status() === 200);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  const [currentUser] = await Promise.all([identity, accepted.then(r => {
    expect(r.status(), 'workflow login must be accepted before entering protected UI').toBe(204);
  })]);
  expect(await currentUser.json()).toMatchObject({ username, id: expect.any(String), displayName: '合成工作流用户' });
  await expect(page.getByText(`已登录：合成工作流用户（${username}）`, { exact: true })).toBeVisible();
}
