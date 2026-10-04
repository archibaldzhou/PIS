# T39 本地合成接口 API

所有接口沿用会话、CSRF、主体/医院/动作幂等域和当前授权，默认开发功能关闭。没有对外发送或调用医院接口。原型 UI-032 仅用于布局参考，业务按开发 PRD。

- `GET /api/requests/{request}/adapters?page=1`：同权限病例队列，来源、患者、病例稳定 ID；各适配能力，不返回任意正文。只返回最多20项，page 1..50。
- `POST /api/requests/{request}/adapters`：显式 `confirmedCaseId,patientId,sourceId,adapter,schema,externalId,sequence,code,materialId,amountMinor,note`。schema=SYN-HOSPITAL-1；adapter HIS/BILLING/DEVICE；DEVICE 必须本病例材料ID，BILLING 必须整数金额，其余路径对应字段必须 null。返回 `IdempotentCommands.Result`，重放信号在 `replayed`，原资源回执不变。来源ID同输入即使新键也不重复建业务，异参409。
- `POST /api/requests/{request}/adapters/{id}/{action}`：`confirmedCaseId,sourceId,payloadHash,expectedVersion,attemptId,reason,confirmed=true`。action CLAIM/RECEIVE/ACK/RECONCILE/TIMEOUT/FAIL/POISON/CANCEL/REPAIR。CLAIM 输入 attemptId=null，其他操作绑定当前尝试；同一键/全部同参返回原回执，当前授权仍需有效。原回执是历史操作结果，不表示当前状态；界面成功后重新读取。
- `GET /api/requests/{request}/adapters/{id}/history`：最多100项追加历史（版本上限99），actor/time/reason/state/errorCode。
- `/api/requests/reports/cases/{case}/adapters/emr/v1`：T21 `/deliveries` 的版本化本地别名，GET、POST、`/{delivery}/{action}`、`/{delivery}/history` 使用完全相同的报告资格、DTO、幂等域与数据。只允许 LOCAL_SIM。未知厂商与 HL7/FHIR NOT_CONFIGURED。

状态：QUEUED → ATTEMPTING →（持久化 RECEIVE 后仍 ATTEMPTING）→ ACKED → RECONCILED。失败进入 RETRY_WAIT 或 DEAD；顺序不符进入 REJECTED；人工取消进入 CANCELLED。UI 显示本地记录事实与 ACK/对账分别存在，不能把这些状态理解为真实医院/设备签收或支付。重试受30秒租约、最多3次、5/20秒退避控制。REPAIR 不改 payload、不重置尝试数。

错误：400 DTO/字段白名单失败；401 未登录；403 CSRF或基础工作流禁止；404 对象/当前资格不可用（不暴露越权对象）；409 身份、版本、顺序、尝试、ACK证据或幂等冲突；413 HTTP输入超限。沿用 COMMAND_BUSY/TIMEOUT：原键确认，不盲目更换请求键。意外错误不是零项队列或成功。日志不记录正文、凭据或患者信息。
