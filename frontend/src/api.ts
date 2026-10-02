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

export async function request(path: string, options: RequestInit = {}): Promise<Response> {
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
  const workflowErrors: Record<string, string> = {
    QC_NOT_FOUND: '没有此材料QC权限或材料不存在', QC_QUARANTINED: '当前材料或来源已隔离，操作被阻断', QC_IDENTITY_LOCKED: '身份错误持续阻断，本版不支持解除', QC_IDENTITY_MISMATCH: '核对材料身份不一致，未提交QC', QC_EXCEPTION_RELEASE_DISABLED: '异常放行未批准，服务端已禁用', QC_STANDARD_UNSUPPORTED: '不支持该QC标准版本', QC_SOURCE_INVALID: '材料或技术来源已失效', QC_REWORK_NEW_MATERIAL_REQUIRED: '返工必须新建材料并独立质检，原材料保持隔离', QC_REWORK_UNSUPPORTED: '当前状态或直制路径不支持盒级返工', QC_NOT_ASSESSED: '尚未登记QC判定', QC_STATE_CONFLICT: 'QC状态已变化，请刷新核对', QC_LIMIT_REACHED: '质检历史达到开发限制',
    RECEPTION_STATE_CONFLICT: '当前状态不允许此操作，请刷新', RECEPTION_ALREADY_LINKED: '已有关联病例或接收记录，需要人工复核',
    RECEPTION_CONTAINER_CONFLICT: '容器清单已变化，接收已回滚', IDENTITY_OR_QUANTITY_REVIEW_REQUIRED: '身份或数量异常不能自行修正并恢复',
    LABEL_NOT_FOUND: '标签资源不存在或无权操作', LABEL_IDENTITY_MISMATCH: '条码校验失败或不属于此容器，已阻断', LABEL_REQUIRES_RECEIVED: '仅已接收容器可创建标签',
    LABEL_USE_REPRINT: '已有标签身份，请选择原任务重打', LABEL_STATE_CONFLICT: '任务状态不允许此操作，请刷新',
    MATERIAL_NOT_FOUND: '材料不存在或未获授权', MATERIAL_SOURCE_NOT_READY: '需要先完成来源接收核对', MATERIAL_TASK_NOT_READY: '所选技术任务尚未完成合成演练', MATERIAL_SOURCE_MISMATCH: '材料或来源身份不一致，请核对', MATERIAL_INACTIVE: '材料或源蜡块已作废，操作被阻断', MATERIAL_BLOCK_EXISTS: '该包埋任务已有蜡块身份', MATERIAL_LIMIT_REACHED: '材料数达到开发限制', MATERIAL_ROUTE_UNSUPPORTED: '当前材料路径不支持此操作',
  TECH_NOT_FOUND: '当前技术任务不可用或未授权', TECH_SOURCE_NOT_READY: '取材来源尚未完成，不能进入技术队列', TECH_SOURCE_MISMATCH: '取材盒身份不一致，请核对', TECH_PREDECESSOR_NOT_READY: '前置任务未完成合成演练或来源不同', TECH_OWNER_REQUIRED: '仅当前持有人可执行此操作', TECH_STATE_CONFLICT: '技术任务状态已变化，请刷新核对', TECH_SELF_HANDOFF: '需要另一位授权用户确认交接', TECH_REWORK_EXISTS: '已存在关联返工任务，请核对历史', TECH_LIMIT_REACHED: '技术任务数量达到开发限制',
  GROSS_NOT_FOUND: '取材资源不存在或无权操作', GROSS_REQUIRES_RECEIVED: '病例和容器必须先完成接收', GROSS_ALREADY_EXISTS: '已有取材记录，请刷新',
    GROSS_SOURCE_MISMATCH: '来源容器不属于当前已接收病例或有重复，已阻断', GROSS_STATE_CONFLICT: '当前取材状态不允许此操作', GROSS_TARGET_MISMATCH: '取材盒或图像不属于此记录或已失效',
    GROSS_INCOMPLETE: '完成取材前须填写描述并保留至少一个取材盒', GROSS_LIMIT_REACHED: '开发记录数量已达到上限', GROSS_PHOTO_TOO_LARGE: '合成图像请求超过大小上限', GROSS_PHOTO_REJECTED: '仅接受随包合成PNG样本', GROSS_PHOTO_WITHDRAWN: '图像已撤回或记录已取消，禁止下载',
    WORKFLOW_DISABLED: '开发工作流未启用', VERSION_CONFLICT: '版本已变化，请刷新并复核',
    REQUEST_NOT_DRAFT: '当前申请不是可编辑草稿', REQUEST_INCOMPLETE: '请补齐病史、采样和各容器固定信息',
    DUPLICATE_REVIEW_REQUIRED: '同一就诊已有提交申请，需要人工复核，本版不允许绕过',
    ENCOUNTER_MISMATCH: '就诊与授权工作范围不匹配', CONTAINER_SET_IMMUTABLE: '已登记容器清单不能增删条目',
    REQUEST_NOT_FOUND: '申请不存在或不可见', IDEMPOTENCY_KEY_REUSED: '请求键对应另一份输入，请核对原操作',
    COMMAND_BUSY: '原操作仍需确认，请保留原请求重试', COMMAND_TIMEOUT: '操作超时，请保留原请求确认结果',
  };
  if (isRecord(body) && typeof body.code === 'string' && Object.hasOwn(workflowErrors, body.code)) {
    throw new ApiError(response.status, body.code, workflowErrors[body.code]);
  }
  throw new ApiError(response.status, 'HTTP_ERROR', '后端请求失败（HTTP ' + response.status + '）');
}

export async function readJson(response: Response): Promise<unknown> {
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
