# T35 接口与交付记录

开发PRD：`docs/prd/development-synthetic-worker-v1.md`；架构：ADR0024。已实际查看授权原型UI-044；界面使用中文且始终标明非诊断。

## 契约

所有入口同源会话/CSRF及现有授权，单独默认关闭开关。无临床执行许可。

- `POST /api/requests/{request}/scans/{scan}/synthetic-tasks`：assessmentId、workerSchema、强制reason；Idempotency-Key。
- `GET /api/requests/{request}/synthetic-tasks?page=1&scanId={scan}`：本提交者、当前身份版本、确切扫描资源授权后单次有界查询（不逐项N+1），页1–5、20项。UI当前展示第一页并明确20项限制。
- `GET .../synthetic-tasks/{id}`：保存状态与当前有效状态分开，依赖变化显示INVALIDATED，不隐式改写GET。历史最多100项；无诊断字段。
- `POST .../{id}/actions/{CLAIM|HEARTBEAT|CANCEL|RETRY|RECONCILE}`：expectedVersion、generation、leaseId、reason；Idempotency-Key。RECONCILE持久化当前输入失效、存储失败或租约超时，重试有界退避。
- `POST .../{id}/run`：当前expectedVersion、generation、leaseId、reason；仅执行有界本地技术夹具。逻辑幂等身份是task+generation+lease，内部稳定存储键与callbackId，不接收任意代码/URL/临床输入。
- `POST .../{id}/callback`：expectedVersion、generation、leaseId、callbackId、source/schema（均固定SYN-CONTRACT-WORKER-1）、artifactId/hash。callbackId必须等于租约ID，同一代次唯一；真实会话及当前owner/资格/QC授权，不把客户端source字符串当身份认证。
- `POST .../{id}/artifact`：必须当前资格有效，返回最多4KiB的原始不可变技术夹具，no-store/nosniff，固定下载名。通用storage字节接口拒绝该用途。

所有命令Result的`replayed`是T07重放信号，receipt ID和版本不可改。撤销、旧租约、旧代次、错hash、错schema、CAS冲突拒绝，不返回临床结果。停止浏览器等待不等于服务器取消；原键确认仍保留原输入；明确放弃后必须重新读取。

## 验证路径

- `RequestWorkflowTest`新增实际服务+PG测试：精确绑定、幂等、产物hash、通用存储拒绝、迟到回调、模型/QC撤销、并发领取、取消竞争、有限退避、审计回滚与原产物恢复、资格/HTTP/CSRF/严格DTO。
- `SyntheticWorkerModeTest`：默认关闭、dev/test限定、prod拒绝。
- `SyntheticWorkerProcess`仅测试classpath，真实Spring应用JVM连接父测试自建schema。领取后Runtime.halt(23)，父进程冻结时钟对账/退避，第二应用进程完成另一代次；不创建生产访问。
- `frontend/e2e/viewer.spec.ts`扩展真实服务队列创建/领取/产物/撤销；现有44场景保留。
- UI回归：未知进度、脏表单、任务切换、延迟/停止等待、原键重试、撤权错误；前端parser严格版本/身份/非临床断言。
- `python3 backend/src/test/probes/ai-postgres.py`：独立PG17 V1–V32迁移、CAS/回滚/约束；不是完整Java服务或进程重启证明。

## 本地结果与边界

本地实际通过：177项前端单测（31文件）、typecheck/lint/build；87项完整UI（4.2分钟），收尾改动及新增产物读取回归后4项UI复测通过（15.9秒）。PG17 V1–V32探针通过。源码语法/词法/HTTP受检异常检查通过，但这些不是Java类型编译。真实E2E仅发现44场景，未运行真实服务。

原始本地日志与实际浏览器截图在 `docs/evidence/t35/`。截图经过实际查看，未知进度、准确任务ID、非临床声明及清空后的表单可见；该截图来自API模拟UI回归，不是服务端E2E证据。初次新UI测试误用通用菜单丢失病例上下文，补充确切扫描入口后修复；产物撤销测试按既有AI_CONFLICT中文错误契约修正匹配，保留拒绝及旧内容清除断言。

复现命令：`npm --prefix frontend test`、`npm --prefix frontend run lint`、`npm --prefix frontend run build`、`PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui`、`python3 backend/src/test/probes/ai-postgres.py`。当前执行环境Node22.23.3、Chromium、固定PG17.11镜像；本地辅助工具在/tmp，同一原执行环境，无环境切换。

完整Maven verify已尝试，但本地缺Boot4.1.1导入BOM（包括zipkin-reporter3.5.3、brave6.3.1等），离线构建在POM解析时阻塞。没有绕过之前下载限制。完整后端编译/集成、真实服务E2E、实际应用进程崩溃重启需CI执行，不能以源码或PG探针替代。T35 CI未核验，不合main，不宣称临床/生产可用。

## CI 37164827163 回归修复

该轮后端462项有1 failure、2 errors，不是完整通过。本次独立修复保持T35在验证分支。

- 两个rollback-only错误：`invalid()`捕获从`workerBinding→assessment`或QC嵌套REQUIRED模板逃出的冲突；内部模板先把参与事务标为rollback-only，外层返回“失效”后尝试提交才触发UnexpectedRollback。改为`workerBindingCurrent`显式判定结果；通过已有历史/QC边界持有病例锁，再持有模型series/state锁，比较当前QC发布、模型/资料/校准版本。预期撤销返回false；认证、权限、IO及未预期错误仍原样失败、整事务回滚。不清除rollback-only、不改变事务管理器安全设置。
- 新回归验证撤销后读取明确失效、失效审计失败回滚任务/outbox/event、原键并发重放只产生一次状态与写审计；原有撤销后run/artifact拒绝、真实QC撤销检查保留。
- 实际应用子进程原来与存活父应用使用同一T28目录，`LocalStorageProvider`持有独占文件锁，第二进程必然`STORAGE_BUSY`。已用真实provider跨JVM复现该障碍。CI原始日志未提供，因此不宣称已排除其他启动故障。测试改为只复制本例合成原件及root身份marker到私有测试快照根目录；不复制锁文件，不关闭父provider，不改变独占锁。两个实际Spring子进程顺序使用同一快照根目录，仍保留halt(23)、数据库租约/退避恢复及第二进程经服务鉴权读取产物并验证字节。父测试额外比较实际产物字节。
- 子进程失败/超时断言附最后8KiB、至多64行、每行240字符的脱敏尾部；隐藏credential/password/token/authorization/JDBC等配置行，删除URL/路径/UUID。加入固定阶段标记与日志脱敏测试，不输出任意请求正文或配置。

本地实际通过：Java语法解析、231文件词法作用域、26个HTTP helper受检异常检查；真实存储provider类和日志脱敏类通过JavaCompiler编译，跨JVM独占拒绝/测试快照连续打开/脱敏探针通过；`git diff --check`通过。这不等于完整后端类型编译或实际Spring进程重启通过。

可复现独立探针（JDK21，在仓库根执行；输出只含合成固定标记）：

```sh
mkdir -p /tmp/pis-worker-fix-classes
javac -d /tmp/pis-worker-fix-classes backend/src/main/java/com/pis/storage/StorageProvider.java backend/src/main/java/com/pis/storage/StoragePolicy.java backend/src/main/java/com/pis/storage/LocalStorageProvider.java backend/src/test/java/com/pis/ai/WorkerProcessDiagnostics.java backend/src/test/probes/WorkerLockProbe.java
java -cp /tmp/pis-worker-fix-classes WorkerLockProbe
```

本环境无`javac`命令，但现有JDK的`ToolProvider.getSystemJavaCompiler()`可用，本次以它编译上述相同输入后运行。结果：`PASS actual provider JVM exclusive-root failure, sequential snapshot-root opens, bounded redaction; NOT full Spring application restart`。

实际尝试离线Maven `-Dtest=RequestWorkflowTest,SyntheticWorkerModeTest,WorkerProcessDiagnosticsTest test`，仍在POM解析因zipkin-reporter-bom3.5.3、brave-bom6.3.1等缺失而失败。完整后端、真实撤销事务/并发、实际应用崩溃重启和真实服务E2E仍需下一轮CI核验。没有修改前端、迁移、认证配置或既有测试门槛；未合main。
