# 后台管理、权限与自动范围：接口和本机验证

开发依据：[PRD](../prd/development-identity-admin-v1.md)、[ADR 0032](../adr/0032-managed-synthetic-identity.md)。仅合成数据；本会话授权开发，不代表医院业务/临床批准。

## 接口

继续使用同源 Cookie 会话和 CSRF。管理路由位于 `/api/requests/admin`，还需 dev/test 工作流开关及启用的合成账号。

| 路由 | 行为 |
| --- | --- |
| GET `/api/auth/me` | 新增 `administration`、`passwordChangeRequired` 标志，不返回密码、哈希、认证版本 |
| POST `/api/auth/password` | `{oldPassword,newPassword}`；成功204并使旧会话失效；不自动重试 |
| GET `/api/requests/work-context` | 默认范围、有效范围、菜单、可写范围；不是授予权限操作 |
| GET `/api/requests` | scopeId 可选；省略时仅聚合当前有效读取范围，20项分页，最多第10000页 |
| GET `/hospitals` | 当前有管理或管理审计权限的医院 |
| GET `/{hospital}/catalog` | 固定岗位模板、权限、院区、科室、来源、工作范围；医院管理员专用 |
| GET `/{hospital}/users`、`/users/{id}` | 20项分页搜索、读取完整人员授权；跨院 ID 拒绝 |
| POST `/{hospital}/users`、PUT `/users/{id}` | 新建、编辑、停用；配置姓名/工号/默认范围及最多10组范围权限 |
| POST `/{hospital}/users/{id}/password` | 版本化密码重置，首次登录必须修改 |
| POST `/{hospital}/organizations` | 创建本院 CAMPUS、DEPARTMENT、SOURCE |
| POST `/{hospital}/scopes`、PUT `/scopes/{id}` | 创建、改名、停用范围；已有院区/科室/来源组合不可更改 |
| GET `/{hospital}/audit?page=1` | 成功变更与拒绝记录；每页20项，展开查看权限配置前后快照 |
| GET `/api/requests/diagnosis/output-scopes/{id}` | 独立报告输出队列；不授予诊断、复核或签署能力 |

除本人改密外，管理写入均要求 `Idempotency-Key`、`reason`；修改要求 `expectedVersion`。创建/更新返回原有 `CommandReceipt`，HTTP201/200；相同键不同输入409，重放重新鉴权，旧版本409。所有范围属于当前医院、默认范围属于本人授权的要求在服务和复合 FK 同时验证。密码规则16–72 UTF-8字节，bcrypt沿用既有编码器。

角色模板：系统管理员、科主任、登记接诊、取材、常规技师、细胞学、特殊染色/IHC、冰冻、诊断、复核签发、质控、报告档案、审计；分子岗位显示未开放。角色选择填入推荐权限，人工调整后以具体权限为准，不对既有账号做隐式批量变更。

## 本机操作

1. 登录后通过“后台管理”进入；也可从工作区右上角进入。
2. 在“用户管理”添加账号，设置工号、初始密码、岗位和范围，指定默认范围、权限截止时间和原因；专业权限另核验合成资格。
3. 用户首次登录修改密码后重新登录。普通工作区只展示当前范围；申请查询自动汇总可访问范围，打开申请后沿用其原范围。
4. 编辑人员权限、停用或重置密码将使该人员旧会话失效。当前管理员不能修改自己，通过另一位管理员配置。
5. 在“组织与工作范围”管理院区、科室、来源及范围；“管理审计”查看结果与原因。

首次管理员仅显式初始化：编译测试 classpath 后运行 `com.pis.security.testfixture.SyntheticAdminBootstrap <owned-test-schema> <synthetic.username>`。数据库连接来自 `PIS_TEST_DB_URL`、`PIS_TEST_DB_USERNAME`、`PIS_TEST_DB_PASSWORD` 环境；不要在命令行或仓库放密码。工具限定回环 `pis_test`、`pis_test_` 加32位十六进制 schema、既有合成账号和唯一有效范围，拒绝已有管理员的医院；不改密码、不新授予专业资格，并追加初始化记录。演示账号的现有专业授权保留。此工具不打入正式 JAR。

## 验证记录（2026-10-07，本机 Windows、PG17.11）

- `backend/mvnw.cmd -f backend/pom.xml -B -ntp -Dtest=IdentityAdministrationTest,IdentityMigrationTest,SecurityHttpTest,ManualRequestTest test`：28项全部通过。覆盖空库与V37升级、同键重放/异参、同键并发、旧版本、越院、低权限、资格、管理员直接签署拒绝、输出分权、密码/会话、拒绝审计、事务回滚、多范围与失效默认范围。
- `CaseAccessPolicyTest` 22项及 `SecurityConfigurationTest` 2项通过，未放宽原病例权限。
- `frontend: npm test` 208项通过；`npm run lint`、`npm run build` 通过。构建仍有既有大包提示，未关闭阈值。
- 既有119项浏览器 UI 回归：86项首轮通过，33项因共享测试夹具遗漏 expect 导入失败，修复后33项全部通过。只读范围收尾后重跑申请/后台55项，54项首轮通过，1项既有取材模式切换时序失败，未改断言单独复现通过。新增管理UI3项通过，覆盖原请求重试、双击、初始值、关闭重开清空密码和自动范围/菜单隐藏。
- 真实服务浏览器：`identity.spec.ts`、`accession.spec.ts`、`output.spec.ts`、`review.spec.ts` 共5项通过。包含新增账号→改密→自动范围→越权拒绝→停用/会话撤销及原复核/冻结/PDF链。二角色浏览器改用同一测试来源，避免连接固定5173的其他演示实例。

本机浏览器使用 Chrome 独立端口5194/5195；真实测试服务8081与随机 schema，原有8080/5173演示保留。标准CI继续使用仓库的 Playwright 入口，不更换或降低门禁。

完整 Linux CI、全量后端 verify、私有存储 POSIX、恢复演练及生产验收不由上述子集替代。未在本机运行完整存储链；Windows本机存储仍按用户要求不启用。当前提交的确切 SHA CI 需另行核验，历史CI不替代新提交。

本地8080原数据库通过V37→V38升级，升级前后申请单总数均31；使用显式初始化工具为 `synthetic.workflow.accession` 配置后台管理，原密码保持不变。实际5173浏览器已验证后台用户列表、默认可写范围及无范围选择器。
