# T30 数字扫描QC：接口与交付记录

[PRD](../prd/development-digital-qc-v1.md) / [ADR0020](../adr/0020-digital-qc-exact-publication.md)。T29已验证树e482d3e1e5587fae959dc2c641463ab5fa116326正常快进推送main；本T30独立验证，未绿前不合main。

## 接口

基础路径 `/api/requests/{requestId}/scans/{scanId}/digital-qc`，Cookie会话、CSRF、Idempotency-Key及严格DTO白名单沿用既有安全策略。

- GET：当前精确扫描身份、hash、扫描修订、QC CAS、当前/历史序列、历史状态/有效状态/失效原因、当前评价及最多100个事件，预留末尾版本供发布和撤销，达到评价上限不会阻断最后撤销。所有读取需专业资格、病例和组织/医院授权并审计。
- GET `/history`：最多100项不可变评价，单个有界查询。
- POST：Evaluation，expectedVersion首版-1，scanVersion/objectId/slideId/objectHash必须精确一致；checklist只允许SYN-DIGITAL-QC-1；覆盖/焦点/缺失各PASS/FAIL/UNKNOWN；coveragePercent 0–100、missingTiles 0–1000000、regions最多20个当前合成尺寸内整数矩形（x/y/width/height/note），note强制。缺项400，错版本409，缺陷保存但不通过。
- POST `/PUBLISH` 或 `/REVOKE`：expectedVersion、assessmentVersion、reason，发布要求最新扫描/有效身份/来源/资格和全部必需项通过，撤销独立保存原因。重试保持原键与原正文；重放旧发布回执不能重新发布。
- GET `/bytes?publicationVersion=N`：当前有效精确发布的合成对象，Range可选，所有访问受服务器授权、限流及审计。二进制安全响应头固定；X-PIS-Capability=SYNTHETIC_CONTRACT_ONLY_NO_VIEWER。错误不会返回原件字节。

状态UNASSESSED/EVALUATED/PUBLISHED/REVOKED是历史记录；effectiveState才是当前资格（ISOLATED/EVALUATED_NOT_PUBLISHED/PUBLISHED_SYNTHETIC_CONTRACT）。保留重扫、源QC变化或撤权前的历史，当前消费即时受阻。所有内容都是人工合成评价，无图像生成、AI、临床签署或外发。

## 验证证据与边界

- 实际PG17 V1–V28探针检查精确绑定、未知不通过、追加历史、发布/撤销CAS竞争与回滚；通过。
- 前端151项单测、lint、类型和构建通过。63项全套UI通过，随后5项T30收尾回归通过（含1项新增，未声称64项全套重跑）；初次新增UI遇到三个同名Select选项定位歧义，改为依据当前combobox的aria-controls关联列表，保留业务断言。
- 新增后端集成源码覆盖未知/缺陷、版本绑定、重扫、源QC撤销、资格/范围、并发发布撤销、审计回滚，以及文件读后撤销和下载审计失败；迁移基线明确28，新增27→28升级验证，旧checksum/重放/空种子断言保留。
- 新增真实E2E来源链：独立合成账号、直接制片与材料QC、T28原件、T29导入、T30未知阻断/评价/界面发布/精确Range/撤销/越权。Playwright已发现43项E2E场景，发现测试不等于执行。
- 本地Maven离线verify在模型解析阶段失败：Boot4.1.1依赖Zipkin3.5.3、Brave6.3.1等BOM未缓存；未尝试既有被拒绝的下载。完整后端编译/测试、真实E2E执行、本次完整CI尚未本地验证。Java AST检查不能替代类型编译。
- 未实现真实像素/覆盖/焦点评估、真实WSI或T31阅片器；没有凭合成头宣称厂商链路通过。开发开关默认关闭，生产安全配置未改。
