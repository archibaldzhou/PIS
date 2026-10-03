# T20 补充、更正与版本链本地交付记录

已实现[先行开发 PRD](../prd/development-report-amendments-v1.md)与[ADR 0010](../adr/0010-append-only-report-amendment-chain.md)所述合成范围。完整后端、真实 E2E 和 CI 未验证，不声明可合并或投产。

## 环境、来源与保留范围

继续同一 /workspace/PIS，validation/t15-20261002，起始工作区干净，T19 为 9d2aa29cfe4e29cb5f90d6d82f2f403cd09a8d1d。T15–T19 全部历史保留，无历史改写、环境切换、GitHub 登录/凭据处理/push、OAuth/安全配置更改或部署。读取 AGENTS、工程 ADR、门禁、既有报告实现与测试，未发现额外适用的仓库技能。

当前环境直接上传的 02_全部UI原型.zip 已验证可读，SHA256 74f3305da4278f84c7660eb13625c1ff604174116d2fe13fbeffe85cf7a58291；实现前实际查看 UI-023 报告更正与补充 PNG。采用原版、新版、类型、理由、版本链及替代关系要素。真实送达和 ACK 不在本次范围。原招标 PRD 正文仍未取得，不把静态原型等同医院规则批准。

## 交付与接口

- 新增 V18：不可变 report_amendment、CAS report_chain_head、不可变 report_replacement。补充/更正不同类型，独立草稿 ID、新修订及原因，绑定确切原冻结签署/修订。唯一后继和病例根锁确保一个当前分支。
- 旧冻结修订、PDF 字节/哈希及审计不改。只允许通过合法新分支推进当前草稿指针。T17 保存继续 CAS；T18 新修订必须重新复核和模拟签署，原复核不继承。资格、QC、分配或新修订改变仍使审批失效。
- 新模拟签署同事务追加替代关系，下游固定 PENDING_NOT_SENT；未发送、无 ACK。新签署前原版仍为当前冻结版。T19 为新签署保存新 PDF；历史版直接读取原字节，新版就绪不能授权旧版打印。
- 前端新增“报告补充与更正”，显示当前/历史冻结版、待完成草稿、类型、原因、分页版本链及只读正文。历史 PDF 校验选中病例、产物、签署、修订及版本，并保留哈希验证。类型/历史/病例切换、取消、脏输入、迟到响应、双击及未知结果原键确认均覆盖。

新增前缀 /api/requests/reports/cases/{id}/amendments：

| 接口 | 内容 |
| --- | --- |
| GET ?page=1 | 链头、当前草稿/冻结版、是否待完成、当前资格/就绪及最多 20 条分支，page 上限 10000 |
| POST | confirmedCaseId、expectedVersion（初始0）、assignmentVersion、baseSignatureId、baseRevisionId、baseDraftVersion、kind、reason；必须 Idempotency-Key 与 CSRF |
| GET /snapshots/{signature} | 当前授权下的确切冻结修订、是否当前冻结版及对应历史 PDF ID |
| GET /output/{artifact}（既有病例前缀） | 精确历史产物元数据，不返回文件字节；历史 UI 只允许预览/下载 |

二进制仍用 T19 /output/{artifact}/bytes/PREVIEW 或 DOWNLOAD 的审计 POST。创建新版本要求当前领取人及复核访问资格、组织/病例和 QC/身份门禁；查询/历史/下载同样服务端鉴权。401 未认证、403 CSRF、404 对象/范围/资格不可用；400 类型/原因/输入，409 原版/链/分配陈旧或已有待完成草稿。回执原键重放重新鉴权，不重复分支。

取消输入不取消已提交版本；本次没有删除分支、退回原指针、送达、真实签署或部署接口。默认关闭的合成开关、现有安全链及依赖锁保持不变。

## 已执行验证

执行目录 /workspace/PIS，固定 Node 22.23.3/npm 11.21.0：

- npm --prefix frontend run test：87 项、14 文件通过。
- npm --prefix frontend run lint、run build：通过，包含 TypeScript；约 1166 KB 主包的 500 KiB 提示保留。
- npm --prefix frontend audit --audit-level=high：0 项漏洞，不代表 Java 依赖审计。
- PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui：27 项全量通过，含 3 项 T20 回归；完善历史只读提示后，这 3 项再次通过。使用本机 Chromium151、模拟 HTTP 的 UI 回归，不冒充真实服务 E2E。桌面/移动截图已实际查看，移动页面无整体横向溢出。
- PG17 临时无网络容器：V17 已有冻结报告/PDF 后升级 V18，原审计/事件及 PDF 保持、无自动分支；拒绝直接解冻，允许显式分支→编辑→新复核→新冻结→追加待替换关系；唯一后继、不可变约束及审计失败回滚通过。实际链查询 SQL PREPARE/EXECUTE 通过；两个并发后继事务只有一个成功，另一个明确 CAS 冲突。这是 SQL 探针，不是 JDBC/Flyway/Spring 集成。
- 现有实际 Java 渲染器生成新修订/新签署绑定的一页 PDF，重复渲染一致，新哈希不同且 PG 保存/读取原 PDF 字节完全相同。pypdf 严格解析、pdftoppm 实际渲染并查看，中文警示、修订号及页眉页脚正常。渲染器/字体未更改，沿用 T19 的有界栅格方案。Fontconfig 缓存不可写警告未影响随包字体，未改权限绕过。
- Java 全源码语法解析通过；不是完整类型编译。git diff --check、T15–T19 祖先链、V1–V17/安全配置/CI/依赖/字体/渲染器及原 PDF 字节比对、十域源码引用无环/公开边界探针及文档链接检查通过。

## 未运行与具体阻塞

本次使用现有 Maven3.9.16、/tmp/pis-t08-maven/repository 离线执行 backend/pom.xml verify，仍缺 Spring Boot4.1.1 导入的 Zipkin3.5.3、Brave6.3.1、gRPC 等 BOM，exit1，未进入 Java 编译/测试。日志 /tmp/pis-t20-maven.log。没有重新认证、替换镜像或绕过下载拒绝。

新增 Spring/JUnit 测试覆盖新旧字节/版本绑定、重复请求、越权、并发建链、草稿冲突、资格撤销、QC/草稿/分配失效、建链及新签署审计回滚、HTTP/CSRF/类型/原因、不可变性和 V17→V18 迁移；这些测试尚未执行成功。完整后端类型编译、verify、真实 PG 服务/HTTP/并发集成、正式 JAR 验证仍未完成。

frontend/e2e/amendment.spec.ts 使用独立 SYN-AMEND-001 合成夹具、真实登录及独立复核人员，覆盖旧报告/PDF、新补充草稿、不能继承签署、新复核/模拟签署/新 PDF、原 PDF 字节对比和再建更正。没有 HTTP mock；受后端构建阻塞未执行真实 E2E。CI 依指示不查询、不等待、不 push，当前提交没有完整 CI 证据。
