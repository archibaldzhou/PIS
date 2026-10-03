# T16 诊断分配、领取与转交本地交付记录

状态：已实现，本地适用检查通过；完整 Java/Spring/E2E 与 CI 尚未验证，不声明可合并或投产。依据[本次开发 PRD](../prd/development-diagnosis-assignment-v1.md)和[ADR](../adr/0006-synthetic-diagnosis-routing.md)。本次只做 T16，无报告编辑、签署、发布或部署。

## 恢复与来源

同一 `/workspace/PIS` 环境，原分支 `validation/t15-20261002`、干净基线 `e4e5b44c900437b952cae28c2913399995d1c17a`。不能凭本地远端跟踪引用可靠证明 T15 从未被其他会话发布，因此保留原英文提交历史，不 amend；本次起独立中文任务提交。不登录/处理 GitHub 凭据、不 push、不等待 CI。T01–T14 main 仍以前次父会话确认的 `a36fbff996a57180eb7edb64313b89a5345d6e71` 为已验证基线。

已读取根 AGENTS、README、工程 ADR/门禁及相关既有实现；根目录和 `.agents/.codex` 未发现额外适用技能文件。未发现独立的 T16 总计划正文，开发范围依据本次用户明确指令，不冒称核验缺失附件。直接上传 ZIP 在当前环境仍可读，SHA256 `74f3305da4278f84c7660eb13625c1ff604174116d2fe13fbeffe85cf7a58291` 一致，实际查看 UI-019 医生诊断队列；未把附件当作临床规则批准或写入仓库。

## 接口契约

前缀 `/api/requests/diagnosis`，沿用已有会话/CSRF及默认拒绝策略，未修改认证安全配置。

| 方法与路径 | 行为 |
| --- | --- |
| GET `/scopes/{id}` | state=ALL/UNASSIGNED/ASSIGNED/ACTIVE，page默认1、上限10000，pageSize默认20、最多50；授权范围队列与同快照计数 |
| GET `/cases/{id}` | 病例/申请/患者稳定ID、当前状态/版本/人员、就绪、本人功能权限、最多100名当前有效候选人员、最近100条追加事件 |
| POST `/cases/{id}/assign` | 分配 UNASSIGNED 给指定合格人员，生成 ASSIGNED v0 |
| POST `/cases/{id}/claim` | 合格人员领取未分配病例，或指定人员领取自己的 ASSIGNED 病例，变 ACTIVE |
| POST `/cases/{id}/transfer` | 当前 ACTIVE 持有人转给不同合格人员，变 ASSIGNED，需对方明确领取 |

写入携带 Idempotency-Key、CSRF及白名单DTO：expectedVersion（初始-1）、confirmedCaseId、targetUserId（领取必须null，分配/转交必须UUID）、reason（非空，最多2000）。客户端不能提交actor、组织、资格、报告或管理员覆盖标志。成功200；错误参数400、未认证401、CSRF403、未授权/不存在对象404；版本/身份/状态/未就绪/目标不可用409。无权、跨组织、不存在、资格过期目标统一 DIAGNOSIS_TARGET_UNAVAILABLE，不透露其存在性。

diagnosis_grant 必须叠加启用的 synthetic_only 账号、启用scope、有效workflow READ。can_assign 不自动给 can_diagnose；角色名称和管理员不赋权，资格只能是合成演练版本。不向生产种账号、资格或自动任务。目标查询及写入均限同scope，锁后再次核验资格撤销/期限。当前对象和身份版本服务端决定。

每次变更在申请根锁后要求 RECEIVED、至少一张 ACTIVE 玻片、全部 ACTIVE 材料有效 PASS、无申请身份隔离。NOT_ASSESSED、撤销、失效、来源隔离均不就绪。QC随后变坏保留历史归属并阻断后续命令；重放回执是历史成功记录，不改变当前门禁。

## 实现与前端边界

V14 新增 diagnosis_grant/assignment/event，唯一病例任务、复合归属FK、CAS版本触发器、只追加事件。V1–V13字节未变。T07保持唯一事务所有者，记录更新、事件、审计和回执同事务；同键异参拒绝，重放复核当前权限。新域无反向依赖，源码引用门禁扩展九域。SecurityConfiguration、CI、OAuth和依赖锁文件均未改。

UI沿用原型的队列与当前人员/材料就绪提示，实际服务端分页/过滤；非就绪明确阻断。表单核对病例UUID和原因，转交显示同范围合格人员，操作切换清空隐藏目标。脏输入换病例/页/范围有提示；未知命令保持原对象/版本/目标/键并阻断导航，双击单发；确认成功清除旧待确认提示。迟到的旧病例响应不能替换新病例。无报告正文或签署入口。

## 实际运行证据

工作目录 `/workspace/PIS`，固定 Node22.23.3/npm11.21.0：

- `npm --prefix frontend run lint`：通过。
- `npm --prefix frontend run test`：75项、10文件通过。
- `npm --prefix frontend run build`：TypeScript与Vite构建通过；主包约1123KB的500KiB警告仍保留。
- `PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui`：17项全量通过；最后修复重复选择当前病例保留脏输入后，另运行3项诊断UI回归通过。系统Chromium151，非固定CI浏览器。已检查桌面/移动端截图，页面整体无横向溢出，表格内部横向滚动。
- `npm --prefix frontend audit --audit-level=high`：通过，0项漏洞（本次查询时点）。不是Java依赖审计证明。
- 真实PG17临时无网络容器 SQL 探针：V13→V14升级、旧材料/标签数据保持、从源码提取的实际队列/资格/就绪SQL PREPARE/EXECUTE、无自动资格、未评估阻断/PASS就绪/身份隔离阻断、重复分配拒绝、CAS、事件不可改删、事件失败事务回滚及撤销资格过滤通过；临时容器已清理。不是Spring/JDBC/Flyway集成测试结果。
- Java源码语法解析、九域源码引用无环探针、V1–V13及安全/CI配置字节比对、diff检查通过。语法解析不是Java类型编译。

## 未运行/未通过及限制

Maven首次离线verify因默认 `/home/agent/.m2/repository` 不可写而失败；改用现有可写 `/tmp/pis-t08-maven/repository` 后，离线verify因缺少 Spring Boot 导入的 Zipkin、Brave、gRPC、Spring Security 等 BOM 而失败，尚未进入编译或测试。没有换镜像绕过之前的下载权限拒绝。因此完整 Java/Flyway/PG服务集成、锁等待并发、HTTP、正式JAR隔离和真实Spring E2E未运行成功；CI按用户要求不等待也不核验。

新增待完整环境执行：分配→领取→转交→受让人领取的归属/版本/历史、跨scope/人员越权、目标资格过期和锁等待撤销、陈旧版本与同键异参/撤权重放、身份/QC阻断、审计失败原子回滚、三种命令各自真实PG根锁竞争单胜者、HTTP认证/CSRF/字段白名单/不存在签署入口、V13升级校验和保持。真实E2E使用独立 SYN-DIAGNOSIS-001 合成患者及两账号，覆盖材料QC前提、四步流转和QC撤销后的阻断，无HTTP路由mock。

期间 exec-server 曾一次 transport disconnected；同一环境 `pwd` 核验随即恢复，文件和进度完整保留，未创建或切换环境。失败时SQL探针尚未启动，恢复后重新建立并成功执行。不宣称部署、临床或医院资格验收完成。
