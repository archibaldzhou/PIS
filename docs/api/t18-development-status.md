# T18 合成复核与模拟签署本地交付记录

已实现，完整后端与真实Spring E2E、CI未验证，不声明可合并或投产。依据[先行开发PRD](../prd/development-report-review-v1.md)及[ADR 0008](../adr/0008-synthetic-report-review.md)。无临床、CA或法律签署效力。

## 环境与来源

同一/workspace/PIS、validation/t15-20261002，起始工作区干净。核验T15 e4e5b44c900437b952cae28c2913399995d1c17a、T16 f6ad327f9d5550195483de1f0fb8e564027b4033、T17 e0bba76f37e620f8d41564e91a988df96f442eb5均在原历史中，无amend、环境切换、GitHub登录/凭据处理/push、安全配置更改或部署。

读取根AGENTS、工程ADR、既有PRD与门禁及关联实现，未发现额外适用仓库技能。直接上传02_全部UI原型.zip在当前环境可读，SHA256 74f3305da4278f84c7660eb13625c1ff604174116d2fe13fbeffe85cf7a58291。先查看UI-022报告审核与签发实际图片，再实现身份/版本、人工字段、意见、退回和模拟确认；原型的真实签署服务、CA及发布未实现。原招标PRD正文仍未取得，不把原型当医院规则批准。

## 接口和流程

现有会话/CSRF保护下，前缀/api/requests/reports/cases/{id}/review：

| 方法 | 行为 |
| --- | --- |
| GET | 当前确切草稿、模板、分配/流程版本、依赖摘要、显式策略、资格与当前派生就绪状态 |
| GET /history?page=1 | 每页20条不可变事件，page 1–10000，当前权限重新校验 |
| POST /APPROVE | 合成复核通过，要求就绪、非空人工诊断文本及策略规定的作者分离 |
| POST /RETURN | 明确原因退回；保留修订，新增修订后才能再次复核 |
| POST /SIMULATE_SIGN | 明确simulationAcknowledged=true，消费当前有效复核，执行人员分离并冻结原草稿 |

写入DTO包含expectedVersion（初始-1）、confirmedCaseId、revisionId、draftVersion、assignmentVersion、templateCode/version、dependencyToken（64位十六进制）、reason及simulationAcknowledged；同时必须有Idempotency-Key。其他两动作的simulationAcknowledged必须false。拒绝未知字段和无效类型。所有动作要求有效ACTIVE分配、资格及QC/身份门禁，非就绪不能通过退回绕过。

401未认证，403 CSRF，404病例/资格不可用；400输入/分页不合法；409陈旧依赖或版本、身份不符、职责分离、未配置策略、未就绪、退回后未重修、模拟确认缺失或冻结。没有/sign、发送、PDF或CA接口。历史回执只代表原请求成功；重放仍校验当前权限。

V16新增复核策略/独立资格、事件与CAS头；V1–V15不改。策略/事件不可改删；复合FK绑定同病例确切修订及原复核。资格代次和原始依赖快照阻止撤销再恢复复活旧审批。报告修订、模板、分配、QC或依赖资格变化使APPROVE显示STALE；已模拟历史保留且不可再次签署/编辑。T07事务内原子审计，正文与依赖证据不写入通用audit_event。

生产迁移不种策略、资格或操作记录。合成模式默认关闭，仍只接受dev/test与synthetic_only账号。测试夹具显式安装两个人员分离策略与独立SYN-REVIEW-001合成病例，不使用真实患者或自动诊断。

前端使用原型布局要素并明确模拟边界：展示确切修订、模板与人工字段；原因与病例核对必填；动作切换确认清空、取消保留；病例/范围/离开脏提示；迟到响应丢弃；双击单发；未知结果冻结原输入原键确认；确定冲突保留输入，不自动覆盖。历史独立分页，模拟签署须再次明确确认，成功后冻结。已查看桌面/移动截图，无页面整体横向溢出。

## 已执行检查

执行目录/workspace/PIS，固定Node22.23.3/npm11.21.0：

- npm --prefix frontend run lint：通过。
- npm --prefix frontend run test：81项、12文件通过。
- npm --prefix frontend run build：类型检查与Vite构建通过；主包约1142KB，500KiB告警保留。
- PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui：21项全量通过；补充分页历史后另跑2项复核UI回归通过。系统Chromium151，不替代固定CI浏览器或真实服务E2E。
- npm --prefix frontend audit --audit-level=high：本次0项漏洞；不代表Java依赖审计。
- PG17临时无网络容器探针：V15→V16、旧材料/标签来源保持、源码提取的实际复核写入/头/CAS与资格/QC查询PREPARE和EXECUTE、无自动策略或授权、资格代次不能回退、策略/事件不可改删、模拟后草稿冻结、审计失败事务回滚通过。临时容器已清理。这是SQL探针，不是JDBC/Flyway/Spring集成测试。
- Java语法解析通过，不是类型编译。最终git diff --check、十域源码引用无环探针、T15–T17祖先链、V1–V15迁移/安全与应用配置/CI/依赖字节比对及文档链接检查通过。

首次前端类型检查发现useRef缺少初值，修正后通过；首次PG探针将PREPARE语句命名为保留字grant导致探针语法错误，更名后全量探针通过。没有降低测试或绕过失败。

## 未验证与阻塞

本次使用现有Maven3.9.16和可写/tmp/pis-t08-maven/repository运行离线verify，缺少Spring Boot4.1.1导入的Zipkin3.5.3、Brave6.3.1、gRPC等BOM，未进入Java编译/测试。日志/tmp/pis-t18-maven.log。没有更换镜像、下载路径或绕过之前的权限拒绝。

因此完整后端编译/verify、Flyway及真实PG服务/HTTP/并发集成、正式JAR隔离和真实Spring E2E未运行成功。新增待执行测试涵盖人员分离与管理员拒绝、跨scope、确切模板/修订/分配、资格撤销及锁等待撤销、撤销恢复不复活、退回重修、QC/草稿/分配竞争、双复核/双模拟签署及复核与模拟签署竞争、审计回滚、幂等与HTTP白名单、迁移及分页历史。不能将测试源码存在当通过。

新增frontend/e2e/review.spec.ts使用真实登录与两个独立合成账号，覆盖创建/QC/领取/人工草稿、作者不能自复核、另一人员复核、原作者模拟签署、原复核关联及草稿冻结，无HTTP mock；后端依赖阻塞，未执行。GitHub CI依用户要求不等待、不查询、不push，当前本地变更没有完整CI验证。T19输出、T20更正、报告发送和部署均未包含。
