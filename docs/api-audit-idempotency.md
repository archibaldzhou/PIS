# T07 API、审计和幂等基础契约

## 范围

本任务建立统一错误、参数校验、服务端请求关联、事务成功审计和短事务幂等基础。没有生产患者、申请或接收写接口，合成命令 Controller/数据表只允许放在 `src/test`。T08/T09 接业务时必须实现具体资源授权、原子业务写入、版本冲突和前端重试行为，不能把本任务的通用能力当作已完成临床验收。

沿用 Java 21、Boot 4.1.1、MVC、JDBC、Flyway、PG17；Bean Validation 使用 `jakarta.validation`，版本由 Boot BOM 管理。Jackson 使用 Boot4 默认的 Jackson3 `tools.jackson.*`。不引入 JPA、Redis、审计 AOP、springdoc 或无实际消费者的 Outbox。

## HTTP 错误与 trace

应用 API 错误响应的目标契约是 `application/problem+json`，包含 RFC9457 的 `type/title/status/detail/instance` 及顶层 `code/traceId`。实际 HTTP status 必须与正文一致。`type` 是 `urn:pis:problem:<lowercase-kebab-code>`；`instance` 是本次服务器请求的 `urn:uuid:<traceId>`，不复制可能包含患者标识或秘密的 URL/query。

`TraceIdFilter` 在 Security 链之前生成 UUID，返回 `X-Trace-Id`，并在请求线程绑定及 finally 清理 MDC。任何客户端同名 header 都不能指定服务端 trace。服务器 ERROR/ASYNC redispatch 复用该请求的 trace；新请求和幂等重放使用新 trace。后台线程不能凭空继承 HTTP 身份或 trace，未绑定上下文直接失败，后续异步任务须建立自己的明确身份/关联协议。

`ApiExceptionAdvice` 处理 MVC 异常；Security filter 的异常仍由 T06 的原 handler 处理并接共用 writer。`ApiProblems` 直接写 Security 响应时使用白名单字段 Map，避免随意构造的 JsonMapper 未装 ProblemDetail mixin，导致扩展字段嵌在 `properties` 下。Security writer 暂保留安全 `message` 字段以兼容 T06；code不改名，包括 `AUTHENTICATION_UNAVAILABLE`。不能通过关闭 CSRF、增加匿名业务路径或替换安全链来“修好”错误格式。

| 场景 | HTTP / code |
| --- | --- |
| JSON格式、类型、必需参数错误 | 400 / INVALID_JSON、TYPE_MISMATCH、MISSING_PARAMETER；其他INVALID_REQUEST |
| 入参约束失败 | 400 / VALIDATION_FAILED |
| 缺失/非法幂等键 | 400 / IDEMPOTENCY_KEY_REQUIRED、IDEMPOTENCY_KEY_INVALID |
| 认证、会话、CSRF、授权拒绝 | 保持 T06 的状态与 code |
| 资源不存在 | 404；业务层保持不可见资源的统一策略 |
| 业务状态或版本冲突 | 409；具体业务服务产生稳定 code，不能把所有数据库错误都转409 |
| 同key异请求 | 409 / IDEMPOTENCY_KEY_REUSED |
| 有界数据库锁等待超时 | 409 / COMMAND_BUSY；客户端保留原key重试 |
| 数据库命令执行超时 | 503 / COMMAND_TIMEOUT；客户端保留原key重试 |
| 未知错误、返回值约束失败 | 500 / INTERNAL_ERROR |

校验错误仅包含字段路径和约束 code，不包含 rejectedValue 或原始异常消息。强类型 DTO 配合 `@Valid` 和显式约束；未知临床规则不能提前写成枚举。字符串标识不能静默 trim、改大小写或 Unicode 规范化。`@Size` 的 String 长度是 Java UTF-16 code units，数据库 char_length 是字符数，API约束可能更严格；新业务文档须明确字段长度和文本政策。方法返回值校验失败属于服务器错误，不能误归400。

生产日志只写固定事件/错误 code、服务器 trace 和必要技术字段；不得记录 body、query、密码、cookie、CSRF token、患者字段、拒绝值或数据库异常原文。不得使用 `logger.error(..., exception)` 无条件输出可能含 SQL/参数的完整异常。

## 服务端主体与授权

`CurrentActor` 只接受 `SecurityContext` 中已认证且启用的 `PisPrincipal`，并再次查询 `app_user` 的 enabled/auth_version。请求中的 actor、角色、scope、sessionId 均不参与身份选择。角色和医院/院区/科室权限仍由 T06 当前持久化授权决定，不缓存进幂等记录作为未来通行证。

`IdempotentCommands.execute` 的 hospitalId 必须由业务服务从权威资源/来源解析并验证，不得把任意客户端 hospitalId 直接当成已授权范围。Work.authorize 验证当前操作权限；Work.authorizeReplay还必须验证回执指向资源的当前权限。任何新业务实现都需要专门的跨范围/撤权测试。

**重要：在事务中先调用 authorize，再做无条件 UPDATE，不解决授权竞争。** 实际业务 SQL 必须把当前主体、授权、资源归属和 expectedVersion 条件放到同一原子写语句中，或采用经过证明的锁定协议。测试命令使用独立合成权限表和带权限谓词的原子更新；它不能替代T06病例策略或证明未来T08/T09写入正确。

## 成功审计

`audit_event` 只包含 UUID、数据库写入时间、主体UUID/认证版本、医院UUID、静态操作/资源类型、资源UUID、旧/新版本及trace。多态 resource_id 不假设某一临床表；没有任意 JSON metadata、内容快照或原始幂等key。

`AuditRecorder.append` 要求已有可写事务，服务器读取actor/trace，使用应用生成UUID和不带 RETURNING 的INSERT。业务、成功审计、幂等完成回执一起提交；任何一环失败则全部回滚。失败业务没有“成功审计”，T06安全事件继续独立处理；当前安全事件日志不是持久化合规审计。审计读取接口、医疗访问日志范围、外部归档及留存政策尚未实现。

## 幂等域、摘要与回执

客户端为一次用户意图生成不含患者信息的 `Idempotency-Key`，允许 1–128 个 ASCII 字母、数字、点、下划线、冒号和短横线。网络断开、响应丢失或可重试错误后，应复用同key和同命令；用户修改内容后使用新key。客户端不能在结果未知时自动换key重提。

数据库只保存key的SHA-256；唯一域是 `(actor_user_id, hospital_id, operation_code, key_hash)`，其中operation是服务器定义的版本化常量，例如 `REQUEST_CREATE_V1`。不可加入可变的sessionId、authVersion、角色或trace，否则换会话/改密码会使同一意图重复执行。目标资源UUID、expectedVersion和全部有意义输入都进入摘要。

`CanonicalRequestDigest` 对校验后的类型化命令构造确定表示：对象键递归排序，数组顺序保留，字符串精确保留，等值十进制数统一表示，显式null保留，再取SHA-256。深度与规范结果大小有界。它不提供任意HTTP JSON的语义推断，也不检查某个业务DTO是否遗漏字段。缺失与null是否等价由类型化命令明确决定；未来PATCH如果需要区分字段是否出现，必须使用能表达presence的命令，不能直接套普通nullable字段。改变规范或业务含义时升级操作契约/摘要版本。

成功只保存 `CommandReceipt(status, resourceType, resourceId, version)`；当前status限定200/201，不缓存任意响应headers或临床JSON。重放返回同回执并标记replayed，HTTP适配层可返回 `Idempotency-Replayed: true`。当前状态查询由独立、重新授权的读取完成。

### 事务/并发算法

执行器自己拥有READ COMMITTED短事务；不允许被另一个事务包裹。当前技术上限为锁等待3秒、单语句5秒、Spring事务10秒，不是临床SLA或总体外呼限制。

1. 读取当前主体并授权，验证key，计算类型化命令摘要
2. 单事务INSERT IN_PROGRESS，唯一约束裁决竞争，`ON CONFLICT DO NOTHING`
3. 竞争可能等待；返回后再次确认主体和操作权限
4. 本请求插入成功：原子业务写入、成功审计、更新SUCCEEDED和回执，统一提交
5. 未插入：必须另发SELECT读取已提交的获胜记录，检查当前回执资源权限，再比较摘要并重放/拒绝

PG17 READ COMMITTED 的DO NOTHING可能因为本条语句快照看不到的竞争行而放弃插入。因此不能将INSERT和“读取已有行”合成同一CTE期待一定拿到结果。正常流程不会单独提交IN_PROGRESS，也没有租约或过期接管。发现人工/损坏IN_PROGRESS时不能当成功；返回COMMAND_BUSY待调查。

业务异常、审计失败和最终回执写入失败均回滚reservation，之后原key可重试。数据库异常使事务中止后，必须先整体回滚再映射HTTP错误，不能catch后继续读写。当前Work只允许unchecked异常；后续若引入checked异常协议，必须明确全部失败回滚。

这个算法仅覆盖同数据库内事务性副作用。没有自动过期/清理策略，也不承诺外部exactly-once。若后续需要AI或外部网络工作，短事务保存任务及真实Outbox事件后返回202，再由消费者执行和去重；不得在这里等待推理、文件上传、消息发送或远程HTTP。

## 部署时的审计权限

当前Compose和CI测试账号为合成环境超级用户。**迁移文件本身不将当前应用账号变成只追加角色，不能以开发环境通过宣称生产审计不可篡改。**

生产应采用独立迁移作业和独立runtime身份，应用进程不持有migrator/owner凭据。迁移完成后runtime使用 `SPRING_FLYWAY_ENABLED=false`，由部署编排保证正确版本，不能把迁移密码留在应用环境变量中。

由授权管理员在实际环境选择已有角色后执行最小权限配置；下面是模板，不创建账号/密码，也不应原样用于未知环境：

```sql
-- pis_runtime 是由部署管理员确认的实际运行角色名。
GRANT USAGE ON SCHEMA pis TO pis_runtime;
REVOKE ALL ON TABLE pis.audit_event FROM pis_runtime;
GRANT INSERT ON TABLE pis.audit_event TO pis_runtime;
```

此外必须核查角色的完整有效权限，而不只是这三句SQL：

- runtime不是superuser，不拥有audit表、schema或database，无CREATEROLE/CREATEDB/REPLICATION/BYPASSRLS
- 无直接、继承或SET ROLE到owner/高权限角色的路径；NOINHERIT单独不足够
- 无audit UPDATE/DELETE/TRUNCATE/TRIGGER、授权选项、危险SECURITY DEFINER函数或级联改写路径
- 撤销不必要的schema/database CREATE，检查PUBLIC与search_path；若要求禁止临时DDL，还需处理database TEMPORARY权限
- audit读者用独立角色；writer INSERT不带RETURNING，所以无需SELECT
- 不要对全部未来表统一授予全DML权限，否则新审计表可能重新获得修改权限

这只限制普通runtime改写已有历史。DBA/owner可以改变权限或对象，被攻破的writer仍可能插入虚假记录；没有防DBA篡改、外部WORM、密码学完整性或合规认证承诺。

`AuditPrivilegesTest` 仅在名称以_test结尾的真实PG17合成库创建随机NOLOGIN临时角色，无密码；以数据库会话授权模拟其实际有效权限，验证INSERT成功、SELECT/UPDATE/DELETE/TRUNCATE/ALTER/DROP/CREATE及提权被拒绝，最后撤销授权并删除该角色。这不是生产账户创建或登录验收。

## 验证边界

本任务新增独立规范化、API错误/trace、PG事务/竞争、迁移和权限测试。并发测试必须观察第二连接确实进入PostgreSQL唯一键锁等待后再释放第一连接，不能只并排启动两个线程就宣称覆盖阻塞语义。还需跑全套T06登录/会话/CSRF/范围和已有前后端回归。

只有完整真实PG17测试和最终SHA的CI通过后才能报告对应验收成功。编译、源码存在、局部无数据库测试通过均不能替代数据库或端到端通过。

依据：[RFC9457](https://www.rfc-editor.org/rfc/rfc9457.html)、[Spring MVC错误](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)、[MVC校验](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html)、[Boot JSON](https://docs.spring.io/spring-boot/reference/features/json.html)、[PG17隔离语义](https://www.postgresql.org/docs/17/transaction-iso.html#XACT-READ-COMMITTED)、[唯一性检查](https://www.postgresql.org/docs/17/index-unique-checks.html)、[权限](https://www.postgresql.org/docs/17/ddl-priv.html)、[角色成员关系](https://www.postgresql.org/docs/17/role-membership.html)
