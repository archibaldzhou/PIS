import { describe, expect, it, vi } from 'vitest';
import { ApiError, type CurrentUser, type HelloResponse } from './api';
import { SessionController } from './session';

const user = { id: 'synthetic-id', username: 'synthetic.reader', displayName: '合成测试用户' };
const csrf = { headerName: 'X-CSRF-TOKEN' as const, token: 'synthetic-token' };
const hello = { message: 'Hello World', application: 'PIS' };
const credentials = { username: 'synthetic.reader', password: 'Synthetic-test-only-42!' };
const expired = () => new ApiError(401, 'UNAUTHENTICATED', '登录已失效，请重新登录');
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function fixture() {
  const api = {
    fetchCurrentUser: vi.fn().mockResolvedValue(user), fetchCsrf: vi.fn().mockResolvedValue(csrf),
    fetchHello: vi.fn().mockResolvedValue(hello), login: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn().mockResolvedValue(undefined),
  };
  const session = new SessionController(api);
  return { api, session };
}
async function anonymousFixture() {
  const fixtureValue = fixture();
  fixtureValue.api.fetchCurrentUser.mockRejectedValueOnce(expired());
  await fixtureValue.session.restore();
  return fixtureValue;
}

describe('session lifecycle', () => {
  it('restores a session and fetches Hello after the identity check', async () => {
    const { api, session } = fixture();
    await session.restore();
    expect(session.getSnapshot()).toMatchObject({ status: 'authenticated', user, hello: { status: 'success', data: hello } });
    expect(api.fetchCurrentUser).toHaveBeenCalledOnce();
    expect(api.fetchHello).toHaveBeenCalledOnce();
  });
  it('shows anonymous login without fetching protected Hello', async () => {
    const { api, session } = await anonymousFixture();
    expect(session.getSnapshot()).toEqual({ status: 'anonymous' });
    expect(api.fetchHello).not.toHaveBeenCalled();
  });
  it('keeps a recoverable startup failure distinct from unauthenticated', async () => {
    const { api, session } = fixture();
    api.fetchCurrentUser.mockRejectedValueOnce(new Error('network unavailable'));
    await session.restore();
    expect(session.getSnapshot()).toEqual({ status: 'error', recovery: 'check', message: 'network unavailable' });
    await session.restore();
    expect(session.getSnapshot().status).toBe('authenticated');
  });
  it('gets CSRF before login, refreshes it afterward, and checks the server identity', async () => {
    const { api, session } = await anonymousFixture();
    await session.login(credentials);
    expect(api.login).toHaveBeenCalledWith(credentials, csrf, expect.any(AbortSignal));
    expect(api.fetchCsrf).toHaveBeenCalledTimes(2);
    expect(api.fetchCsrf.mock.invocationCallOrder[0]).toBeLessThan(api.login.mock.invocationCallOrder[0]);
    expect(api.fetchCsrf.mock.invocationCallOrder[1]).toBeGreaterThan(api.login.mock.invocationCallOrder[0]);
    expect(session.getSnapshot()).toMatchObject({ status: 'authenticated', user });
  });
  it('prevents duplicate login submissions while the request is pending', async () => {
    const { api, session } = await anonymousFixture();
    const request = deferred<void>();
    api.login.mockReturnValueOnce(request.promise);
    const first = session.login(credentials);
    await Promise.resolve();
    await session.login(credentials);
    expect(api.login).toHaveBeenCalledOnce();
    expect(session.getSnapshot().status).toBe('authenticating');
    request.resolve();
    await first;
    expect(session.getSnapshot().status).toBe('authenticated');
  });
  it('shows login failures and allows a retry with fresh CSRF', async () => {
    const { api, session } = await anonymousFixture();
    api.login.mockRejectedValueOnce(new ApiError(401, 'AUTHENTICATION_FAILED', '用户名或密码不正确，或账号不可用'));
    await session.login(credentials);
    expect(session.getSnapshot()).toEqual({ status: 'anonymous', message: '用户名或密码不正确，或账号不可用' });
    await session.login(credentials);
    expect(session.getSnapshot().status).toBe('authenticated');
    expect(api.fetchCsrf).toHaveBeenCalledTimes(3);
  });
  it('does not auto-submit after fast CSRF failure and recovers only after an explicit retry', async () => {
    const { api, session } = await anonymousFixture();
    api.fetchCsrf.mockRejectedValueOnce(new Error('network unavailable'));
    await session.login(credentials);
    expect(api.login).not.toHaveBeenCalled();
    expect(session.getSnapshot()).toEqual({ status: 'anonymous', message: 'network unavailable' });
    await Promise.resolve();
    expect(api.login).not.toHaveBeenCalled();
    await session.login(credentials);
    expect(api.login).toHaveBeenCalledOnce();
    expect(api.fetchCsrf).toHaveBeenCalledTimes(3);
    expect(session.getSnapshot()).toMatchObject({ status: 'authenticated', user });
  });
  it('offers identity recheck if login succeeds but me fails', async () => {
    const { api, session } = await anonymousFixture();
    api.fetchCurrentUser.mockRejectedValueOnce(new Error('network unavailable'));
    await session.login(credentials);
    expect(session.getSnapshot()).toEqual({ status: 'error', recovery: 'check', message: '登录状态确认失败：network unavailable' });
    await session.restore();
    expect(session.getSnapshot().status).toBe('authenticated');
  });
  it('expires a just-created session if the identity check returns 401', async () => {
    const { api, session } = await anonymousFixture();
    api.fetchCurrentUser.mockRejectedValueOnce(expired());
    await session.login(credentials);
    expect(session.getSnapshot()).toEqual({ status: 'anonymous', message: '登录已失效，请重新登录' });
  });
  it('returns to login if the post-login CSRF refresh reports an expired session', async () => {
    const { api, session } = await anonymousFixture();
    api.fetchCsrf.mockResolvedValueOnce(csrf).mockRejectedValueOnce(expired());
    await session.login(credentials);
    expect(session.getSnapshot()).toEqual({ status: 'anonymous', message: '登录已失效，请重新登录' });
  });
  it('reports CSRF refresh failure without hiding an established session', async () => {
    const { api, session } = await anonymousFixture();
    api.fetchCsrf.mockResolvedValueOnce(csrf).mockRejectedValueOnce(new Error('network unavailable'));
    await session.login(credentials);
    expect(session.getSnapshot()).toMatchObject({ status: 'authenticated', notice: expect.stringContaining('安全令牌刷新失败') });
  });
  it('clears the user view during logout, blocks repeated logout, and refreshes CSRF', async () => {
    const { api, session } = fixture();
    await session.restore();
    const request = deferred<void>();
    api.logout.mockReturnValueOnce(request.promise);
    const first = session.logout();
    await Promise.resolve();
    expect(session.getSnapshot()).toEqual({ status: 'logging-out' });
    await session.logout();
    expect(api.logout).toHaveBeenCalledOnce();
    request.resolve();
    await first;
    expect(session.getSnapshot().status).toBe('anonymous');
    expect(api.fetchCsrf).toHaveBeenCalledTimes(2);
  });
  it('does not claim logout succeeded when the logout request fails; supports retry', async () => {
    const { api, session } = fixture();
    await session.restore();
    api.logout.mockRejectedValueOnce(new ApiError(403, 'CSRF_INVALID', '安全校验已失效，请重试'));
    await session.logout();
    expect(session.getSnapshot()).toEqual({ status: 'error', recovery: 'logout', message: '退出登录未完成：安全校验已失效，请重试' });
    await session.logout();
    expect(session.getSnapshot().status).toBe('anonymous');
  });
  it('treats an already-expired logout session as anonymous', async () => {
    const { api, session } = fixture();
    await session.restore();
    api.logout.mockRejectedValueOnce(expired());
    await session.logout();
    expect(session.getSnapshot()).toEqual({ status: 'anonymous', message: '登录已失效，请重新登录' });
  });
  it('shows a warning if anonymous CSRF refresh fails after confirmed logout', async () => {
    const { api, session } = fixture();
    await session.restore();
    api.fetchCsrf.mockResolvedValueOnce(csrf).mockRejectedValueOnce(new Error('offline'));
    await session.logout();
    expect(session.getSnapshot()).toMatchObject({ status: 'anonymous', message: expect.stringContaining('安全令牌刷新失败') });
  });
});

describe('protected Hello and cancellation', () => {
  it('recovers Hello after a network failure without losing the user', async () => {
    const { api, session } = fixture();
    api.fetchHello.mockRejectedValueOnce(new Error('network unavailable'));
    await session.restore();
    expect(session.getSnapshot()).toMatchObject({ status: 'authenticated', hello: { status: 'error', message: 'network unavailable' } });
    await session.retryHello();
    expect(session.getSnapshot()).toMatchObject({ status: 'authenticated', hello: { status: 'success' } });
  });
  it('returns to login on Hello 401', async () => {
    const { api, session } = fixture();
    await session.restore();
    api.fetchHello.mockRejectedValueOnce(expired());
    await session.retryHello();
    expect(session.getSnapshot()).toEqual({ status: 'anonymous', message: '登录已失效，请重新登录' });
  });
  it.each(['CSRF_INVALID', 'ACCESS_DENIED'])('preserves the session and explains Hello 403 %s', async code => {
    const { api, session } = fixture();
    await session.restore();
    api.fetchHello.mockRejectedValueOnce(new ApiError(403, code, code));
    await session.retryHello();
    expect(session.getSnapshot()).toMatchObject({ status: 'authenticated', user, hello: { status: 'error', message: code } });
  });
  it('ignores older Hello results even if the transport ignores abort', async () => {
    const { api, session } = fixture();
    const old = deferred<HelloResponse>();
    api.fetchHello.mockReturnValueOnce(old.promise);
    await session.restore();
    await session.retryHello();
    expect(api.fetchHello.mock.calls[0][0].aborted).toBe(true);
    old.resolve({ message: 'stale', application: 'stale' });
    await Promise.resolve();
    expect(session.getSnapshot()).toMatchObject({ hello: { status: 'success', data: hello } });
  });
  it.each(['resolve', 'reject'] as const)('does not reverse logout when delayed Hello %s arrives', async outcome => {
    const { api, session } = fixture();
    const old = deferred<HelloResponse>();
    api.fetchHello.mockReturnValueOnce(old.promise);
    await session.restore();
    await session.logout();
    expect(api.fetchHello.mock.calls[0][0].aborted).toBe(true);
    if (outcome === 'resolve') old.resolve(hello); else old.reject(expired());
    await Promise.resolve();
    expect(session.getSnapshot()).toEqual({ status: 'anonymous', message: undefined });
  });
  it('ignores stale me after a newer restore and logout have completed', async () => {
    const { api, session } = fixture();
    const old = deferred<CurrentUser>();
    api.fetchCurrentUser.mockReturnValueOnce(old.promise);
    const pending = session.restore();
    await session.restore();
    await session.logout();
    expect(api.fetchCurrentUser.mock.calls[0][0].aborted).toBe(true);
    old.resolve(user);
    await pending;
    expect(session.getSnapshot().status).toBe('anonymous');
    expect(api.fetchHello).toHaveBeenCalledOnce();
  });
  it.each(['resolve', 'reject'] as const)('ignores obsolete CSRF %s after a newer authenticated session check', async outcome => {
    const { api, session } = await anonymousFixture();
    const old = deferred<typeof csrf>();
    api.fetchCsrf.mockReturnValueOnce(old.promise);
    const pending = session.login(credentials);
    await session.restore();
    expect(api.fetchCsrf.mock.calls[0][0].aborted).toBe(true);
    if (outcome === 'resolve') old.resolve(csrf); else old.reject(new Error('late CSRF failure'));
    await pending;
    expect(session.getSnapshot()).toMatchObject({ status: 'authenticated', user });
    expect(api.login).not.toHaveBeenCalled();
    expect(api.fetchHello).toHaveBeenCalledOnce();
  });
  it('does not finish an obsolete login after a newer session check', async () => {
    const { api, session } = await anonymousFixture();
    const old = deferred<void>();
    api.login.mockReturnValueOnce(old.promise);
    const pending = session.login(credentials);
    await Promise.resolve();
    api.fetchCurrentUser.mockRejectedValueOnce(expired());
    await session.restore();
    old.resolve();
    await pending;
    expect(session.getSnapshot()).toEqual({ status: 'anonymous' });
    expect(api.fetchHello).not.toHaveBeenCalled();
  });
  it('cancels on unmount and allows Strict Mode setup-cleanup-setup', async () => {
    const { api, session } = fixture();
    const old = deferred<CurrentUser>();
    api.fetchCurrentUser.mockReturnValueOnce(old.promise);
    const pending = session.restore();
    session.stop();
    await session.restore();
    old.reject(new Error('late failure'));
    await pending;
    expect(session.getSnapshot().status).toBe('authenticated');
  });
  it('notifies and unsubscribes state subscribers', async () => {
    const { session } = fixture();
    const listener = vi.fn();
    const unsubscribe = session.subscribe(listener);
    await session.restore();
    expect(listener).toHaveBeenCalled();
    unsubscribe();
    listener.mockClear();
    await session.logout();
    expect(listener).not.toHaveBeenCalled();
  });
});
