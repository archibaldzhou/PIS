# T22 本地交付记录：术中冰冻、人工时间与沟通历史

2026-10-03；起点 `8f635e9b1f038fef3bf132c5e6a01d057732745a`，同一 `/workspace/PIS`、`validation/t15-20261002` 分支。本次只本地提交，未登录 GitHub、未处理凭据、未 push、未查询 CI、未部署。

依据 [开发 PRD](../prd/development-frozen-v1.md)、[ADR 0012](../adr/0012-independent-frozen-workflow.md) 和根 AGENTS。当前环境直接读取授权附件，SHA256 `74f3305da4278f84c7660eb13625c1ff604174116d2fe13fbeffe85cf7a58291`；实际查看 UI-016 与 UI-025。原型是静态合成示例，不是医院业务批准；未声称读到原始招标 PRD 正文。

## 实际实现

- V20 新增 `frozen_grant / frozen_case / frozen_event / frozen_rejection`。冰冻申请/工作记录、材料、来源容器和所有者独立于石蜡任务及常规诊断分配。每病例一个冰冻工作记录及独立材料身份；本开发范围关联同病例的常规冻结报告，不支持跨病例合并、自动拆分或设备采集。
- `RECEIVE → PREPARE → QC_PASS → DRAFT → REVIEW` 为人工操作。独立合成资格与现有医生资格、范围授权同时成立；草稿由当前已领取负责人输入，复核者与作者分离。管理员角色不赋予临床权限。转交后须由接手人领取。身份不符终止新操作，本任务没有异常放行或解除隔离功能。
- UTC offset 与 IANA zone 必须匹配；发生时间不得未来，传输年份范围 1–9999，服务器记录时间独立保存。时间先后按接收/制备/QC/修订/复核/沟通/回读/确认检查。接收、制备时间可追加更正（精确引用当前事件，强制原因）；迟补不改写服务器记录时间。间隔仅显示人工接收与制备之差，没有医院 TAT 或自动动作。
- 草稿是不可变 DRAFT 事件，事件 UUID 就是确切修订 ID。复核摘要含当前修订、负责人、时间、QC、申请版本、医生和冰冻资格代次、来源 QC 依赖。更正/新草稿/QC/转交清除当前复核指针；资格撤回后恢复也不能恢复旧复核有效性。旧事件仍可读。复核提交还必须携带读取时的 reviewToken，与提交时依赖摘要匹配，防止把尚未见到的新来源QC静默绑定进复核。
- 沟通方式固定为 `LOCAL_SIMULATION`；当前合格记录者选择同范围合格合成账号作为接收者。`COMMUNICATE / READBACK / CONFIRM` 分别追加；接收者本人分别输入回读及确认证据，确认前必须已有回读，均绑定确切沟通及人工修订/复核。没有真实送达字段或外部发送动作。旧修订、失效复核或非接收者会话不能追加有效确认。
- 常规关联通过已有公开快照授权接口读取确切模拟冻结版（新增只读 `frozenAt` 字段用于时间先后），保存常规修订、模板、模拟冻结 ID、冰冻修订和复核、人工一致/不一致状态及解释。数据库复合 FK 约束版本归属；双方原文、旧 PDF、哈希和历史不改写。历史关联不自动升级。
- 申请根锁、CAS、T07 幂等和成功审计同事务。表头版本必须有对应事件；事件、失败审计追加不可变。读取病例列表及详情审计；已授权对象的业务拒绝用独立事务保存最小代码/主体/资源/trace，不写输入正文。越权/未知对象统一不可用。
- React 工作站从已接收申请详情进入；阶段/病例/历史切换处理脏输入，取消保留输入，迟到读取丢弃，未知写结果冻结原输入/原键重试。手机宽度的长 UUID 按行换行，历史表在自身区域横向滚动。

## API 契约

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/api/requests/{request}/frozen-cases` | 授权病例身份列表，上限 100，读取审计 |
| GET | `/api/requests/frozen/cases/{case}?page=1` | 当前头、有效复核、人工时间间隔、来源/合格人员及历史；每页 20，上限第 10000 页 |
| POST | `/api/requests/frozen/cases/{case}/{ACTION}` | 必须 CSRF 与 Idempotency-Key；动作见下文；回执绑定病例与新 CAS 版本 |

动作：`RECEIVE, PREPARE, CORRECT_TIME, QC_PASS, QC_FAIL, IDENTITY_MISMATCH, DRAFT, REVIEW, TRANSFER, CLAIM, COMMUNICATE, READBACK, CONFIRM, LINK_ROUTINE`。

每次命令携带 confirmedCaseId、expectedVersion（未登记为 -1）、occurredAt（带 offset）、zoneId、强制 reason、人工 content；按动作使用 containerId/site、resultId、relatedId、targetUserId、routineSignatureId/comparison、reviewToken（复核必需）。身份由服务端会话取得，不接受伪造 actor 或 adminOverride。时间/字段错误 400，越权对象 404，陈旧 CAS/阶段/隔离/依赖冲突 409；禁用开关沿用 503。未知请求结果重用原输入及幂等键，不能因取消 UI 推断服务器撤销。

## 已实际执行的本地检查

当前工具链 Node 22.23.3 / npm 11.21.0，沿用冻结依赖；PG17 镜像固定摘要，临时容器 `--network none`，仅合成数据，已清理自己的容器。

| 检查 | 结果与准确边界 |
| --- | --- |
| `npm --prefix frontend run lint` | 通过，未关闭规则 |
| `npm --prefix frontend run build` | TypeScript strict 与 Vite 通过；保留既有大 chunk 提示（约 1.19 MB），不表示性能验收 |
| `npm --prefix frontend run test` | 99 项、16 文件通过 |
| `PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui` | 33 项通过；HTTP mock UI 回归，不是真实服务 E2E |
| `npm --prefix frontend run audit:dependencies` | 0 vulnerabilities，仅 npm 范围 |
| 实际 PG17 `/tmp/pis-t22-probe.py` | V1–V20 SQL、V19→V20 旧报告/PDF/审计不变、复合 FK/追加不可变/重复回读、资格代次、实际生产 CAS SQL 竞争一胜一零行、审计故障回滚及真实查询语句通过；不是 Spring/JDBC/Flyway 集成测试 |
| 独立 JDK 编译并运行 `FrozenPolicy` | 时区 offset/DST、未来时间、相等/逆序时间及摘要变化检查通过；仅该生产纯 Java 类，不是完整后端编译 |
| Java 源码语法、diff/链接/祖先检查 | 使用当前解析器进行语法及本地静态检查；不替代 Java 类型检查或完整架构测试 |

初轮新增 UI 回归发现手机病例 UUID 按钮溢出，修复换行后定向回归通过，随后完整 33 项通过。最后增加结构化阶段/沟通方式和复核依赖令牌后，冰冻定向 UI 回归再次通过。最终桌面与手机截图已实际查看；动画中的截屏另作定向重拍。临时日志位于 `/tmp/pis-t22-{unit,ui-full,build,lint,audit,pg,maven}.log`；截图位于 `frontend/test-results/frozen-*.png`，均非临床资料。

## 测试源码与未验证项

补充 `FrozenPolicyTest`、`FrozenMigrationTest`、`RequestWorkflowTest` 中独立冰冻、职责/接收者分离、资格/QC/转交/新修订失效、时间更正、常规差异绑定、并发草稿及复核竞争、审计回滚、HTTP CSRF/字段白名单/越权/重放撤权测试；十二域源码引用检查与迁移版本断言同步。新增 `frontend/e2e/frozen.spec.ts` 为真实本地 Spring/PG 跨角色流程源码，无 HTTP mock，不调用外部端点；仅测试 classpath 增加合成资格与就诊种子。

**完整后端编译、完整 JUnit/PG/HTTP/Flyway 测试尚未运行通过。** 本次执行离线 Maven verify 在 POM 解析阶段失败：本地缺少 Boot 4.1.1 导入的 Zipkin Reporter 3.5.3、Brave 6.3.1、gRPC 1.83.1 等 BOM，尚未进入完整 Java 编译。未改依赖或绕过先前下载拒绝。

**真实 Spring E2E 未运行，CI 未验证。** 前者依赖上述后端构建及完整测试入口；后者按用户要求本次不等待、不连接 GitHub。没有生产性能、医院流程/资格制度或临床验证；不将局部本地通过称为可合并或可投产。开发合成模式仍默认关闭，无真实患者、设备、外发、CA 或临床签署。
