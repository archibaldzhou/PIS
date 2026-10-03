import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, fetchCsrf, fetchCurrentUser, fetchHello, login, logout } from './api';

const csrf = { headerName: 'X-CSRF-TOKEN' as const, token: 'synthetic-csrf-token' };
const user = { id: 'synthetic-id', username: 'synthetic.reader', displayName: '合成测试用户' };
function respond(body: unknown, status = 200) {
  const mock = vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status }));
  vi.stubGlobal('fetch', mock);
  return mock;
}
afterEach(() => vi.unstubAllGlobals());

describe('read-only API requests', () => {
  it('validates Hello and sends same-origin cookies without caching', async () => {
    const mock = respond({ message: 'Hello World', application: 'PIS' });
    const signal = new AbortController().signal;
    await expect(fetchHello(signal)).resolves.toEqual({ message: 'Hello World', application: 'PIS' });
    expect(mock).toHaveBeenCalledWith('/api/hello', { signal, credentials: 'same-origin', cache: 'no-store' });
  });
  it('validates current user and does not expose extra server fields', async () => {
    respond({ ...user, internal: 'not returned' });
    await expect(fetchCurrentUser()).resolves.toEqual(user);
  });
  it('validates CSRF token and keeps it out of the request URL', async () => {
    const mock = respond(csrf);
    await expect(fetchCsrf()).resolves.toEqual(csrf);
    expect(mock.mock.calls[0][0]).toBe('/api/auth/csrf');
  });
  it.each([{}, null, { message: 123 }, { message: 'Hello' }])('rejects invalid Hello data: %j', async body => {
    respond(body);
    await expect(fetchHello()).rejects.toThrow('后端返回格式不正确');
  });
  it.each([{}, { ...user, id: 10 }, { ...user, username: '' }, { ...user, displayName: null }])(
    'rejects invalid current user: %j', async body => {
      respond(body);
      await expect(fetchCurrentUser()).rejects.toThrow('后端返回格式不正确');
    },
  );
  it.each([{ ...csrf, token: '' }, { ...csrf, headerName: 'Authorization' }, {}])(
    'rejects invalid CSRF data: %j', async body => {
      respond(body);
      await expect(fetchCsrf()).rejects.toThrow('后端返回格式不正确');
    },
  );
  it('rejects invalid JSON explicitly', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>Not JSON</html>')));
    await expect(fetchHello()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });
});

describe('session mutations', () => {
  it('posts form credentials, CSRF header, same-origin cookies, and cancellation', async () => {
    const mock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', mock);
    const signal = new AbortController().signal;
    await login({ username: 'a+b@example.test', password: 'synthetic&password=42!' }, csrf, signal);
    const [url, options] = mock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/auth/login');
    expect(options).toMatchObject({ method: 'POST', signal, headers: { 'X-CSRF-TOKEN': csrf.token }, credentials: 'same-origin', cache: 'no-store' });
    expect(options.body).toBeInstanceOf(URLSearchParams);
    expect((options.body as URLSearchParams).get('username')).toBe('a+b@example.test');
    expect((options.body as URLSearchParams).get('password')).toBe('synthetic&password=42!');
  });
  it('posts logout without credentials in the body', async () => {
    const mock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', mock);
    await logout(csrf);
    expect(mock).toHaveBeenCalledWith('/api/auth/logout', expect.objectContaining({
      method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-TOKEN': csrf.token },
    }));
    expect(mock.mock.calls[0][1].body).toBeUndefined();
  });
});

describe('failure classification', () => {
  it('uses the same generic login error even if the server sends account details', async () => {
    respond({ code: 'ACCOUNT_DISABLED', message: 'private account detail' }, 401);
    await expect(login({ username: 'synthetic', password: 'synthetic' }, csrf)).rejects.toMatchObject({
      status: 401, code: 'AUTHENTICATION_FAILED', message: '用户名或密码不正确，或账号不可用',
    });
  });
  it.each([
    [429, 'LOGIN_THROTTLED', '登录尝试过于频繁，请稍后再试'],
    [503, 'AUTHENTICATION_UNAVAILABLE', '登录服务暂不可用，请稍后重试'],
  ] as const)('explains login HTTP %s without disclosing internal details', async (status, code, expectedMessage) => {
    respond({ message: 'private server detail' }, status);
    await expect(login({ username: 'synthetic', password: 'synthetic' }, csrf)).rejects.toMatchObject({ status, code, message: expectedMessage });
  });
  it('classifies other 401 responses as expired sessions', async () => {
    respond({}, 401);
    await expect(fetchHello()).rejects.toMatchObject({ status: 401, code: 'UNAUTHENTICATED', message: '登录已失效，请重新登录' });
  });
  it.each([
    ['CSRF_INVALID', '安全校验已失效，请重试'],
    ['ACCESS_DENIED', '无权访问该资源，请联系管理员'],
    ['FORBIDDEN', '请求被拒绝，请重试或联系管理员'],
  ])('distinguishes 403 %s', async (code, expectedMessage) => {
    respond({ code }, 403);
    await expect(fetchHello()).rejects.toMatchObject({ status: 403, code, message: expectedMessage });
  });
  it('handles non-JSON HTTP failures without leaking server response text', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('private stack trace', { status: 503 })));
    await expect(fetchHello()).rejects.toThrow('后端请求失败（HTTP 503）');
  });
  it('normalizes network failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    await expect(fetchHello()).rejects.toEqual(new ApiError(0, 'NETWORK_ERROR', '无法连接后端，请检查网络后重试'));
  });
  it('preserves cancellation instead of presenting a network error', async () => {
    const controller = new AbortController();
    controller.abort();
    const error = new DOMException('Aborted', 'AbortError');
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(error));
    await expect(fetchHello(controller.signal)).rejects.toBe(error);
  });
});

describe('frozen errors remain definitive and do not echo supplied content', () => {
  it.each(['FROZEN_REVIEW_INVALIDATED', 'FROZEN_READBACK_REQUIRED', 'FROZEN_TIME_ORDER'])('preserves %s for safe correction and retry', async code => {
    respond({ code, detail: 'untrusted synthetic server text' }, 409);
    const { request } = await import('./api');
    await expect(request('/api/requests/frozen/cases/synthetic/DRAFT')).rejects.toMatchObject({ status: 409, code });
    await expect(request('/api/requests/frozen/cases/synthetic/DRAFT')).rejects.not.toThrow('untrusted synthetic server text');
  });
});
