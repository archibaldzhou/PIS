# T17 结构化报告草稿本地交付记录

已实现；完整后端、真实Spring E2E和CI未验证，不声明可合并或投产。依据[开发PRD](../prd/development-report-drafts-v1.md)及[ADR 0007](../adr/0007-immutable-report-drafts.md)。

## 恢复与原型

同一/workspace/PIS、validation/t15-20261002；基线f6ad327f9d5550195483de1f0fb8e564027b4033。T15、T16及已有历史完整保留，无amend、push、登录、凭据或安全配置更改。根AGENTS及相关工程约束已读取，没有新增适用仓库技能。

已验证本环境授权直接上传02_全部UI原型.zip可读，SHA256为74f3305da4278f84c7660eb13625c1ff604174116d2fe13fbeffe85cf7a58291，并实际查看UI-020报告编辑图片。保留身份条、人工大体/镜下/诊断/备注、模板及修订历史；不实现原型中的AI、自动保存、复核或签署。原招标PRD正文仍不可读，范围依据用户明确T17指令与本次先行编写的开发PRD。

## 契约与实现

沿用受保护前缀/api/requests/reports/cases/{id}及现有会话/CSRF：GET读取详情和模板；GET /history?page=1按20项分页；POST /draft保存人工草稿。无签署、发送或CA接口。

保存白名单：expectedVersion（首次-1）、assignmentVersion、confirmedCaseId、templateCode、templateVersion、fields、reason及Idempotency-Key。字段精确匹配不可变模板模式；四文本各≤4000字符，fields序列化UTF-8≤32000字节。结构化版本增加0–1000整数sampleCount及显式布尔manualChecked；拒绝null、缺项、未知项和类型强转。草稿允许空文本，不能据此认定报告完成。

未认证401、CSRF403、无资格/非当前ACTIVE医生/越权病例404；模式或类型错误400；身份不符、陈旧草稿或分配版本、模板不可用、QC未就绪409。管理员和分配员没有隐式编辑权。转交后旧医生不能读取或重放，受让人领取后才能访问历史。

V15新增不可变模板、不可变修订及CAS草稿头，复合外键约束头的病例/版本/修订一致。T07保持唯一事务所有者，根锁后重验授权、身份和QC，修订/头/审计/回执原子提交。历史分页，人工作者、原因、时间及分配版本可追溯。生产迁移不种模板或授权，合成模式默认关闭；测试仅使用独立SYN-REPORT-001和人工文本。

前端病例/模板切换、脏提示、取消、迟到响应丢弃、未知结果冻结、双击单发、原键原输入重试和冲突保留输入已实现。桌面及移动截图已实际检查，无页面整体横向溢出。

## 本地运行证据

工作目录/workspace/PIS，Node22.23.3/npm11.21.0：

- npm --prefix frontend run test：78项、11文件通过。
- npm --prefix frontend run lint：通过。
- npm --prefix frontend run build：类型检查和Vite构建通过；主包约1133KB，原有500KiB告警保留。
- PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui：19项全量通过。系统Chromium151，不替代固定CI浏览器或真实服务E2E。
- npm --prefix frontend audit --audit-level=high：通过，本次查询0项漏洞；不是Java依赖审计。
- 十域源码引用无环探针、V1–V14/安全/CI/依赖基线字节比对、文档链接及git diff --check通过。
- 真实PG17无网络临时容器SQL探针通过：V14→V15、旧材料/标签等来源保持、从ReportService提取的实际插入/当前/历史SQL PREPARE与EXECUTE、无自动模板、模板及修订禁止改删、复合FK/CAS拒绝、审计失败事务回滚。临时容器已清理。这不是Flyway/JDBC/Spring测试结果。
- Java语法解析通过，仅解析不是类型编译。

## 未验证及阻塞

本次执行现有Maven3.9.16离线verify，使用可写/tmp/pis-t08-maven/repository；Spring Boot4.1.1导入的Zipkin3.5.3、Brave6.3.1、gRPC等BOM缺失，未进入Java编译或测试。日志/tmp/pis-t17-maven.log。没有更换环境、镜像或绕过此前下载权限拒绝。

因此完整后端verify、Flyway/真实PG服务测试、事务与锁等待并发、HTTP集成、正式JAR隔离，以及真实Spring E2E均未运行成功。新增源码覆盖模板绑定与历史、权限撤销/转交、严格类型、陈旧分配与草稿版本、幂等、实际PG锁竞争单胜者、审计回滚及HTTP错误边界；不能把测试源码存在当成通过。

新增frontend/e2e/report.spec.ts覆盖真实登录→合成材料QC→领取→两个模板人工保存→历史绑定→陈旧版本拒绝→QC撤销阻断，无路由mock；后端依赖阻塞，未执行。GitHub CI依用户要求不等待、不查询、不push，当前提交无完整CI验证。无T18、AI诊断、部署或临床动作。
