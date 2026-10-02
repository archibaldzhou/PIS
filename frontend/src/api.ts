export interface HelloResponse { message: string; application: string }
export interface CurrentUser { id: string; username: string; displayName: string }
export interface CsrfToken { headerName: 'X-CSRF-TOKEN'; token: string }
export interface Credentials { username: string; password: string }

export class ApiError extends Error {
  constructor(public readonly status: number, public readonly code: string, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function request(path: string, options: RequestInit = {}): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(path, { ...options, credentials: 'same-origin', cache: 'no-store' });
  } catch (error) {
    if (options.signal?.aborted || (error instanceof Error && error.name === 'AbortError')) throw error;
    throw new ApiError(0, 'NETWORK_ERROR', '无法连接后端，请检查网络后重试');
  }
  if (response.ok) return response;

  const body: unknown = await response.json().catch(() => null);
  if (response.status === 401) {
    if (path === '/api/auth/login') {
      throw new ApiError(401, 'AUTHENTICATION_FAILED', '用户名或密码不正确，或账号不可用');
    }
    throw new ApiError(401, 'UNAUTHENTICATED', '登录已失效，请重新登录');
  }
  if (path === '/api/auth/login' && response.status === 429) {
    throw new ApiError(429, 'LOGIN_THROTTLED', '登录尝试过于频繁，请稍后再试');
  }
  if (path === '/api/auth/login' && response.status === 503) {
    throw new ApiError(503, 'AUTHENTICATION_UNAVAILABLE', '登录服务暂不可用，请稍后重试');
  }
  if (response.status === 403) {
    if (isRecord(body) && body.code === 'CSRF_INVALID') {
      throw new ApiError(403, 'CSRF_INVALID', '安全校验已失效，请重试');
    }
    if (isRecord(body) && body.code === 'ACCESS_DENIED') {
      throw new ApiError(403, 'ACCESS_DENIED', '无权访问该资源，请联系管理员');
    }
    throw new ApiError(403, 'FORBIDDEN', '请求被拒绝，请重试或联系管理员');
  }
  throw new ApiError(response.status, 'HTTP_ERROR', '后端请求失败（HTTP ' + response.status + '）');
}

async function readJson(response: Response): Promise<unknown> {
  try { return await response.json(); }
  catch { throw new ApiError(response.status, 'INVALID_RESPONSE', '后端返回格式不正确'); }
}

function invalidResponse(): never {
  throw new ApiError(200, 'INVALID_RESPONSE', '后端返回格式不正确');
}

export async function fetchHello(signal?: AbortSignal): Promise<HelloResponse> {
  const data = await readJson(await request('/api/hello', { signal }));
  if (!isRecord(data) || typeof data.message !== 'string' || typeof data.application !== 'string') {
    return invalidResponse();
  }
  return { message: data.message, application: data.application };
}

export async function fetchCurrentUser(signal?: AbortSignal): Promise<CurrentUser> {
  const data = await readJson(await request('/api/auth/me', { signal }));
  if (!isRecord(data) || typeof data.id !== 'string' || !data.id ||
      typeof data.username !== 'string' || !data.username ||
      typeof data.displayName !== 'string' || !data.displayName) return invalidResponse();
  return { id: data.id, username: data.username, displayName: data.displayName };
}

export async function fetchCsrf(signal?: AbortSignal): Promise<CsrfToken> {
  const data = await readJson(await request('/api/auth/csrf', { signal }));
  // Never accept arbitrary response-controlled header names.
  if (!isRecord(data) || data.headerName !== 'X-CSRF-TOKEN' ||
      typeof data.token !== 'string' || !data.token) return invalidResponse();
  return { headerName: data.headerName, token: data.token };
}

export async function login(credentials: Credentials, csrf: CsrfToken, signal?: AbortSignal): Promise<void> {
  await request('/api/auth/login', {
    method: 'POST', signal,
    headers: { [csrf.headerName]: csrf.token },
    body: new URLSearchParams({ username: credentials.username, password: credentials.password }),
  });
}

export async function logout(csrf: CsrfToken, signal?: AbortSignal): Promise<void> {
  await request('/api/auth/logout', {
    method: 'POST', signal, headers: { [csrf.headerName]: csrf.token },
  });
}

export const sessionApi = { fetchCurrentUser, fetchCsrf, fetchHello, login, logout };
export type SessionApi = typeof sessionApi;
