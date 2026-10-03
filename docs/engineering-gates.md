# 工程门禁：实际入口、覆盖与缺口

核查日期：2026-10-02，T01。依据为仓库 scripts、pom、CI、测试源码；“存在/已配置”不表示本次运行通过。每项任务需报告实际结果，最终 CI 必须绑定确切提交 SHA。

## 可执行入口

使用 README 固定的工具链：JDK 21、Node 22.23.3、npm 11.21.0、Maven Wrapper。数据库连接/密码通过 PIS_TEST_DB_URL、PIS_TEST_DB_USERNAME、PIS_TEST_DB_PASSWORD 注入；隔离数据库名以 _test 结尾，PG17 不可用则失败，不跳过/回退 H2。

| 目录与命令 | 实际覆盖 | 限制 |
| --- | --- | --- |
| backend：./mvnw -B -ntp verify | Surefire 测试、真实 PG 迁移/约束/查询/授权/事务/并发、打包、Failsafe SecurityArtifactIT | 未绑定 Java 格式或全量依赖/秘密扫描；不能只 compile 替代 verify |
| frontend：npm ci | package-lock 冻结安装，固定工具版本和 esbuild 安装脚本许可 | 不用 npm install 重解锁文件，不用 --force/--legacy-peer-deps |
| frontend：npm run lint | ESLint、TypeScript 推荐规则、Hooks 规则，警告即失败 | 没有全仓格式检查或后端 lint |
| frontend：npm run typecheck | tsc --noEmit，strict，覆盖 src/e2e/配置 | build 已包含同一入口 |
| frontend：npm test | Vitest 自动发现 src 下 *.test/*.spec.ts(x)，当前 api/session 测试 | 不是完整业务组件/临床规则测试；不存在 test:ci 命令 |
| frontend：npm run build | typecheck + Vite 构建 | 不替代运行/浏览器验证 |
| frontend：npm run audit:dependencies | npm audit 生产和开发依赖，High/Critical 失败 | 网络失败也是失败；Low/Moderate 仍需评估；不是 Java 扫描 |
| frontend：npx playwright install chromium；npm run test:e2e | Chromium 与真实 Spring 测试入口/Vite 的登录、会话、CSRF、失败恢复及迟到响应 | 先完成后端 verify、配置测试库、释放 8080/5173；只使用测试 classpath 中合成种子；不是已完成业务闭环 |

CI `.github/workflows/ci.yml` 的 verify 和 frontend-dependencies 在 main push/PR 上执行；dependency-review 只在 PR 审查 GitHub 识别的依赖变更，不是 Java 全量扫描，也不会在 main 直接 push 时运行。不更改分支保护规则，不假称合并强制门禁已配置。

## 保留的真实数据库与安全回归

| 要求 | 当前测试证据位置（backend/src/test/java/com/pis 下） |
| --- | --- |
| 空库/升级/重复迁移/校验和/DDL 回滚/启动失败 | database/PostgresMigrationTest、CoreModelMigrationTest、DatabaseStartupFailureTest；security/access/IdentityAccessMigrationTest；audit/AuditIdempotencyMigrationTest |
| 同院患者/申请/病例及编号唯一域、跨范围 FK | core/CoreModelConstraintsTest、CoreModelQueriesTest；security/access/AccessModelConstraintsTest |
| 双连接旧版本不覆盖胜出值 | core/CoreModelVersionTest |
| 默认拒绝、CSRF/会话/撤销、病例权限/资格/分配 | security/SecurityHttpTest、SecurityConfigurationTest、SecurityStartupChecksTest；security/access/CaseAccessPolicyTest |
| 同键异参、竞争、回滚、回执重新鉴权、审计/幂等约束 | idempotency/IdempotentCommandsTest、IdempotencyConstraintsTest、CanonicalRequestDigestTest |
| ProblemDetail/字段白名单/trace 及 HTTP 状态 | api/ApiProblemsTest、ApiExceptionAdviceTest、ApiJsonConfigurationTest、TraceIdFilterTest；api/http/ApiHttpContractTest |
| 合成 runtime 审计只追加与正式 JAR 不含测试入口 | audit/AuditPrivilegesTest；security/SecurityArtifactIT |

测试源码只是可执行覆盖的定位；并发基线不能改成仅启动两个线程而不观察真实数据库竞争。新增业务仍须补用例原子校验，不把合成测试命令的授权结果当作真实申请/接收授权证明。

## 未建立的门禁与责任

以下负责人是待指定的责任角色，不是已接受任务的具体人员。不虚构工单、承诺日期或测试成功。相关功能接入前必须落地并更新此表；生产前完成所有适用门禁。

| 缺口 | 责任角色 | 阻断条件/需要的证据 |
| --- | --- | --- |
| 方法级授权注解、非 HTTP 服务调用回归 | 后端/安全负责人 | 若依赖注解，先启用 @EnableMethodSecurity 并证明拒绝；新业务服务始终需显式身份/范围与原子写校验 |
| 域循环依赖架构测试、Java 格式门禁 | 工程负责人 | 新业务域接入时确定可执行检查，不以空脚本或全仓无关格式化充数 |
| Java 实际解析依赖全量扫描、秘密扫描 | 安全/平台负责人 | 生产发布前建立可复现扫描和高危失败策略；PR dependency-review、.gitignore 和人工 diff 均非替代 |
| OpenAPI/事件/设备契约、业务组件及跨角色 E2E | 后端/前端/集成负责人 | 对应 T08/T09 及后续功能验收前提供契约与真实测试，既有登录测试不算业务闭环 |
| outbox/inbox、回放/补偿、WSI/AI 安全矩阵 | 集成/WSI/AI 负责人 | 外部副作用或相应功能接入前；当前没有消费者、临床模型、tile 服务 |
| 生产账号分离、持久安全审计、RLS（如采用）、备份恢复、容量及终端验证 | 平台/安全/业务负责人 | 目标环境上线前真实验证；合成超级用户及临时角色测试不替代生产验收 |
| JDK 精确补丁/支持期、覆盖阈值与扫描例外治理 | 工程/安全负责人 | 发布基线评审；例外需实名负责人/理由/期限/补救，不自动忽略高危 |

## T01 本次验证边界

T01 只新增规范、ADR、需求登记和本文，并更新 README 导航。适用检查为 diff/本地文档链接、命令入口及测试证据路径一致性；没有运行时、迁移、依赖或 CI 配置变更，因此不为此文档任务重跑 PG/浏览器/构建。当前环境 Node 24.19.0、npm 11.9.0 不符合仓库固定版本，不以这些版本运行前端门禁或关闭 engine 检查。

本环境 Actions API 曾返回 Forbidden，不换路重试。正常推送后交由父会话通过已授权连接核验该 SHA 的 CI；在收到结果前只报告 CI 待核验，不写通过。

T12新增 `architecture/TechnicalDomainBoundaryTest`：三个工作流域的显式源码引用无环与processing公开服务边界检查，随backend verify执行。它不是完整Java解析器或全仓字节码依赖检查，既有全局架构门禁缺口仍保留。

T13 将上述有界源码无环检查扩展至 accession/grossing/processing/specimen/label/material 六域；保留 processing 公开服务边界断言。执行结果待 T13 Java CI，不扩大为完整架构分析声明。

T14源码引用检查扩展到quality共七域，并增加QC与消费竞态PG测试、迁移与真实UI/API返工链。具体已运行/待CI结果见[T14记录](api/t14-development-status.md)，不由测试源码存在推断通过。

T15新增V13授权只读投影、同快照计数/分页、逐项批量领取与最小追踪。源码域引用检查扩展八域。前端72单测和14系统Chromium UI测试、本地PG SQL探针通过；新增真实PG/HTTP/并发/截止边界及真实E2E仍待确切SHA完整CI，见[T15记录](api/t15-development-status.md)。

T16 独立诊断资格、分配/领取/转交、原子门禁及追加历史已实现。九域源码引用检查、75前端单测、17系统Chromium UI测试、类型/构建/npm audit及PG17 SQL探针本地通过；Maven离线依赖缺失，Java编译/完整集成和真实Spring E2E未验证。本次依用户最新指示不等待CI、不push，见[T16记录](api/t16-development-status.md)。

T17增加report域，源码引用无环检查扩展十域。模板不可变、草稿CAS/历史/权限/幂等及审计回滚测试已补充；完整后端和真实E2E受Maven依赖缺失阻塞，不能推断通过。已运行证据见[T17记录](api/t17-development-status.md)。

T18新增复核资格、依赖快照/代次、退回重修、合成模拟冻结及并发/审计测试。前端和PG SQL检查通过范围见[T18记录](api/t18-development-status.md)；完整后端与真实E2E仍受离线BOM缺失阻塞，CI未验证。

## T19 本地输出验证边界

固定合成PDF的实际独立Java渲染、中文分页目视检查、前端单测/UI和PG17 SQL探针已执行；见[T19交付记录](api/t19-development-status.md)。独立渲染器类型编译不等于Spring完整编译；Maven缺失BOM仍阻塞后端verify、真实服务E2E和完整门禁。CI未查询/运行，不宣称可合并或投产。PDF无临床/CA/法律效力，打印历史无实体设备成功证明。

## T20 版本链本地验证边界

前端单测/UI、本地类型构建、PG17增量迁移/链约束/回滚探针及新旧实际PDF检查已运行，见[T20记录](api/t20-development-status.md)。新增后端并发、权限、失效、审计与真实跨角色E2E源码不等于测试已通过；Maven缺失BOM继续阻塞完整后端和真实E2E，CI未验证。不得据此声明可合并、投产或下游已替换。

## T21 本地持久化投递门禁

新增本地outbox/inbox、租约/CAS、有限重试、业务ACK及对账，实际检查见[T21记录](api/t21-development-status.md)。纯Java策略编译和PG SQL探针不替代完整Spring编译/集成；Maven缺失BOM仍阻塞后端及真实本地E2E，CI未验证。无真实外发或CA签署，不能宣称可合并、投产或临床已送达。

## T22 冰冻本地验证边界

独立 frozen 域加入十二域源码引用检查，V20、资格/QC/身份、人工时间/更正、版本及沟通分离的后端测试和真实本地 E2E 源码已补充。99 前端单测、33 HTTP mock UI、PG17 SQL 探针、独立 Java 时间策略与本地前端检查通过，详见[T22记录](api/t22-development-status.md)。完整后端编译/测试及真实 E2E 仍受 Maven BOM 缺失阻塞，CI 未验证；不能将这些局部检查作为合并或投产结论。

## T23 本地增量（2026-10-03）

细胞学台账与来源传播见[T23交付记录](api/t23-development-status.md)。107项前端单测、完整36项mock UI及收尾5项复测、lint/typecheck/build/npm审计、真实PG17 SQL并发/回滚探针及独立Java数量策略通过。V21迁移与后端/真实E2E测试源码已补；Maven缺失BOM仍在POM阶段失败，完整Java编译/后端JUnit/真实服务E2E/CI未验证，不构成可合并或投产结论。

T24 新增冻结染色批次、独立玻片来源及对照/技术结果门禁。真实PG SQL探针、前端检查和mock UI的执行证据见[T24交付记录](api/t24-development-status.md)。完整后端编译/集成、真实E2E及CI仍未验证，不能据此放行；医院批准方案与实际仪器接口仍是投产阻塞。

T25 新增病例限时会诊、个人意见/汇总版本、明确确认及非签署采纳。真实PG SQL、前端及独立Java检查证据见[T25记录](api/t25-development-status.md)。完整后端编译/集成和真实E2E仍因BOM缺失未验证，CI未连接；不据此放行合并或投产。
