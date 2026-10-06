# T06：身份与资源授权开发模板

## 范围与生产门槛

本任务只实现本机合成数据的会话登录、退出、CSRF、账号撤销和内部资源授权。没有患者/病例业务 HTTP 接口，没有临床签发、报告审核、诊断操作或真实医院角色制度。`CASE_EDIT` 只是待后续业务接入的结构授权示例，并不表示已允许任何临床操作。

上线前必须由医院确认组织/院区/科室拓扑、病例责任归属和转科制度、角色与操作资格核准人、初始账号配置方式、会话/密码/锁定政策，以及 SSO 身份提供方和身份映射。生产部署还需要最小权限运行数据库账号、TLS/可信代理配置、共享会话或明确的单实例策略、部署级限速、持久安全审计及安全评审。本任务没有实施这些部署步骤。

认证主体与资源授权分离：`PisPrincipal` 只保存稳定用户 ID、认证版本和基本显示信息，不缓存角色/数据范围。未来 OIDC 可在已验证外部身份映射为本地用户后复用该主体与策略；目前没有 OIDC provider、回调、账号绑定或 SSO 按钮，不能视作 SSO 已完成。

## 本机合成账号

生产迁移不带账号或默认密码。默认情况下，没有账号即可启动空系统，但无法登录。不得将真实患者信息放入开发库。

仅在显式 `dev` profile、回环地址 `127.0.0.1`、数据库名以 `_dev` 结尾时可以启用开发账号初始化：

1. 在用户自己的终端/环境中设置 `PIS_DEV_AUTH_ENABLED=true`
2. 设置 `PIS_DEV_USERNAME`：3–64 位的小写 ASCII 字母、数字、点、下划线或连字符，首位为字母或数字
3. 设置 `PIS_DEV_PASSWORD`：16–72 UTF-8 字节；不要在聊天、版本库或命令参数中填写真实密码
4. 按 README 导出环境变量后启动 `SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run`

初始化只创建一个 `synthetic_only=true` 的账号，不创建医院、病例、角色范围、分配或操作资格；它可以查看登录后的工程连通性页面，没有病例读取权。密码仅以 `{bcrypt}` cost 12 的自适应 hash 入库。不要复用任何真实系统的密码。

启动时若同名合成账号已经存在，保持其密码、停用状态及现有权限不变，不通过重启重置密码或重新启用账号；同名非合成账号会导致初始化失败。密码/账号管理界面不在 T06 范围。

非 dev/test 环境拒绝开发账号开关、非 Secure session cookie及数据库中的合成账号；`prod` 与 `dev`/`test` 混用也会启动失败。测试 profile 配置只放在测试资源，不打入正式 JAR。`PIS_DEV_AUTH_ENABLED`、账号和密码都没有可用的生产默认值。

## HTTP 与 CSRF

- `GET /api/auth/csrf`：允许匿名；返回 `{headerName, token}`，通过同一个 HTTP session 保存预期 token
- `POST /api/auth/login`：表单编码 `username`/`password`，携带 `X-CSRF-TOKEN`，成功 204，失败统一 401
- `GET /api/auth/me`：返回 `{id, username, displayName}`；未认证 401
- `POST /api/auth/logout`：要求 CSRF，失效当前 session并清除 cookie，成功 204
- `GET /api/hello`：只允许已认证账号
- 无详情的 health/readiness/liveness继续匿名；其余生产路径默认拒绝

登录采用 Spring Security 的表单认证过滤器及 JSON handlers，不手工绕过 session fixation、SecurityContext 保存或退出流程。登录会改变 session ID；仅使用 cookie tracking，不接受 URL session ID。禁用 HTTP Basic、请求缓存及 HTML 登录重定向；不启用 remember-me。

CSRF 使用 `HttpSessionCsrfTokenRepository` 和框架默认 XOR/BREACH 保护。前端只在内存持有端点返回的 token，并按返回 headerName 回传；不要混用明文 CSRF cookie 的 SPA 配置。认证成功、退出成功后重新取 token，旧 token不能再用于写请求。[Spring CSRF 文档](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)

状态码约定：

- 401：未登录、失效会话或统一登录失败
- 403 / `CSRF_INVALID`：缺失或失效 CSRF，包括尚未登录的 POST
- 403 / `ACCESS_DENIED`：已登录但无权执行操作；不表示应自动退出
- 429 / `LOGIN_THROTTLED`：登录尝试过频
- 503 / `AUTHENTICATION_UNAVAILABLE`：暂时无法查询当前账号状态，拒绝沿用旧认证

错误响应为 `{code, message}`。未知账号、错误密码、停用账号使用相同的登录失败响应，不返回账号存在性。密码超过 bcrypt 的 72 字节上限会直接拒绝，不能静默截断；明文、noop、未知 hash 前缀无法认证。T07将接入通用错误格式，必须保留这些状态与前端的区分处理。

## 会话、撤销和防护边界

默认 cookie 为 `PIS_SESSION`，HttpOnly、Secure、SameSite=Lax、Path=/、不设 Domain；仅显式 dev/test 使用 HTTP 时关闭 Secure。默认空闲超时 20 分钟，作为待医院确认的开发值。认证与 CSRF 响应不缓存；前后端同源，不开放宽泛的带凭据 CORS。

每个带已认证主体的请求，在恢复 SecurityContext 后检查当前 `enabled` 和 `auth_version`。停用、删除或版本不匹配会清空上下文、失效 session、清除 cookie并返回 401；数据库异常会失效当前 session并返回 503，不能回退使用旧权限。

修改密码或 enabled 会在同一数据库语句中由触发器递增 `auth_version`；禁用后再启用也不会复活未曾访问过的旧 cookie。版本不能回退。角色/范围/资格/分配每次资源授权重新查询。

支持多个独立 session，退出只退出当前 session；停用账号/修改凭据会使所有旧 session在下一次请求失效。**本任务没有启用并发会话名额上限，也不声称严格单设备登录。** 当前 session位于单个进程，不是可直接水平扩容的共享会话方案。[Spring session 文档](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)

登录限速为有界的单进程开发保护：每个规范化用户名 20 次/5 分钟，每个来源地址 100 次/5 分钟，未知账号同样计数。限制成功和失败尝试，不宣称恒定时间认证或分布式防爆破能力；不信任客户端伪造的转发地址。上线需要审核部署级限速和账号恢复策略。

撤销承诺限于变更提交后的下一次请求/下一次授权查询。不能中断已经通过检查的在途请求。未来业务写入口必须将授权条件与写入绑定，或采用与撤权/转科协调的锁定协议；单独先调用 `permits` 再写入，甚至仅加 `@Transactional`，都不能保证消除授权竞态。

## V3 资源模型

- `campus` 属于 hospital；`department_campus` 显式关联同院院区与科室，允许一个科室在多个院区
- `case_access_scope` 保存病例唯一当前院区、责任科室和 scope_version；没有归属就拒绝
- `pathology_request.requesting_department_id` 仅是申请/送检科室，不作为病例负责科室授权
- `user_role_scope` 绑定单个 role及 HOSPITAL/CAMPUS/DEPARTMENT 范围，NULL 形状受 CHECK约束；缺失字段不会扩大成院级范围
- `case_assignment` 记录 READ/EDIT 分配和所依据的 scope_version；转院区/责任科室后版本递增，旧分配留存但失效
- `user_operation_qualification` 记录操作、组织范围、有效期和撤销状态；是软件资格记录结构，不是医学资格认证
- 复合 FK 防止跨医院、跨院区科室错配；版本触发器不允许退回历史版本

三个默认角色均为待医院评审的模板，不是已批准的人员角色：

- `SECURITY_ADMIN_TEMPLATE`：没有病例读取/编辑权；账号管理 API亦未实现
- `CASE_READER_TEMPLATE`：CASE_READ
- `CASE_EDITOR_TEMPLATE`：CASE_READ与CASE_EDIT；编辑还需要独立有效资格

## 内部授权算法

`CaseAccessPolicy.permits/require(userId, authVersion, caseId, operation)` 只能从服务端已认证主体取得身份与版本，实际病例归属从数据库读取。

必须在**同一条有效 grant**内满足角色有操作权限、组织范围覆盖实际病例归属、分配条件满足；不能将 A grant的权限与 B grant的范围拼接。`ASSIGNED_ONLY` 需要有效且版本相符的分配。CASE_EDIT额外要求覆盖当前实际归属的有效操作资格。

READ与EDIT分别校验，分配/角色/资格中任一项单独都不产生权限。不存在的病例、缺失归属、撤销/过期关系以及未知操作均拒绝；REPORT_SIGN始终不受支持。策略不接收客户端自称的角色、医院/科室或用户 header，也不公开患者数据。

## 测试、正式制品与审计边界

真实 PostgreSQL 17 集成测试使用既有 `PostgresTestDatabase`，每组拥有独立随机 schema，数据库名必须以 `_test` 结尾；连接/迁移失败会失败，不跳过、不回退 H2。保留独立 V1→V2 升级验收，增加 V2→V3 升级和完整 V1→V3 迁移。

测试用资源 Controller仅存在 `src/test`，其测试链复用生产的 `authenticationAndSession` 配置，包含相同账号撤销过滤器、CSRF、认证/session和错误处理，仅增加合成路径的 authenticated allowlist。正式链不允许这些路径，正式 JAR不包含它们。

浏览器测试通过 Maven `spring-boot:test-run` 显式启动测试入口 `SecurityE2eApplication`，创建独立随机 schema及测试账号，退出后清理其自有 schema；不是在正式 JAR中打开测试后门。测试账号字面量仅在测试源码中，正式资源、启动配置与数据库迁移没有默认凭据。[Spring Boot测试启动文档](https://docs.spring.io/spring-boot/maven-plugin/run.html)

`mvn verify` 在打包后使用 Failsafe检查实际正式 JAR，确认无测试Controller/fixture、测试配置、测试依赖或示例凭据。前端测试覆盖会话恢复、登录失败、重复提交、CSRF错误、退出失败、迟到响应与窄屏布局。真正的 PG/Chromium结果以该提交的 CI执行为准。

`SecurityEvents` 实际发布并记录登录成功/失败、退出、会话撤销和限速事件，为T07留下监听接口。不记录密码、cookie、CSRF、提交的用户名、IP或请求体，也不声称完成持久化、不可篡改或合规安全审计。

## 2026-10-07 集中管理扩展

现已新增合成后台用户、岗位权限、组织范围、密码重置和首次改密；旧文中的“管理界面不在T06范围”是历史任务边界。医院管理员独立授权，不能因管理角色隐式获得诊断或签署资格。详见[ADR 0032](adr/0032-managed-synthetic-identity.md)与[接口说明](api/identity-administration.md)。生产门槛不变。
