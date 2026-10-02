# T12 技术任务与交接验收记录

状态：T12 验证分支完整CI已通过；main整理提交推送后另行核验。

依据[开发PRD](../prd/development-processing-v1.md)。当前环境再次通过上传文件下载工具落地原型ZIP，字节数10428559，SHA256 `74f3305da4278f84c7660eb13625c1ff604174116d2fe13fbeffe85cf7a58291`，与原授权上传一致。实际查看UI-010/011/012，对照批次状态、来源盒、人员核对/交接、异常和返工。原型的医院示例工艺、硬件运行和蜡块/玻片不自动转为已实现功能。

## 接口与数据

认证Cookie/CSRF、默认关闭dev/test、synthetic_only和当前范围校验沿用基线。独立READ+PROCESS；交接需要HANDOFF且HANDOFF依赖PROCESS，不需要给接收人GROSS写权限。公开 `GrossService.released` 只读来源契约供processing域使用，返回已完成记录的有效取材盒和容器谱系，不引用Gross内部实体/Repository，不扩大CASE权限。

| 方法及 `/api/technical` 下路径 | 契约 |
| --- | --- |
| GET `/requests/{id}` | 当前病例、当前actor、有效来源盒及最多50项任务 |
| GET `/tasks/{id}` | 当前任务和最近100条追加事件，隐藏跨范围资源 |
| POST `/requests/{id}` | requestVersion、cassetteId、kind、可选predecessorId、reason；201 |
| POST `/tasks/{id}/claim` | QUEUED领取，服务器设置owner |
| POST `/tasks/{id}/offer` | ACTIVE当前owner发起待确认交接 |
| POST `/tasks/{id}/accept` | 另一授权用户核对后接收，保留双方身份 |
| POST `/tasks/{id}/withdraw` | 原owner撤回待确认交接 |
| POST `/tasks/{id}/finish-simulation` | 当前owner记录合成演练完成，无真实设备/工艺含义 |
| POST `/tasks/{id}/abort` | 中止保留历史，已领取任务仅owner操作 |
| POST `/tasks/{id}/rework` | 终态新建同来源/类别/前置的返工子任务，201；原任务版本加一、状态不覆盖 |

所有任务动作传expectedVersion、confirmedCassetteId和1–2000字原因，携带Idempotency-Key。服务器使用完整资源/输入摘要，同键异参拒绝，重放重新鉴权。写操作按主体→scope→grant→申请→任务锁定，读取完整任务视图锁申请，版本CAS和前置/来源/owner/权限与审计回执同事务。返工只允许一个直接子任务，后续沿链追加。正常动作200；业务/版本/前置409；非owner403；无权资源404；字段错误400，沿用ProblemDetail/trace。

V9创建technical_task与technical_event及PROCESS/HANDOFF默认false授权列；复合FK保证前置/返工/取材盒属于同一医院、申请、病例、记录和盒。身份不可改、事件不可更新/删除，当前状态/owner/version由命令维护。V1–V8保持不变；迁移无业务种子。账号与合成场景仅在测试classpath，新增双用户E2E账号不会进入正式JAR。

## 验证边界

本地真实PG17已在无网络可丢弃容器逐个事务执行V1–V9 SQL探针并清理，验证SQL可应用，不能替代Flyway/Java测试。前端65项单测、9项UI浏览器测试、lint、类型检查和构建通过；已实际查看技术页面移动端截图。切任务回归发现并修复表单与历史组件重复React key导致的旧输入残留。构建仍报告主包超过500KiB，未关闭警告。

新增测试覆盖：V8升级及校验和、双用户交接/自接收/错误盒/旧owner拒绝、独立权限及重放撤权、前置拒绝与不同路线、复合FK、中止和返工不可变来源、审计故障回滚、领取/交接竞争且观察PG锁等待、HTTP认证/CSRF/业务拒绝、真实双浏览器账号完整操作、UI脏输入/切任务清理/未知结果原键重试/移动端。

本地Maven依赖下载此前429，下载/Actions拒绝未换路绕过；完整后端与真实Spring E2E交由已授权验证分支CI执行。推送后父会话核验确切SHA，成功后T12单独整理main，不部署、不强推。

## 留待后续

此任务没有真实跨病例设备批次装卸、程序参数、硬件回执、设备联机、温度/时长/厚度或临床质量放行。允许手工合成演练完成，不冒称真实处理完成。T13另建立蜡块/玻片编号及重切加深的新实体谱系；本任务只保存技术任务，不创建占位玻片。医院岗位、复核、工艺、接口、安全与临床验证仍待批准。

新增TechnicalDomainBoundaryTest检查accession/grossing/processing三个工作流域的源码引用无环，以及processing只引用公开跨域服务边界；这是有界源码检查，不宣称全项目字节码架构分析。其执行同样待Java CI。

最终验收：父会话确认验证SHA `5a1bae318a337626e1ca709acd15c2e4e24661f4` 的 [CI 37046573244](https://github.com/archibaldzhou/PIS/actions/runs/37046573244) completed success，verify 110969371987 与独立依赖审计成功，PR-only review按原条件跳过。main整理保留此已验证实现，仅更新说明和移除验证分支触发；无部署/真实工艺验收。

父会话另已确认 main `f6da5454b7a3b87564a3cf212fe897e1e6c57b25` 的 CI 37047155677 success。
