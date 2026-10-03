# T19 固定合成 PDF 与打印记录本地交付

实现范围依据[先行开发 PRD](../prd/development-report-output-v1.md)及[ADR 0009](../adr/0009-fixed-synthetic-report-artifacts.md)。本地部分检查通过，完整后端、真实 E2E 和 CI 未验证，不声明可合并或投产。

## 环境及原型

继续同一 /workspace/PIS、validation/t15-20261002，T18 起点 2e4c43bd7e4fd1755ac86ca432f0fc1e1a1c5a4b，T15–T18 全部历史保留。读取 AGENTS、相关工程文档与实现，未发现额外适用仓库技能。无环境切换、历史改写、GitHub 登录/凭据处理/push、安全配置修改或部署。

直接上传的 02_全部UI原型.zip 在当前环境验证可读，SHA256 为 74f3305da4278f84c7660eb13625c1ff604174116d2fe13fbeffe85cf7a58291。实现前实际查看 UI-024 报告分发与回执页面，采用版本、产物摘要、重试和打印历史要素；附件没有单独 PDF 排版/打印页面。原招标 PRD 正文仍未取得，不把原型或当前开发 PRD 当医院规则批准。本次不实现发送、T20 更正或临床签署。

## 实现与接口

V17 新增不可变 report_artifact、report_output_head 和追加 report_output_event。每次模拟签署唯一对应版本 0 的固定 PDF；绑定确切签署/复核、报告修订、模板版本、字段模式/快照、QC 等依赖摘要。保存字节、SHA256、渲染器和字体版本；重复预览、下载、重印直接读取同一字节，绝不以新模板重造旧版。V1–V16 保持不变。

接口前缀 /api/requests/reports/cases/{id}/output，保留会话、CSRF、身份隔离、医生资格及病例/组织授权。管理员不自动获得权限；合成模式仍默认关闭。

| 方法/路径 | 契约 |
| --- | --- |
| GET | 冻结签署/修订、依赖是否仍有效、产物元数据和活动版本 |
| POST | confirmedCaseId、signatureId/version、revisionId、reason；幂等确保固定产物 |
| POST /{artifact}/bytes/PREVIEW 或 DOWNLOAD | 经授权和原子审计读取；无二进制 GET |
| POST /{artifact}/events/{kind} | PRINT_REQUEST、REPRINT_REQUEST、USER_REPORTED_PRINTED/FAILED/CANCELLED |
| GET /{artifact}/history?page=1 | 当前授权下每页 20 条追加历史，page 上限 10000 |

所有 POST 要求 Idempotency-Key；读取/打印操作绑定 confirmedCaseId、artifactVersion、sha256、expectedVersion、requestId 和 reason。首次打印不含 requestId，重印及自报必须引用原打印请求；自报仅原操作者，结果只能追加一次。打印需要 PRINT、重印 REPRINT；读取允许并发追加，打印及自报需要 CAS。错误区分 400 输入、401 未认证、403 CSRF、404 不可用对象/作用域、409 冲突/失效、429 渲染忙与 503 字体/产物完整性故障。

新增生成及打印必须重新验证当前依赖；历史 PDF 可在当前授权下读取并提示历史依赖失效。已存在产物的确保操作只返回原产物，不重新生成。事务外渲染、病例根锁下重新核验、事务内字节/事件/头/审计/回执一致；审计失败不返回 PDF。二进制响应仅服务器 UUID 文件名，no-store、nosniff、固定 MIME、长度和摘要头，不接受路径或任意文件名。

受限 JDK 渲染器与随包 OFL 中文字体无新增运行时依赖；无任意 HTML、脚本、远程 URL 或远程字体。输入、页数、字节、同时渲染数与时间有界，架构限制见 ADR。每页均标记“合成演示／非临床报告”。PDF 是栅格演示文件，无可搜索文字层、PDF/A、CA 或法律/临床效力。

前端加入“固定PDF与打印记录”，内存 Blob 在完整性校验后展示/下载，关闭及切病例释放。双击单发、未知结果原键重试、冲突保留输入；动作/病例切换和离开处理脏状态；迟到响应丢弃；损坏 PDF 不展示。打印请求、用户自报与历史明确区分，无 window.print 或设备命令，无硬件成功声明。

## 已执行检查

当前环境固定 Node 22.23.3/npm 11.21.0：

- npm --prefix frontend run test：84 项、13 文件通过。
- npm --prefix frontend run lint、run build：通过，含 TypeScript 检查；约 1155 KB 主包及 500 KiB 告警保留。
- npm --prefix frontend audit --audit-level=high：0 项漏洞；不代表 Java 依赖审计。
- PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui：24 项全量通过；随后补充截图等待弹窗退场，并重跑相关 3 项 UI，通过。桌面及移动截图已实际查看，移动页面无整体横向溢出。采用本机 Chromium 151，不等于固定 CI 浏览器或真实后端 E2E。
- 通过现有 JDK JavaCompiler API 对实际 SyntheticPdf.java 独立类型编译并执行：中文长字段 4 页、重复字节一致、长度/页数/控制字符/双向格式字符/缺失字形边界拒绝通过。系统 javac 命令缺失，但同一 JDK 编译器 API 可用。不是完整 Spring 类型编译。
- 实际生成的[合成 PDF 测试夹具](../../frontend/ui-tests/fixtures/synthetic-fixed-report.pdf)为 537479 字节，SHA256 316f60077d221f2ba365bf35eba3167f74e3c4daa68713cd9f1a3215cba3c65c。pypdf 严格解析 4 页 A4、静态图像且无动作/注释；pdftoppm 实际渲染并逐页查看，中文、长字段换页、每页提示及页眉页脚正常。Fontconfig 报缓存目录不可写警告，未影响随包物理字体渲染，未更改权限绕过。
- PG17 临时无网络容器探针：V1–V16 后升级 V17、旧冻结历史保持、无自动产物种子、实际 SQL PREPARE/EXECUTE、固定字节/哈希、冻结绑定、不可变约束、CAS、自报唯一性及审计失败回滚通过，容器已清理。这是 SQL 探针，不是 Flyway/JDBC/Spring 集成测试。
- Java 全源码语法解析通过，仅语法检查。git diff --check、域间源码引用无环探针、T15–T18 祖先链、V1–V16 迁移/安全配置/CI/依赖字节对比和文档链接检查通过。

## 未运行及阻塞

离线执行现有 Maven 3.9.16 verify，使用 /tmp/pis-t08-maven/repository，缺少 Spring Boot 4.1.1 导入的 Zipkin 3.5.3、Brave 6.3.1、gRPC 等 BOM，exit 1，未进入完整 Java 编译或测试。日志 /tmp/pis-t19-maven.log。未尝试认证、切换下载镜像或绕过既有拒绝。

新增后端测试覆盖权限/隐藏对象、哈希及版本绑定、固定字节重试、并发生成/打印、打印自报、QC/资格变化、审计回滚、HTTP/CSRF、不可变迁移与渲染边界；这些 Spring/JUnit 测试尚未运行成功，不能以源码存在宣称通过。完整后端编译、verify、Flyway/JDBC/并发集成与正式 JAR 验证均未完成。

新增 frontend/e2e/output.spec.ts 是真实服务跨角色流程：创建/QC/领取/人工草稿/独立复核/模拟签署/产物下载及打印自报，未 mock HTTP。因 Spring 构建阻塞未运行真实 E2E。GitHub CI 依指示不查询、不等待、不 push，因此本地提交无完整 CI 验证。本次未使用真实患者、未发送或打印真实报告、未部署。
