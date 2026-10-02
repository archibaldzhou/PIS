import { test, expect, type Page } from '@playwright/test';

// Synthetic fixtures only. These defaults never reach production source code.
const username = process.env.PIS_E2E_USERNAME ?? 'synthetic.reader';
const password = process.env.PIS_E2E_PASSWORD ?? 'Synthetic-test-only-42!';
const displayName = process.env.PIS_E2E_DISPLAY_NAME ?? '合成测试用户';
const disabledUsername = process.env.PIS_E2E_DISABLED_USERNAME ?? 'synthetic.disabled';
const disabledPassword = process.env.PIS_E2E_DISABLED_PASSWORD ?? password;
const genericLoginError = '用户名或密码不正确，或账号不可用';

async function fillLogin(page: Page, loginUsername = username, loginPassword = password) {
  await page.getByLabel('用户名', { exact: true }).fill(loginUsername);
  await page.getByLabel('密码', { exact: true }).fill(loginPassword);
}
async function signIn(page: Page) {
  await page.goto('/');
  await fillLogin(page);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByText(`已登录：${displayName}（${username}）`, { exact: true })).toBeVisible();
}
async function expectHello(page: Page) {
  await expect(page.getByText('应用：PIS · API 连接成功', { exact: true })).toBeVisible();
}
function barrier() {
  let release!: () => void;
  const promise = new Promise<void>(resolve => { release = resolve; });
  return { promise, release };
}

test('anonymous users see an accessible login form without protected data', async ({ page }) => {
  let helloRequests = 0;
  page.on('request', request => { if (new URL(request.url()).pathname === '/api/hello') helloRequests++; });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '登录 PIS' })).toBeVisible();
  await expect(page.getByLabel('用户名', { exact: true })).toHaveAttribute('autocomplete', 'username');
  await expect(page.getByLabel('密码', { exact: true })).toHaveAttribute('autocomplete', 'current-password');
  await expect(page.getByRole('combobox')).toHaveCount(0);
  expect(helloRequests).toBe(0);
  expect((await page.request.get('/api/hello')).status()).toBe(401);
});

test('real login, Hello retry, refresh session restoration, and logout', async ({ page, context }) => {
  await signIn(page);
  await expectHello(page);
  const sessionCookie = (await context.cookies()).find(cookie => cookie.name === 'PIS_SESSION');
  expect(sessionCookie?.httpOnly).toBe(true);
  expect(sessionCookie?.sameSite).toBe('Lax');
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length })))
    .toEqual({ local: 0, session: 0 });
  await page.getByRole('button', { name: '重新请求' }).click();
  await expectHello(page);
  await page.reload();
  await expectHello(page);
  await expect(page.getByText(`已登录：${displayName}（${username}）`, { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page.getByRole('heading', { name: '登录 PIS' })).toBeVisible();
  expect((await page.request.get('/api/auth/me')).status()).toBe(401);
  expect((await page.request.get('/api/hello')).status()).toBe(401);
  await page.reload();
  await expect(page.getByRole('heading', { name: '登录 PIS' })).toBeVisible();
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('');
});

test('wrong password shows a generic failure and clears the submitted password', async ({ page }) => {
  await page.goto('/');
  await fillLogin(page, username, 'Incorrect-synthetic-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText(genericLoginError);
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('');
  await expect(page.getByLabel('用户名', { exact: true })).toHaveValue(username);
  expect((await page.request.get('/api/auth/me')).status()).toBe(401);
});

test('disabled account gets exactly the same generic failure', async ({ page }) => {
  await page.goto('/');
  await fillLogin(page, disabledUsername, disabledPassword);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText(genericLoginError);
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('');
});

test('empty credentials are validated before submitting', async ({ page }) => {
  let loginRequests = 0;
  page.on('request', request => { if (new URL(request.url()).pathname === '/api/auth/login') loginRequests++; });
  await page.goto('/');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByText('请输入用户名', { exact: true })).toBeVisible();
  await expect(page.getByText('请输入密码', { exact: true })).toBeVisible();
  expect(loginRequests).toBe(0);
});

test('a failed initial session check can be retried without assuming logout', async ({ page }) => {
  await page.route('**/api/auth/me', route => route.abort('failed'));
  await page.goto('/');
  await expect(page.getByText('会话请求失败', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '登录 PIS' })).toHaveCount(0);
  await page.unroute('**/api/auth/me');
  await page.getByRole('button', { name: '重新检查登录状态' }).click();
  await expect(page.getByRole('heading', { name: '登录 PIS' })).toBeVisible();
});

test('login CSRF errors are explicit and retry gets a fresh token', async ({ page }) => {
  let tokenRequests = 0;
  page.on('request', request => { if (new URL(request.url()).pathname === '/api/auth/csrf') tokenRequests++; });
  await page.route('**/api/auth/login', route => route.fulfill({
    status: 403, contentType: 'application/json', body: JSON.stringify({ code: 'CSRF_INVALID', message: 'invalid' }),
  }));
  await page.goto('/');
  await fillLogin(page);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('安全校验已失效，请重试');
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('');
  await page.unroute('**/api/auth/login');
  await fillLogin(page);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expectHello(page);
  expect(tokenRequests).toBeGreaterThanOrEqual(3);
});

test('login network error clears the password and a later real login recovers', async ({ page }) => {
  await page.route('**/api/auth/csrf', route => route.abort('failed'));
  await page.goto('/');
  await fillLogin(page);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('无法连接后端，请检查网络后重试');
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('');
  await page.unroute('**/api/auth/csrf');
  await fillLogin(page);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expectHello(page);
});

test('repeated submit while login is pending sends one real login request', async ({ page }) => {
  const held = barrier();
  const reached = barrier();
  let loginRequests = 0;
  await page.route('**/api/auth/login', async route => {
    loginRequests++;
    reached.release();
    await held.promise;
    await route.continue();
  });
  await page.goto('/');
  await fillLogin(page);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await reached.promise;
  await expect(page.getByLabel('用户名', { exact: true })).toBeDisabled();
  await expect(page.getByLabel('密码', { exact: true })).toBeDisabled();
  await page.keyboard.press('Enter');
  expect(loginRequests).toBe(1);
  held.release();
  await expectHello(page);
  expect(loginRequests).toBe(1);
});

test('shows a Hello HTTP error after login and recovers on retry', async ({ page }) => {
  await page.route('**/api/hello', route => route.fulfill({ status: 503, body: 'Unavailable' }));
  await signIn(page);
  await expect(page.getByText('连接失败', { exact: true })).toBeVisible();
  await expect(page.getByText('后端请求失败（HTTP 503）', { exact: true })).toBeVisible();
  await page.unroute('**/api/hello');
  await page.getByRole('button', { name: '重新请求' }).click();
  await expectHello(page);
});

test('shows a Hello network error after login and recovers on retry', async ({ page }) => {
  await page.route('**/api/hello', route => route.abort('failed'));
  await signIn(page);
  await expect(page.getByText('无法连接后端，请检查网络后重试', { exact: true })).toBeVisible();
  await page.unroute('**/api/hello');
  await page.getByRole('button', { name: '重新请求' }).click();
  await expectHello(page);
});

test('a server-invalidated session returns to login with an expired message', async ({ page }) => {
  await signIn(page);
  await expectHello(page);
  const csrf = await (await page.request.get('/api/auth/csrf')).json() as { token: string };
  expect((await page.request.post('/api/auth/logout', { headers: { 'X-CSRF-TOKEN': csrf.token } })).status()).toBe(204);
  await page.getByRole('button', { name: '重新请求' }).click();
  await expect(page.getByRole('heading', { name: '登录 PIS' })).toBeVisible();
  await expect(page.getByRole('alert')).toHaveText('登录已失效，请重新登录');
  await expect(page.getByText('应用：PIS · API 连接成功', { exact: true })).toHaveCount(0);
});

test('resource permission denial retains the session and explains access failure', async ({ page }) => {
  await page.route('**/api/hello', route => route.fulfill({
    status: 403, contentType: 'application/json', body: JSON.stringify({ code: 'ACCESS_DENIED', message: 'forbidden' }),
  }));
  await signIn(page);
  await expect(page.getByText('无权访问该资源，请联系管理员', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible();
  await page.unroute('**/api/hello');
  await page.getByRole('button', { name: '重新请求' }).click();
  await expectHello(page);
});

test('failed logout is not presented as successful and can be retried', async ({ page }) => {
  await signIn(page);
  await expectHello(page);
  await page.route('**/api/auth/logout', route => route.abort('failed'));
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page.getByText('退出登录未完成：无法连接后端，请检查网络后重试', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '登录 PIS' })).toHaveCount(0);
  await page.unroute('**/api/auth/logout');
  await page.getByRole('button', { name: '重试退出' }).click();
  await expect(page.getByRole('heading', { name: '登录 PIS' })).toBeVisible();
  expect((await page.request.get('/api/auth/me')).status()).toBe(401);
});

test('a delayed Hello response cannot restore the view after logout', async ({ page }) => {
  const held = barrier();
  const reached = barrier();
  await page.route('**/api/hello', async route => {
    const response = await route.fetch();
    reached.release();
    await held.promise;
    // A browser may already have aborted this request after logout.
    await route.fulfill({ response }).catch(() => undefined);
  });
  await signIn(page);
  await reached.promise;
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page.getByRole('heading', { name: '登录 PIS' })).toBeVisible();
  held.release();
  await expect(page.getByRole('button', { name: '退出登录' })).toHaveCount(0);
  expect((await page.request.get('/api/auth/me')).status()).toBe(401);
  await page.reload();
  await expect(page.getByRole('heading', { name: '登录 PIS' })).toBeVisible();
});

test('missing or incorrect CSRF is rejected by the real login endpoint', async ({ page }) => {
  const noToken = await page.request.post('/api/auth/login', { form: { username, password } });
  expect(noToken.status()).toBe(403);
  expect((await noToken.json()).code).toBe('CSRF_INVALID');
  await page.request.get('/api/auth/csrf');
  const wrongToken = await page.request.post('/api/auth/login', {
    headers: { 'X-CSRF-TOKEN': 'synthetic-invalid-token' }, form: { username, password },
  });
  expect(wrongToken.status()).toBe(403);
  expect((await wrongToken.json()).code).toBe('CSRF_INVALID');
  expect((await page.request.get('/api/auth/me')).status()).toBe(401);
});

test('login and signed-in content fit a narrow mobile viewport', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await signIn(page);
  await expectHello(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page.getByLabel('用户名', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
