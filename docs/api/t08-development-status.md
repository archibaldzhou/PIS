# T08 实施记录（独立分支验证中）

2026-10-02，依据用户授权新编[开发 PRD](../prd/development-accession-v1.md)及已合法读取原型。本文件记录工作区事实，不替代 CI 或临床验收。

## 最新 CI 证据

父会话核验 `d7f78c2307874ec227f8e27f9edb6d394b53a4f2` 的 CI 37029404698：后端已编译并执行170项测试，169通过、1失败；单独依赖审计通过，主 verify 的前端阶段未运行。唯一失败是 `IdentityAccessMigrationTest` 仍将最新版本断言为4，实际已为5。

本轮修复更新版本断言并保留全部角色/权限种子限制，补充四张 V5 表无种子；V4→V5 测试检查旧申请/容器未改变、V1–V4全部校验和及角色/权限未变。修复后的结果待新 SHA 的 CI 核验。

`RequestWorkflowTest` 源码恰有8个 `@Test`，包括 `realHttpRequiresSessionCsrfAndCurrentWriteGrant`（真实随机端口、Cookie登录、CSRF、只读/跨范围/撤权、创建及重放）；它与 CI 该类8项执行数一致。`RequestMigrationTest` 为另1项，新增合计9项。以下本地阻塞记录不再表示 CI 尚未编译。

## 已写入工作区

- V5 增量迁移：独立 workflow_scope/workflow_grant、申请状态/临床资料、结构化容器详情。无机构/账号/权限种子；旧申请不自动进入新状态机。
- WorkflowAccess、RequestService/Controller、DTO：默认关闭且 dev/test＋合成账号限定，独立作用域、已有就诊选择、登记/查询/编辑/提交，复用 T07 幂等/审计，协调授权行/业务行锁及版本。
- 前端申请列表与登记表单，运行时响应解析、CSRF 写接口、原意图内存幂等键；不确定结果保留输入/键并禁止普通导航，退出登录仍可显式确认。原有认证链保留。
- T09 标本接收/异常页面已移出本次提交范围；T09 后端尚未实现。
- 新增后端迁移/服务权限、回滚、重复提交/真实行锁竞争测试源码；另补真实 HTTP 会话/CSRF/只读授权、同键并发创建、等待授权锁期间撤权回滚测试源码，均尚未编译执行。新增真实登录＋数据库申请 E2E 脚本，以及独立 HTTP fixture 浏览器 UI 测试。测试种子只在原有 test classpath 入口中。

## 本地检查与限制

使用官方 Node 22.23.3（发行包 SHA-256 与官方清单匹配）、npm 11.21.0，仓库 npm ci 安装成功；未升级依赖或修改锁定依赖树。

- frontend：npm run lint、npm test、npm run build 已通过；Vitest 3 文件59项。构建仍提示约1MB主 JS 分块，需要后续性能评估，未提高警告阈值。
- frontend：npm run audit:dependencies 返回 0 vulnerabilities。
- frontend：PIS_UI_BROWSER_PATH=/usr/bin/chromium npm run test:ui，4项通过。此为显式合成 HTTP fixture 测试，不替代真实数据库 E2E。
- 浏览器是环境已安装 Chromium 151.0.7922.173；Playwright 固定 Chromium 下载返回403 Domain forbidden，安装器自动重试后失败，未自行更换下载域/身份。此处的本地已安装浏览器路径仅适用于 UI 专用配置，原真实 E2E 配置保持默认固定浏览器。
- 已查看生成截图 registration-desktop.png 和 reception-mobile.png，桌面双栏与390px窄屏均可读，无横向页面溢出；均为合成 fixture，截图不是后台验收。
- 后端：Maven 3.9.16 从 Apache 官方归档取得并匹配仓库 Wrapper SHA-256；宿主没有 javac 可执行文件，但 java --list-modules 包含 jdk.compiler，尚未实际完成 Maven 编译。JDK下载入口403后未更换路线继续下载。
- Maven 使用环境已有代理配置后仍遇到 Central HTTP429，依赖解析未完成。未切换版本、禁用 TLS 或跳过测试；无后端编译/verify/真实 API E2E 成功结论。
- 后续带 `-U` 的同源重试持续数分钟仍停留在 POM 解析，已终止该自有进程（退出143），避免无界重复下载；这次中止不记录为测试失败或通过。
- 15:33 与 15:38 UTC 对 Central 固定 `org.springframework.boot:spring-boot-dependencies:pom:4.1.1` 单次探测仍为 HTTP429，均无 Retry-After；未密集重试。Google Cloud [官方文档](https://docs.cloud.google.com/docs/buildpacks/java)列出的 Central 镜像单次连接被代理拒绝（CONNECT 403），立即停止，不改域绕过，也未将镜像写入仓库。
- 可写 Maven 缓存仅11个 POM、0个 JAR；已检查的系统只读 Maven 缓存17个 POM、6个 JAR不包含本项目所需依赖。`mvn -o -C ... verify` 退出1，停于 POM 解析，缺少 `io.zipkin.reporter2:zipkin-reporter-bom:3.5.3`、`io.zipkin.brave:brave-bom:6.3.1`、`tools.jackson:jackson-bom:3.1.5`、`org.junit:junit-bom:6.0.3` 等 BOM；编译/测试阶段未开始。完整本地诊断在 `/tmp/pis-t08-maven/offline-verify.log`。
- 15:41 UTC 再次完整执行 lint、59项单测、typecheck/build、4项独立 UI 测试，全部通过。原有17项真实后端 E2E 和新增申请 E2E 尚未执行，不能以这4项 fixture 测试替代。
- 真实 PostgreSQL 17.11 隔离 SQL 探针执行 V1–V5 成功，并断言默认禁用作用域、禁止跨院关联、作用域身份不可变、WRITE 必须有 READ、无工作流种子。探针容器与 tmpfs 已清理；它不替代 Flyway 升级校验或 Java 服务测试。

后端测试和完整联调尚未通过，不声称 T08/T09 已完成。用户已特别批准独立验证分支临时提交：`validation/t08-20261002` 使用现有完整 CI 门禁，main 保持不变；全部通过后才整理 T08 完成提交。此前“不提交推送”限制由该明确授权限定解除。

## T08 提交前剩余

1. 完成依赖获取，执行完整真实 PG17 verify，修复实际编译/测试失败；新增 V4→V5 升级测试和旧门禁都必须通过。
2. 实际执行新增申请接口的真实 HTTP 认证/CSRF/错误、创建幂等竞争与撤权等待测试，修复失败并补齐任何缺失回归；源码存在不表示矩阵通过。
3. 运行真实后端＋PG 的浏览器登记/编辑/提交闭环，复核 UI 与当前 API 的映射、时间/容器次序和冲突行为。
4. 单独整理 T08 提交范围，避免将未完成 T09 当作完成任务；推送后交父会话核验确切 SHA 的 CI。之后再完成 T09 真实接收/异常/退回用例及测试。
