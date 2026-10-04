# T39 本地交付与验证

## 基线与范围

依据父会话确认 T38 `bf2dcb07108632c6f27248cee85490c29fd9a740` 完整 CI 成功，核验远端 main `dac56498dca7044cf49e4c85e44ef3f1bea172d5` 为其祖先，在原有 main 工作树快进并普通推送。远端 main 确认为 **bf2dcb07108632c6f27248cee85490c29fd9a740**，没有 squash/改写历史。T39 在同一 `/workspace/PIS`、独立 `validation/t39` 开发，未合 main，不查询 Actions。

已读 AGENTS.md 及现有接口、身份、T21 幂等/报告投递契约；仓库只有根 AGENTS，无附加 .agents/skills。先写 [开发PRD](../../prd/development-hospital-adapter-v1.md)，并实际查看同环境已合法落地的 UI-032_接口消息与重放.png。技术方案见 [ADR0029](../../adr/0029-local-hospital-adapter-contract.md)，协议见 [API](../../api/local-hospital-adapter.md)。

实现 V36 七张空表、身份约束、不可变消息/入箱/业务台账/历史、明确迁移基线36与35→36升级契约。HIS关联观察、收费请求台账、设备观察使用自定义 SYN-HOSPITAL-1；EMR是T21原服务的路由别名，复用原冻结版本与替换机制。当前资格和来源/病例绑定、独立授权、CAS、原子审计、输入上限、去重、乱序NACK、30秒尝试租约、3次有界退避、死信、人工修复、取消和ACK后独立对账。没有外呼、支付、仪器操作或真实标准兼容声明。

新增9项 RequestWorkflowTest（含真实HTTP、并发、审计回滚、应用子进程崩溃恢复和EMR同幂等域），新增迁移测试；新增1项真实服务E2E和4项合成HTTP浏览器UI契约。真实应用子进程测试在接收提交后 halt(23)，重启后验证同一入箱/业务记录、有限重试和ACK/对账；本地未运行，必须由CI验证。

## 当前已执行

原环境：Java21、Node22.23.3/npm11.21.0、系统Chromium；PG17固定镜像digest见探针脚本。仅合成数据。

- `frontend/npm test`：**206项通过**，36文件，见 frontend-tests.txt。
- `npm run lint`、`npm run build`（含类型检查）：通过；最后测试改动后再次 lint/typecheck 通过。保留原大chunk提示，无门禁降级。
- `PIS_UI_BROWSER_PATH=/usr/bin/chromium npm run test:ui`：**112项全套通过**（ui.txt）。随后增加独立迟到队列响应回归和截图稳定等待，T39 **4项复测通过**（ui-adapters-final.txt）。两次结果分别陈述，不把针对性复测称为新的全套执行。
- `python3 backend/src/test/probes/ai-postgres.py`：真实PG17 V1–V36、核心/来源绑定、唯一来源ID、入箱digest、并发CAS、回滚、不可变约束通过（postgres.txt）；这是SQL探针，不是Spring/JUnit/HTTP。
- `bash backend/src/test/probes/viewer-validation.sh`：独立provider合成PNG/坐标/像素/并发/取消检查通过（provider.txt），不代表完整应用或真实WSI。
- Java语法解析、词法作用域、HTTP helper受检异常声明探针通过（java-source.txt），**不是Java类型编译或JUnit**。
- `PIS_TEST_DB_PASSWORD=synthetic-discovery-only npm run test:e2e -- --list`：发现**47项/28文件**（e2e-discovery.txt）；只是发现，没有执行真实服务E2E。
- `adapter-ui.png` 已实际打开检查。真实Chromium渲染，但使用合成HTTP路由；界面显示“非医院联通”、来源/病例、QUEUED/本地记录分离、版本/hash、人工操作原因、追加历史。不能替代真实HTTP验证。

首轮局部UI失败为定位器同时命中操作选择与原因文本框；改为精确label后保留全部断言。证据摘要见 initial-ui-selector-failure.txt。审查发现最新迁移序列断言还需显式更新至36，已保留固定版本契约而非动态接受任意迁移。

## 未运行/阻塞与交接

离线 Maven verify 在POM解析阶段失败（maven-offline.txt），原缓存缺 Zipkin/Brave、Cassandra、Groovy、gRPC、Jackson/JUnit 等BOM；未重复被拒的依赖下载。**完整后端编译/测试、真实服务E2E、T39实际应用进程崩溃/重启测试和完整CI均未验证**，由父会话读取验证分支CI。不能将当前部分本地通过称为可合并或可投产。

厂商协议、真实医院端点、HL7/FHIR一致性、真实收费支付与仪器链路均 NOT_CONFIGURED/未验证。没有新生产凭据、OAuth/安全配置更改、外发、部署或真实患者。开发默认关闭、既有临床executionAllowed=false保持不变。T39 CI通过前不得合 main。
