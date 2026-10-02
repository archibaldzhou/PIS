# T14 技术QC与隔离验收记录

状态：验证分支完整CI已通过；main整理提交后另行核验。依据[开发规格](../prd/development-quality-v1.md)。重新验证已授权上传ZIP SHA256 `74f3305da4278f84c7660eb13625c1ff604174116d2fe13fbeffe85cf7a58291`，实际查看UI-014/037及生成的移动端QC截图；附件未入库。

## 契约与边界

| `/api/quality` 路径 | 请求与行为 |
| --- | --- |
| GET `/requests/{id}` | 最多100材料，当前QC版本、判定与有效状态；READ+QC |
| GET `/materials/{id}` | 确切材料/患者/病例/源任务版本、最近100判定/100事件；异常放行固定false |
| POST `/materials/{id}/assess` | expectedVersion（首次-1）、materialVersion、taskVersion（直制null）、confirmedMaterialId、standardVersion、outcome、reason |
| POST `/materials/{id}/revoke` | expectedVersion、confirmedMaterialId、reason；撤销隔离，不改旧判定 |
| POST `/materials/{id}/rework` | 同上；另需PROCESS，创建同盒同类来源关联的新技术任务，记录repairTaskId，原材料REWORK_REQUIRED |
| POST `/materials/{id}/exception-release` | 同上；服务端始终409 QC_EXCEPTION_RELEASE_DISABLED，没有管理员旁路 |

所有写携带 Idempotency-Key、Cookie及CSRF。字段白名单、当前账号/范围、合成dev/test开关不变。QC为独立默认false权限，依赖READ；不自动授予既有管理员/工作流角色。正常写200，版本/来源/隔离409，未认证401、CSRF403、无权资源404。标准仅SYN-MATERIAL-QC-1，PASS/FAIL/PENDING/IDENTITY_MISMATCH均显式选择；服务器记录actor与时间。

NOT_ASSESSED不是通过：保留T13身份登记和未质检初始标识标签兼容路径，绝不作为交片/报告/扫描发布放行依据。一旦QC登记，非有效PASS阻断材料标签、普通同盒技术任务和材料派生；隔离蜡块影响其玻片。容器身份标签仍是T10身份标识，不是QC放行。真实交片批次及下游临床消费未实现，接入时必须明确要求有效PASS。

返工通过QC事务调用TechnicalService的强制已有事务接口，新建T12关联任务并留技术事件；不嵌套T07事务。原判定保留、原实体保持隔离。只有明确关联的返工任务能继续合成处理并供重切创建新玻片；同盒其他隔离仍阻断。新玻片不继承旧PASS。直制无盒级任务，拒绝伪造返工任务，另建明确直制实体后独立QC。身份错误持续隔离，普通重新判定、撤销和返工不能解除。

材料版本变化追加INVALIDATE事件，源技术任务返工追加REWORK事件并关联新任务；读取再比较确切材料/任务版本，源蜡块隔离投影为SOURCE_QUARANTINED。质检是开发事实登记，不能证明实物质量或临床工艺。

## 原子协议与迁移

沿用主体→scope→grant→申请→实体/任务的短事务锁序。QC、标签、材料和技术写都锁同一申请；锁后重读权限、版本、来源、状态。新判定、QC指针CAS、返工任务、追加事件、审计和幂等回执同事务；审计故障全部回滚。重放重新鉴权，同键异参拒绝。撤销先完成则新消费拒绝；消费先完成保留历史，随后隔离阻断进一步使用；纸面输出无法撤销。

V12新增quality_head/assessment/event与can_qc，无业务种子；V1–V11字节保持。复合FK绑定医院/申请/病例/患者/材料和任务范围，判定与事件只追加；身份隔离状态数据库也拒绝转回PASS。技术任务加复合唯一键供FK使用，需要锁表；仅合成小库验证，真实大库迁移需评估锁/容量及分阶段方案。QualitySubjects公开来源契约避免quality依赖material实现；QualityGate由消费域在授权/申请锁后调用，源码无环检查扩展至七域。

## 实际验证

本地固定Node22.23.3/npm11.21.0：lint、69项单测、类型检查/构建、12项UI测试通过。UI使用系统Chromium151，已检查移动端截图；包含QC状态保留、切操作脏提示、隐藏字段清空、双击/断网不乐观通过、原键重试、导航阻断。主包超过500KiB警告仍保留。

真实PG17无网络临时容器SQL探针：V1–V11初始化、V12增量升级、旧材料/标签快照保持、正常更新、身份隔离不可回PASS、判定/事件不可改删通过；容器清理完成。源码七域无环探针和旧迁移字节比对通过。以上不是Java/Flyway集成测试通过证明。

已新增待CI执行：V11→V12升级校验和/旧材料/默认权限/历史约束；完整FAIL→返工新任务→重切新玻片→独立PENDING/PASS→撤销；跨病例、身份不可解除、旧任务版本、同键异参/撤权重放；新判定及返工审计回滚；撤销与标签/新玻片竞争（观察实际PG锁等待）；直制无任务及HTTP认证/CSRF/白名单；普通技术返工失效传播与新任务关联。真实E2E覆盖完整QC返工链和标签拒绝，独立SYN-QC-001合成患者/就诊，无路由mock。

本地Maven依赖此前429、下载及Actions权限拒绝未换路绕过。完整Java/Flyway、真实Spring E2E、锁定浏览器、正式JAR隔离和独立依赖审计待父会话核验确切验证SHA。未部署，不临床启用，不包含T15。

## 首轮CI失败与SQL绑定修复

父会话核验 `01a94b254fa202a8006baf78ff326535b5d0eb75` 的 CI 37058196434 / verify 111007990597：后端221项0 failure、7 errors，均为QualityGate事件INSERT的7列误写8占位符。已修为7参数，保留全部失败回归，并补充首个ASSESS事件的null关联任务、判定ID、版本、操作者/原因，以及REWORK非空关联任务的字段往返断言。没有改迁移或降低安全断言。

本地对quality、MaterialQualitySubjects、TechnicalService共42条字面量JDBC语句做占位符/参数数量审查通过（不包含动态SQL）；动态where语句人工核对为一个UUID绑定。真实PG17执行从修复后QualityGate源码提取的PREPARE/EXECUTE七参数语句，含null关联任务，通过；同时确认升级与历史/身份隔离约束，临时容器已清理。首次探针遇到PG初始化临时服务与正式服务切换，等待正式启动后重跑通过。探针不是Java/Spring/JDBC集成测试结果，完整回归仍待修复SHA的CI。

最终验收：父会话确认验证SHA `11fe584bce1048d4fc34c202d8802b724f2a4b79` 的 CI 37059074063 completed success，verify 111010899562 与独立依赖审计成功，PR-only review按原条件跳过。main保留已验证实现，仅更新验收说明和移除验证分支触发。未部署，不临床启用。
