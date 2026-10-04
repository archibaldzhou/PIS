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
