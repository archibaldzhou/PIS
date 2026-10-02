# T13 材料身份与重切谱系验收记录

状态：开发实现与本地前端检查完成，验证分支完整 CI 待核验；尚未整理 main。
依据 [开发规格](../prd/development-materials-v1.md)。本环境已通过上传下载工具取得原型 ZIP（10428559 字节，SHA256 `74f3305da4278f84c7660eb13625c1ff604174116d2fe13fbeffe85cf7a58291`），实际查看 UI-011/012，T13 再次查看 UI-012；已检查桌面与移动端实际 UI 测试截图。附件未提交仓库。

## 接口与原子校验

Cookie/CSRF、默认关闭 dev/test、synthetic_only、当前主体与范围策略沿用基线。材料读取和操作均需 READ+MATERIAL；标签操作保持独立 PRINT/REPRINT，不默认授予。所有写入携带 Idempotency-Key，DTO 拒绝未知字段，原因必填且最多 2000 字。

| `/api/materials` 路径 | 方法、输入与结果 |
| --- | --- |
| `/requests/{id}` | GET 当前申请、最多100材料及最多50个已完成合成技术任务 |
| `/{id}` | GET 实体、来源 ID 和最近100项追加事件 |
| `/lookup?barcode=…` | GET 精确条码反查，校验受限Code39及授权 |
| `/requests/{id}/blocks` | POST requestVersion、taskId/taskVersion、confirmedCassetteId、reason；201新蜡块 |
| `/requests/{id}/direct-slides` | POST requestVersion、confirmedContainerId、reason；201直制玻片，无虚构蜡块或盒级任务 |
| `/{block}/slides` | POST blockVersion、taskId/taskVersion、confirmedBlockId、reason；201新原片 |
| `/{slide}/recut`、`/{slide}/deeper` | POST sourceSlideVersion、taskId/taskVersion、confirmedSourceSlideId、reason；201独立新玻片 |
| `/{id}/void` | POST expectedVersion、confirmedMaterialId、reason；200，蜡块作废同事务传播到其有效玻片 |

新实体 UUID、显示号及条码分离；一项包埋任务最多一个蜡块，由唯一约束保护。创建原片增加源蜡块版本，重切/加深增加历史源玻片版本，保留旧实体及追加事件。历史源片可以已作废，实际源蜡块必须有效。直制不支持伪装成有蜡块的重切。条码反查和所有 ID 读取重新检查范围；400 字段错误、401 未认证、404 隐藏不可访问资源、409 版本/来源/状态冲突，沿用统一 ProblemDetail。

T07 事务内锁定主体→scope→grant→申请→材料UUID序；技术任务、材料和标签写入共同锁定申请，因此新材料、作废与标签操作串行核验最新来源。使用版本 CAS，业务、追加事件、稳定标签身份、审计、幂等回执同事务。审计失败全部回滚；重放重新鉴权，不重复分配实体。作废只保留历史，不删除、不恢复。

## 标签与迁移兼容

增加 GET `/api/labels/materials/{id}`、POST `/api/labels/materials/{id}/jobs`（requestVersion/materialVersion）、POST `/api/labels/jobs/{id}/verify-material`（materialId/barcode）。其余标签状态、重试、重打、取消复用 T10，重打只生成标签任务。Job 增加 materialId/targetId，旧容器接口和字段继续有效；材料 Job 的 containerId 为空。两个目标分别使用 SYN-CONTAINER-1 / SYN-MATERIAL-1。打开预览及打印前重新读取服务端当前状态，阻断已作废材料；纸面标签无法远程撤销。

V10 新增 material_entity/material_event、默认 false 的 MATERIAL；标签原表增量支持两种目标，保留旧数据。旧 label_job.container_version 对材料快照存目标材料版本，旧容器含义不变。复合 FK 约束患者/医院/病例/盒/任务/蜡块/源玻片一致，身份及事件不可改。V1–V9未改；迁移没有业务种子。兼容和锁表边界见 [ADR](../adr/0004-material-label-targets.md)。

## 实际验证与待验证

本地固定 Node22.23.3/npm11.21.0：lint、67项单测、类型/构建、11项 UI 测试通过；UI 使用系统 Chromium151，并非 CI 锁定浏览器证明。覆盖材料路径切换隐藏字段清理、脏表单、双击、未知结果冻结及原键重试、移动端、失效标签预览。构建主包超过500KiB警告仍保留。

真实 PostgreSQL17 无网络临时容器：V1–V10逐个事务 SQL 空库探针通过；另一个 V9 合成旧容器标签升级 V10 探针确认身份 target_id 回填、material_id 为空及旧事件数量保留。容器已清理。此探针不是 Flyway/Java 集成测试通过证明。

已新增但本地未运行的 Java/真实服务端测试：V9升级完整标签快照和校验和、材料/源/跨患者复合FK、直制与重切谱系、稳定条码及T10重打兼容、审计故障回滚、撤权重放、低权限HTTP/CSRF/字段白名单；并发蜡块、重切和作废竞争均观察真实 PG 锁等待。真实 E2E 包含常规材料→原片→重切→加深→标签→作废及独立直制流程，使用不同合成患者/就诊。架构源码检查扩展为六域无环及 processing 公开服务边界。

本地 Maven 依赖此前429，下载及 Actions 权限拒绝未换路绕过。完整后端 verify、Flyway、正式 JAR 测试隔离、真实 Spring E2E、锁定浏览器和独立依赖审计待父会话核验验证分支确切 SHA。CI 未成功前不宣称 T13 验收完成，不合并 main、不部署。

留待后续：真实工艺、直接制片技术任务、冰冻及其他特殊路线、医院编号/标签、临床岗位复核/放行、设备、扫描、诊断报告、数字阅片及 AI。本任务仅合成开发登记，不用于临床。

## 首轮 CI 失败与向前修复

父会话核验 `5ed5a93c0bf88cd9caff5073112aed48c8b5cd56` 的 CI 37051863784 / verify 110986953355：后端211项，1 failure、8 errors。不是通过结果。V10新增存储生成列在 BEFORE UPDATE 阶段尚未计算，整行 JSON 比较误认为身份变更，导致正常标签和材料更新失败；另一个迁移断言把 Flyway schema 创建记录计入固定行数，漏查V9。

新增 V11（V1–V10逐字保留），只将标签快照及材料身份的校验触发时机调整为 AFTER UPDATE，继续调用原比较函数，包括所有不可变字段，异常仍使语句及事务回滚。没有关闭触发器或删除安全断言。历史校验改为按非空迁移版本选取，不依赖安装行数；补充 V9→V10→V11 旧标签和 V10→V11 已有材料升级、升级前故障重现、正常版本/状态/尝试次数更新、快照/身份改写拒绝及失败后的原行不变回归。

本地真实PG17 SQL探针已通过：V1–V10初始化、两种误拒绝重现、V11升级后原快照保留、正常更新、不可变字段拒绝及语句回滚、历史拒绝删除。临时无网络容器已清理。新增 Java/Flyway 回归仍待修复提交的完整CI，不把SQL探针当作Java测试。此次未改前端，沿用前次67项单测/11项UI结果，没有重复运行。

## 第二轮 CI 与登录测试隔离

父会话确认 `d2ff1895ee16db9e226ffad66facae4c0e82a488` 的 CI 37053025002 / verify 110990795582 后端通过；真实浏览器26项中25通过，接收补充资料场景在登录后等待工作区按钮超时。当前日志未附HTTP登录响应。源码检查发现认证错误场景和所有业务场景共享同一账号，整套真实登录超过原有限流20次/5分钟；T13新增登录使该共享预算问题暴露。

将业务E2E迁移到独立、显式授权的合成账号，认证测试继续原账号，交接继续独立接收者。未更改任何生产限流/认证逻辑，未增加超时、重试或删除断言。公共业务登录助手断言登录204、当前username/displayName及页面登录身份，再进入工作区；后续即使登录失败也能得到具体响应断言。正式JAR隔离检查增加新测试账号标识。

新增限流回归证明两个账号预算独立，同时仍受原100次来源上限。已在本地编译运行实际生产 LoginAttemptLimiter 的独立Java探针，通过账号隔离及来源上限断言；这不是JUnit/Spring全量测试。前端lint/typecheck通过。完整JUnit和26项真实E2E待新SHA的CI核验。
