export interface HelloResponse { message: string; application: string }
export interface CurrentUser { id: string; username: string; displayName: string; administration?: boolean; passwordChangeRequired?: boolean }
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
    PASSWORD_POLICY: '密码须为16–72个UTF-8字节。', PASSWORD_REUSE: '新密码不能与当前密码相同。',
    PASSWORD_CHANGE_REJECTED: '当前密码不正确，或新密码与当前密码相同。', ACCOUNT_NOT_MANAGED: '请联系管理员将此账号纳入后台管理。',
    ADMIN_AUDIT_UNAVAILABLE: '管理审计暂不可用，本次操作已拒绝。',
    ADMIN_VERSION_CONFLICT: '配置已被其他人修改，请关闭编辑并刷新后重新核对。', ADMIN_SELF_CHANGE: '不能在后台修改当前登录账号；请由另一位管理员配置。',
    ADMIN_PERMISSION_INVALID: '请核对范围、角色、权限依赖和截止时间。', QUALIFICATION_REQUIRED: '专业权限需要核验资格；复核与签署还需要诊断权限。',
    DEFAULT_SCOPE_REQUIRED: '默认范围必须属于该用户的工作范围。', ACCOUNT_ALREADY_EXISTS: '登录名或本院工号已存在。',
    SCOPE_ALREADY_EXISTS: '相同组织组合的工作范围已存在。', ORGANIZATION_ALREADY_EXISTS: '组织编码已存在。', ADMIN_INPUT_INVALID: '姓名和工号不能包含首尾空格。',
    ADAPTER_LIMIT: '此申请合成接口消息达到上限。', ADAPTER_NOT_FOUND: '接口资源或当前资格不可用。', ADAPTER_VERSION: '消息版本已变化，请刷新核对。', ADAPTER_NOT_READY: '状态、顺序或退避时间不允许操作。', ADAPTER_IDENTITY: '来源或病例身份、摘要不匹配。', ADAPTER_SCHEMA: '字段与适配器路径不匹配。', ADAPTER_SIZE: '合成消息超过大小上限。', ADAPTER_DUPLICATE_CONFLICT: '来源关联ID已有不同输入。', ADAPTER_ACK_MISMATCH: '缺少匹配的接收证据，不能确认ACK。', ADAPTER_STALE_ATTEMPT: '尝试已过期、取消或被替换。', ADAPTER_PAGE: '接口查询页超出范围。',
    AI_IMPACT_CHANGED: '失效影响快照已变化，请保留理由并重新核验。', AI_IMPACT_CONFLICT: '复核版本已变化，请重新核对原操作。', AI_REFERENCE_INVALIDATED: '此引用已失效或不是当前报告修订，只能授权追溯。',
    AI_DECISION_NOT_FOUND: '人工决定或当前权限不可用。', AI_DECISION_BINDING: '人工决定与病例或结果不匹配。', AI_DECISION_CONFLICT: '人工决定或目标报告版本已变化，请重新核验。', AI_DECISION_ALREADY_ACCEPTED: '此结果已有明确采纳记录，请查看原记录。', AI_DECISION_PAGE: '人工历史页码超出范围。',
    AI_RESULT_INVALIDATED: '合成结果资格或依据版本已失效，叠加不可用。', AI_RESULT_NOT_FOUND: '合成结果或当前权限不可用。', AI_RESULT_NOT_READY: '合成结果尚未完成不可变存储，不可显示。', AI_RESULT_EXISTS: '该任务已有结果，请使用原幂等请求或已知结果ID。', AI_RESULT_CORRUPT: '合成结果包损坏，禁止显示。', AI_RESULT_BINDING: '合成结果来源不匹配。', AI_RESULT_CONFLICT: '结果版本冲突，请原键核对。', AI_RESULT_TILE: '合成瓦片坐标无效。', AI_RESULT_GENERATOR_CHANGED: '运行时或生成器已改变，不能重新生成旧版。',
    AI_WORKER_DISABLED: '合成契约worker未启用；临床执行仍被禁止。', AI_TASK_NOT_FOUND: '合成任务或当前资格不可用。', AI_TASK_CONFLICT: '任务版本、租约或状态已变化，请保留原请求并重新核对。', AI_WORKER_CAPACITY: '合成worker有效租约已达开发并发上限。', AI_CALLBACK_SCHEMA: '回调来源或schema不符合合成契约。', AI_ARTIFACT_NOT_READY: '技术产物未就绪，请核对失败或超时状态。', AI_TASK_ACTION: '不支持该任务操作。', AI_TASK_PAGE: '任务查询页超出开发上限。',
    AI_NOT_FOUND: 'AI契约或当前资格不可用。', AI_CONFLICT: 'AI依据版本已变化，请重新核对；旧判定不是执行许可。', AI_SCHEMA: '模型字段不符合合成契约schema。', AI_STATE: '不支持该状态，不能临床批准。', AI_PROFILE_REQUIRED: '缺少当前扫描适用资料。', AI_PAGE: 'AI查询范围越界。',
    ROI_CONFLICT: 'ROI集合或修订已变化，请保留输入并重新核对。', ROI_BINDING: 'ROI不属于当前精确图像清单。', ROI_NOT_FOUND: 'ROI资源或作者权限不可用。', ROI_CALIBRATION: '校准版本无效或已变化，不能沿用旧测量。', ROI_GEOMETRY: 'ROI数量、有限值、边界或自交校验失败。', ROI_LIMIT: 'ROI数量或修订达到开发上限。',
    VIEWER_NOT_PREPARED: '合成瓦片尚未生成，请显式生成。', VIEWER_UNSUPPORTED: '仅支持合成RGB；文件头或真实WSI格式不可显示。', VIEWER_BINDING: '瓦片清单与精确扫描不一致。', VIEWER_COORDINATE: '瓦片坐标越界。', VIEWER_TILE_MISSING: '瓦片缺失，不能将空白解释为阴性。', VIEWER_SIZE: '合成RGB尺寸或长度越界。', VIEWER_CORRUPT: '合成RGB文件损坏。', VIEWER_BUSY: '生成器繁忙，请显式重试。', VIEWER_RATE: '阅片请求达到有界配额。', DIGITAL_QC_NOT_FOUND: '数字QC专业资格或资源不可用。', DIGITAL_QC_NOT_READY: '精确版本QC未就绪、已撤销或来源变化，请刷新核对。', DIGITAL_QC_SOURCE_CHANGED: '扫描来源已变化，不能沿用旧评价。', DIGITAL_QC_BINDING: '评价与确切扫描原件不匹配。', DIGITAL_QC_REGION: '缺陷区域必须位于当前合成尺寸内。', DIGITAL_QC_STATE: '当前QC状态不支持该操作。', DIGITAL_QC_LIMIT: '该版本已达到合成评价历史上限。', SCAN_NOT_FOUND: '导入病例、对象或扫描资格不可用。', SCAN_DUPLICATE: '该原件已有导入任务，请查询原任务。', SCAN_STATE: '任务状态已变化，请刷新。', SCAN_LEASE_STALE: '尝试已取消、过期或代次不符，结果未激活。', SCAN_SOURCE_CHANGED: '材料或QC依据已变化，不能继续此尝试。', SCAN_RETRY_BLOCKED: '尚未到重试时间、次数已尽或输入已失效。', SCAN_RESCAN_BINDING: '重扫必须引用确切旧任务及新的原件版本。', SCAN_OBJECT_NOT_READY: '原件尚未完成校验。', SCAN_LIMIT: '开发导入任务达到上限。',

    STORAGE_NOT_FOUND: '原件或当前病例资格不可用。', STORAGE_NOT_CONFIGURED: '私有存储尚未配置。', STORAGE_ISOLATED: '当前病例状态或身份隔离阻断存储。', STORAGE_QUOTA: '合成存储配额不足。', STORAGE_INTEGRITY: '字节长度、合成标记或摘要不匹配，未发布原件。', STORAGE_STATE: '存储状态已变化，请核对确切版本。', STORAGE_RATE: '读取达到开发速率或字节上限。', STORAGE_RANGE: '仅支持不超过1MiB的明确单段Range。', STORAGE_BUSY: '存储处理繁忙，请核对状态后重试。', STORAGE_IO: '原件不可读取，未绕过校验。',

    CONSULT_NOT_FOUND: '当前会诊、病例或限时参与资格不可用。', CONSULT_INPUT_CHANGED: '报告、材料QC、分配或资格已改变，请重新发起会诊。', CONSULT_NOT_READY: '尚未取得全体对确切汇总的明确确认。', CONSULT_SUMMARY_CHANGED: '汇总或个人意见已改变，请重新核对。', CONSULT_OPINIONS_CHANGED: '参与状态或意见版本尚不完整或已变化。', CONSULT_STATE: '会诊已终止或状态不适用。', CONSULT_MEMBER_STATE: '参与状态已改变，请重新读取。', CONSULT_EXPIRY: '有效期必须在未来30天内。', CONSULT_PARTICIPANTS: '请核对1至10名当前合格人员，不含本人。', CONSULT_TEXT_REQUIRED: '必须明确输入人工合成文本。', CONSULT_DISPOSITION: '请选择明确的意见或分歧状态。', CONSULT_KIND: '请选择院内会诊或复阅。', CONSULT_LIMIT: '已达开发会诊数量上限。', CONSULT_PAGE: '会诊历史页号不正确。',
    STAIN_NOT_FOUND: '染色病例、批次或当前资格不可用。', STAIN_PAGE: '染色历史页号不正确。', STAIN_STATE: '批次状态已变化，失败或撤销批次不能重新放行。', STAIN_FROZEN: '批次成员已冻结，不能追加或再次冻结。', STAIN_SCHEME: '项目或方案版本不合法，或同版本元数据不一致。', STAIN_EXPIRED: '试剂已过有效期，或有效期不合法。', STAIN_QUANTITY: '来源重复、已消耗或数量超过批次边界。', STAIN_LIMIT: '批次或材料达到开发数量限制。', STAIN_SOURCE: '来源玻片及关联材料QC未就绪或不属于当前病例。', STAIN_SOURCE_STALE: '冻结来源依赖已变化，必须核对并以新来源重跑。', STAIN_TEXT_REQUIRED: '请填写明确的人工合成记录，空白不是阴性。', STAIN_BINDING: '冻结版本或对照事件不匹配，请刷新核对。', STAIN_CONTROL_REQUIRED: '本批对照尚未通过或已失效，不能提交有效结果。', STAIN_RESULT_EXISTS: '此申请已有不可变结果；重跑需新批次与新来源。',
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
    ARCHIVE_NOT_FOUND: '档案或本范围有效资格不可用', ARCHIVE_INPUT_INVALID: '请核对必填人工记录、期限和范围', ARCHIVE_IDENTITY_MISMATCH: '档案身份或位置核对不一致', ARCHIVE_BARCODE_MISMATCH: '条码不匹配，未操作该物品', ARCHIVE_LOCATION_OCCUPIED: '目标库位已占用或重复', ARCHIVE_ITEM_OCCUPIED: '该物品已预约或在借', ARCHIVE_ALREADY_REGISTERED: '来源已归档，请查看原档案项', ARCHIVE_LIMIT: '达到开发台账数量限制', ARCHIVE_STATE: '保管或借阅状态已变化，请刷新核对', ARCHIVE_SEPARATION: '申请人与审批人须为不同合格人员', ARCHIVE_OUTSTANDING: '仍有在借项，不能释放预约并结束', ARCHIVE_SOURCE_UNSUITABLE: '材料身份、QC或保管异常阻断借出', ARCHIVE_SNAPSHOT_STALE: '盘点依据已失效，请重新核对快照',
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
  return { id: data.id, username: data.username, displayName: data.displayName,
    ...(typeof data.administration === 'boolean' ? { administration: data.administration } : {}),
    ...(typeof data.passwordChangeRequired === 'boolean' ? { passwordChangeRequired: data.passwordChangeRequired } : {}) };
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
