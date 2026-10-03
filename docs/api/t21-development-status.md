# T21 本地合成投递交付记录

实现范围见[先行 PRD](../prd/development-delivery-v1.md)与[ADR 0011](../adr/0011-local-synthetic-delivery.md)。局部检查不代表完整 CI、可合并或可投产。

## 环境与来源

同一 /workspace/PIS、validation/t15-20261002，T20 起点 29a32a1a8af435c9107489ceadb3822a29a983e5，开工工作区干净，保留 T15–T20 历史。读取 AGENTS 及相关工程约束、实现与测试；未发现额外适用仓库技能。未切环境、重写历史、登录 GitHub/处理凭据、push、改 OAuth/安全配置或部署。

实现前实际查看当前环境授权附件 UI-024 报告分发与回执 PNG；附件 SHA256 74f3305da4278f84c7660eb13625c1ff604174116d2fe13fbeffe85cf7a58291。采用版本/摘要、目的端、尝试、失败重试及回执区分要素。原招标 PRD 正文仍未取得，不以原型替代临床批准。

## 实际实现

V19 增量迁移新增持久化 outbox/inbox、操作/事件/拒绝审计及本地接收端版本。V1–V18 和旧报告/PDF/哈希不变。QUEUE 与 outbox/事件/审计原子提交；每个处理阶段独立事务，病例根锁及 CAS 串行化同病例消息。

人工驱动本地 worker，只有 LOCAL_SIM，无外部系统或网络投递。接收将实际固定 PDF 字节复制至本地数据库 inbox，按业务投递ID去重；修订、签署、产物、哈希及目的端精确绑定。T20 替换要求接收端当前签署等于原签署，不匹配记录 REJECTED；旧版本不可静默覆盖。T20 待发送状态不改，本地 ACK 不代表真实下游送达。

状态为 QUEUED、ATTEMPTING、RETRY_WAIT、ACKED、RECONCILED、REJECTED、DEAD；30秒租约，最多3次尝试，失败后5/20秒退避，超时明确回收，毒消息显式隔离。ACK要求存在实际本地接收证据、完整业务绑定及未过期当前尝试；对账另验接收端当前版本。无后台自动处理保证，无无限重试，无 exactly-once 宣称；终态留待人工调查。

所有业务入口继承当前报告资格、病例/医院/组织范围与默认关闭的合成隔离；入队/新接收再次核验当前冻结依赖。成功查询、各阶段和历史读取审计；已授权病例的业务拒绝独立追加最小错误码/动作/trace，不保存错误输入正文。入口鉴权及格式拒绝仍走既有安全边界，不宣称已完成生产合规审计。

CA 接口默认 NOT_CONFIGURED，描述未验证只能 UNVERIFIED；没有有效签名状态、签名方法、密钥、证书或真实CA调用。全程合成数据，无临床/法律签署效力。

## API 与界面

前缀 /api/requests/reports/cases/{id}/deliveries：

| 方法 | 行为 |
| --- | --- |
| GET ?page=1 | 审计后的分页列表及 CA 状态，20条/页 |
| POST | 绑定固定产物登记本地队列，重复同产物/目的端返回原操作 |
| POST /{delivery}/{action} | CLAIM、RECEIVE、ACK、RECONCILE、FAIL、TIMEOUT、POISON |
| GET /{delivery}/history?page=1 | 审计后的追加事件历史，20条/页 |

POST 必须 Idempotency-Key、CSRF；DTO 为 confirmedCaseId、artifactId、signatureId、revisionId、sha256、destination、expectedVersion、attemptId、reason。QUEUE/CLAIM 的 attemptId 为 null，其余绑定原尝试。401 未登录、403 CSRF、404 不可见对象、400 输入不合法、409 CAS/绑定/租约/阶段/ACK证据不匹配。状态型替换拒绝的200回执仅确认拒绝事件已保存，UI必须查看 REJECTED，不能认定已ACK。

新增“本地投递与回执”，展示状态、尝试、目的端、精确版本和追加历史。双击单发、未知结果原键确认、冲突保留输入；切病例、分页、选投递、取消和离开保护脏输入，丢弃迟到响应。页面始终标明本地合成，无真实送达。

## 已执行与待执行

本地固定 Node22.23.3/npm11.21.0，lint、TypeScript/Vite构建通过；90项前端单测（15文件）、29项全量UI回归通过，npm audit为0项漏洞。主包约1175KB，500KiB提示保留。浏览器使用本机 Chromium151，UI测试模拟HTTP，不等于真实服务E2E。

PG17无网络临时容器验证：V18→V19、队列与审计原子回滚、inbox与审计原子回滚、固定 PDF/哈希/目的端绑定、接收去重及不可变、终态禁止复活；并发 outbox CAS 仅一个领取成功，另一零行失败。临时容器已清理。这是实际SQL探针，不是Spring/Flyway/JDBC完整集成。

实际无依赖 DeliveryPolicy 和 CaAdapter 经现有 JDK 编译器 API 类型编译，执行租约精确截止、错误尝试、5/20秒退避、3次上限及毒消息终态探针通过；Java全源码语法解析通过。这些不等于完整后端类型编译。

本次离线 Maven3.9.16 verify，使用 /tmp/pis-t08-maven/repository，仍缺 Spring Boot4.1.1 导入的 Zipkin3.5.3、Brave6.3.1、gRPC 等 BOM，exit1，未进入完整 Java 编译/测试。日志 /tmp/pis-t21-maven.log。未改镜像、重试认证或绕过下载拒绝。

新增待执行 JUnit/Spring 测试涵盖迁移、租约/退避、接收后丢ACK及过期租约恢复、错误/迟到ACK、接收去重、并发领取、替换接受/拒绝、毒消息、审计回滚、撤权及 CA 无有效签名；测试源码存在不代表通过。完整后端verify、服务/HTTP/并发集成和正式JAR验证尚未完成。

frontend/e2e/delivery.spec.ts 使用独立 SYN-DELIVERY-001 合成夹具、真实登录/报告/PDF、本地接收、先ACK拒绝、重复接收、ACK及独立对账；不mock HTTP，不连接任何外部接收端。因后端构建阻塞未运行。CI 依指示不查询、不等待、不push，未验证。

最终git diff --check、T15–T20祖先链、V1–V18/安全配置/CI/依赖/字体/渲染器及原PDF字节比对、十一域源码无环与公开边界、文档链接检查通过。桌面/移动截图已实际查看，无页面整体横向溢出。应用进程真实重启故障注入尚未执行；恢复设计及测试不能替代目标环境演练。
