# T25 本地交付记录：院内合成会诊、复阅与分歧处理

2026-10-03；起点 `2ef0a9c81eaf44bbd2b93ea182eafb2908ff4d7f`，同一 `/workspace/PIS`、`validation/t15-20261002`。保留 T15–T24 全部本地历史，本次中文本地提交，不登录或等待 GitHub、不处理凭据、不 push、不部署。

先读取根 AGENTS、相关工程约束及报告/诊断实现，编写[开发PRD](../prd/development-consultation-v1.md)，实际查看授权 UI-021 科内会诊与讨论图片；附件ZIP SHA256复核为 `74f3305da4278f84c7660eb13625c1ff604174116d2fe13fbeffe85cf7a58291`。沿用原型的目的、参与者、材料版本、个人意见与人工汇总；原型不能替代医院批准。架构见[ADR0015](../adr/0015-case-scoped-consultation.md)。

## 实际实现

- V23 增量迁移：`report_consultation`、`consultation_member`、不可变 `consultation_event` 与最小拒绝审计。固定病例、请求/医院/组织、目的类型 CONSULT/REREAD、草稿修订、模板对应关系、分配版本、材料/QC依赖快照及明确期限；复合FK及不可变身份/历史保留来源。旧迁移与旧报告/PDF不改。
- 当前已领取且合格的医生可明确邀请1–10名同组织作用域合格医生，最多30天的开发期限。此上限不是医院制度。邀请只写具体会诊成员，不新增全局范围或角色；原医生资格、账户及工作流组织授权仍必须有效。管理员无自动资格。
- 接受、拒绝、撤回、参与授权撤销、退回均有原因和追加事件。邀请到期、拒绝、撤销或会诊终止后参与者不能通过会诊API继续读写或重放受限内容。当前领取医生可依已有病例权限查询历史；转派后原医生不会因曾发起会诊而获得永久权限。
- 个人意见只能由接受邀请的本人追加，AGREE/DISAGREE/UNKNOWN是意见状态，不是诊断分类。旧意见始终保留。人工汇总绑定所有成员状态及确切最新意见ID，并明确UNRESOLVED/RESOLVED；不计票、不生成诊断。
- RESOLVED只是人工汇总，必须所有参与者明确确认同一汇总才可就绪。个人意见更新、参与者拒绝/撤销或资格变化使旧汇总失效。界面单独展示最新个人意见与当前汇总，不依赖历史分页恰好包含它们；不悄悄抹掉原有分歧。
- 报告修订、模板对应修订、分配版本、材料/QC、当前医生或参与者资格变化后显示失效原因；已模拟冻结报告不能当成可编辑草稿。失效会诊保留历史，须重新发起；撤回/撤销/退回不会解除身份或QC门禁。
- 采纳是明确非签署操作：当前医生人工输入追加说明，经T17共用草稿追加校验，新建修订并追加到notes。所有原字段和原notes保留，记录确切会诊/汇总/意见来源；不自动改诊断、不换模板、不复核/签署/发送。T18冻结及T20更正门禁保持有效。
- 申请根锁、会诊CAS、资格锁定及提交时重读；意见/汇总/采纳、报告新修订、审计和幂等回执同事务。并行旧版本写入只有一个CAS成功，其他请求明确冲突，重新核对后才能再提交。相同键重试重新授权，不重复采纳。
- 前端绑定病例/会诊/报告版本；切病例或动作提示脏输入，取消保留、确认清空不适用字段；迟到响应丢弃。双击互斥，未知写入结果冻结原意图与幂等键，冲突保留输入供人工检查。合成开关默认关闭，无真实消息、人员联系或外部共享。

## API 契约

`GET /api/requests/consultations/cases/{caseId}?consultation={可选ID}&page=1`：明确授权和读审计，最多100个可访问会诊；确切绑定草稿/材料、最多10个参与者及最新意见、当前汇总、历史20条/页（页号1–10000）。无邀请且不是当前领取医生时不返回会诊内容。

`POST /api/requests/consultations/cases/{caseId}/{ACTION}?consultation={既有ID}`：CSRF、Idempotency-Key；CREATE / ACCEPT / REJECT / WITHDRAW / REVOKE / RETURN / OPINION / SUMMARY / CONFIRM / ADOPT。共同输入confirmedCaseId、expectedVersion（创建-1）、reason；CREATE绑定revisionId、assignmentVersion、kind、purpose、expiresAt（含时区）及invitees。个人意见需明确disposition和content；汇总需当前所有opinionIds及人工分歧状态；确认/采纳绑定summaryId；撤销绑定targetId。actor由服务器会话决定。

回执 `REPORT_CONSULTATION / ID / 新版本`，200不代表临床结论或签署。字段错误400；对象/资格/邀请不可用404；状态、版本、依据、意见/汇总冲突409；合成开关关闭503。未知或未解决意见不默认成功，原键回执不证明当前意见仍有效。

## 本次实际验证与边界

目录 `/workspace/PIS`；现有 Node22.23.3/npm11.21.0、Java21和固定digest PG17，未创建或切换执行环境。PG仅临时网络禁用的合成测试容器，已清理。

- `npm --prefix frontend run lint`、`test`、`build`：通过，19文件/120项单测；包含TypeScript检查。构建仍有约1.24MB大chunk警告，未降低阈值。
- `npm --prefix frontend run audit:dependencies`：0漏洞，未新增依赖。
- Chromium mock UI：会诊3项定向检查通过；完整套件44项通过；收尾角色按钮/会话过期提示调整后3项会诊定向复测通过。桌面/390px实际截图已查看，无整页水平溢出。覆盖脏输入、取消、病例切换、迟到响应、撤权错误、双击与原键重试；不等同真实E2E。
- `python3 backend/src/test/probes/consultation-postgres.py`：真实PG V1–V23 SQL、生产会诊INSERT/汇总依据SQL、批量资格快照与撤权过滤、观察到根锁竞争的意见CAS、明确汇总绑定、历史不可变、采纳审计故障回滚和原报告保留通过。探针不能证明Spring服务整体授权/代理行为。
- `python3 backend/src/test/probes/staining-postgres.py`：随全部迁移加载的T24来源转移、结果/撤销竞争及审计回滚回归通过。
- 实际编译并执行生产 `ConsultationPolicy`：期限上界、过去/当前/缺失期限拒绝、UNKNOWN/DISAGREE不等于RESOLVED通过，4项非法期限拒绝。全Java源码语法解析通过；不是项目类型编译或JUnit通过。12域源码引用无环检查通过，不是字节码分析。

新增9项 `RequestWorkflowTest` 会诊场景、策略及V22→V23迁移测试；新增真实双角色 `frontend/e2e/consultation.spec.ts` 与独立合成就诊。涵盖明确确认、追加采纳、重复重试、个人意见失效、越权、到期/撤权、草稿/QC/分配变化、并发意见、采纳审计回滚及终止历史。**完整后端JUnit/HTTP/Flyway集成和真实E2E尚未执行通过。**

本次离线Maven verify在POM解析阶段失败，尚未进入编译：Boot4.1.1导入的Zipkin Reporter3.5.3、Brave6.3.1、Cassandra4.19.3、gRPC1.83.1等BOM本地缺失。未修改依赖、尝试凭据或绕过已有下载拒绝。完整后端编译/测试及真实Spring/PG E2E因此未验证；CI未连接或等待。

医院正式会诊/复阅资格、目的授权及留存制度仍需批准。本实现仅人工合成开发，不使用真实患者/AI，不发送邀请、不院外共享、不执行临床/CA签署或部署；局部本地通过不构成可合并或可投产结论。
