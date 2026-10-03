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
    CYTOLOGY_NOT_FOUND: '细胞学来源、制备或当前资格不可用。', CYTOLOGY_PAGE: '细胞学历史分页不正确。', CYTOLOGY_STATE: '细胞学状态已变化，请刷新。', CYTOLOGY_LEGACY_SOURCE: '容器已有旧材料，不能推测剩余数量建立新台账。', CYTOLOGY_LEDGER_REQUIRED: '已登记细胞学台账，必须通过独立制备核对消耗。', CYTOLOGY_NOT_READY: '来源QC、制备路径或人工元数据尚未就绪。', CYTOLOGY_SOURCE_STALE: '来源QC代次已变化，原制备不能继续产出。', CYTOLOGY_QUANTITY: '预留数量超过剩余材料或不符合边界。', CYTOLOGY_RECONCILIATION: '转入、消耗、废弃、退回或玻片数量不相符。',
    FROZEN_NOT_FOUND: '冰冻病例、人员或当前资格不可用。', FROZEN_PAGE_INVALID: '冰冻历史分页不正确。', FROZEN_TIME_INVALID: '请核对发生时间、IANA时区及offset；不得填写未来时间。', FROZEN_TIME_ORDER: '人工时间顺序冲突，请核对原记录及后续事件。', FROZEN_IDENTITY_MISMATCH: '冰冻病例身份核对不一致。', FROZEN_ALREADY_EXISTS: '此病例已有冰冻记录，请刷新查看。', FROZEN_SOURCE_REQUIRED: '请核对来源容器及冰冻材料部位。', FROZEN_NOT_RECEIVED: '尚未登记冰冻接收。', FROZEN_STAGE_CONFLICT: '冰冻阶段或领取状态已变化，请刷新。', FROZEN_TIME_REFERENCE: '时间更正必须引用当前接收或制备事件。', FROZEN_NOT_PREPARED: '尚未登记冰冻制备。', FROZEN_NOT_READY: '冰冻制备、QC或领取尚未就绪。', FROZEN_SEPARATION_REQUIRED: '合成复核必须由不同于作者的合格人员执行。', FROZEN_QUALIFICATION_REVOKED: '相关人员当前资格已失效，需要重新核对。', FROZEN_COMMUNICATION_MISMATCH: '沟通版本或接收者不匹配，未记录回读或确认。', FROZEN_ALREADY_RECORDED: '此沟通步骤已有记录，不能覆盖。', FROZEN_READBACK_REQUIRED: '接收者尚未独立记录回读，不能记录确认。', FROZEN_LINK_REQUIRED: '请指定确切常规冻结版及人工差异比较。', FROZEN_REFERENCE_REQUIRED: '缺少确切冰冻事件引用。', FROZEN_REVIEW_INVALIDATED: '冰冻复核不存在或依赖已变化，须重新复核。', FROZEN_TEXT_REQUIRED: '请填写人工结果、证据或差异解释。',

    REPORT_OUTPUT_NOT_FROZEN: '此病例尚无合成模拟冻结版本。', REPORT_OUTPUT_NOT_FOUND: '产物或原打印请求不存在，或当前无权访问。', DELIVERY_BINDING: '投递版本、产物或目的端不匹配，请重新核对。', DELIVERY_NOT_READY: '当前投递阶段或退避时间不允许此操作。', DELIVERY_STALE_ATTEMPT: '尝试已过期或被替换，请刷新后核对。', DELIVERY_ACK_MISMATCH: '缺少匹配的本地业务接收证据，不能确认ACK。', DELIVERY_NOT_FOUND: '投递对象或当前范围不可用。', REPORT_AMENDMENT_PENDING: '已有待完成草稿或尚无冻结报告，请刷新版本链后处理。', REPORT_OUTPUT_STALE: '冻结依赖已变化，禁止新生成及打印请求；历史产物不代表当前就绪。', REPORT_PRINT_RESULT_EXISTS: '此打印请求已有用户自报结果，不能覆盖。', REPORT_OUTPUT_ACTION_INVALID: '请核对产物操作及原请求。', REPORT_RENDER_BUSY: '合成PDF渲染容量暂满，请保留原请求稍后确认。', REPORT_RENDER_LIMIT: '文本、页数或渲染资源超过合成PDF上限。', REPORT_GLYPH_UNSUPPORTED: '文本包含内置字体不支持的字符，不能生成固定PDF。', REPORT_FONT_UNAVAILABLE: '固定中文字体不可用，已阻止生成。', REPORT_RENDER_INPUT_INVALID: '冻结内容不符合固定PDF输入约束。', REPORT_RENDER_FAILED: '固定PDF生成失败。', REPORT_ARTIFACT_INTEGRITY: '产物完整性校验失败，已阻止返回。',
    REPORT_REVIEW_NOT_FOUND: '当前病例或合成复核资格不可用。', REPORT_REVIEW_DISABLED: '此范围未配置合成复核策略。', REPORT_REVIEW_NOT_READY: 'QC、身份、分配或人工草稿未就绪。', REPORT_REVIEW_STALE: '复核依赖已变化或职责分离不满足，请重新核对。', REPORT_SEPARATION_REQUIRED: '当前开发策略要求作者与复核人员不同。', REPORT_REVISION_REQUIRED: '退回后需要新增草稿修订，再重新复核。', REPORT_SIMULATED_FROZEN: '合成模拟已完成，原草稿冻结；更正不在当前范围。', REPORT_SIMULATION_CONFIRMATION: '需要明确确认合成模拟操作。', REPORT_FIELDS_INVALID: '字段不符合所选模板的类型或长度要求', REPORT_IDENTITY_MISMATCH: '报告病例核对不一致，未保存', REPORT_TEMPLATE_UNAVAILABLE: '模板确切版本不可用，请刷新核对', REPORT_PAGE_INVALID: '修订历史分页不正确',
    DIAGNOSIS_NOT_FOUND: '诊断病例或范围不可用，或未获授权', DIAGNOSIS_PAGE_INVALID: '诊断队列分页参数不正确', DIAGNOSIS_TARGET_INVALID: '该操作的目标人员参数不正确', DIAGNOSIS_TARGET_UNAVAILABLE: '目标人员当前不可用或不具备本范围有效资格', DIAGNOSIS_IDENTITY_MISMATCH: '核对的病例身份不一致，未执行操作', DIAGNOSIS_NOT_READY: '病例材料、接收或身份门禁未就绪，操作被阻断', DIAGNOSIS_STATE_CONFLICT: '当前状态或持有人已变化，请刷新核对',
    WORKLIST_NOT_FOUND: '工作范围或模块不可用，或未获授权', WORKLIST_PAGE_INVALID: '工作列表分页参数不正确', WORKLIST_DUPLICATE_ITEM: '批次包含重复任务，未执行任何项',
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
