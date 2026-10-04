# T42 最终开发验收报告

**结论：已验证的是合成开发基线，不是医院 UAT、临床或生产可用系统。** 父会话已核验 T42 精确 SHA `5e51683122a51660eff42ef55a6390d79227662f` 的 [完整 CI 37198859288](https://github.com/archibaldzhou/PIS/actions/runs/37198859288) 成功，覆盖首页说明修复和验收索引。T41 基线 `5be3b823a68dbddb2d8962b53492afe618f82f82` 的 [main CI 37198121358](https://github.com/archibaldzhou/PIS/actions/runs/37198121358) 亦成功。本轮核验最新远端及祖先关系后，已保留历史快进并正常推送 T42 到 main，远端精确 SHA 为 `5e51683122a51660eff42ef55a6390d79227662f`。该次 main 推送的新 CI 仍由父会话另验。

本轮只更新文档中的证据结论；**这次新的文档提交不属于上述已验证 SHA，其完整 CI 待父会话核验，仅推送 validation/t42，不提前合 main。**

T42开发时的实际构建截图发现首页仍声称“尚未实现病理业务”。当时以独立中文fix提交修正为准确的合成开发/无临床AI说明，新增实际dist提示回归，见 [根因与修复](../api/t42-notice-fix.md)。仅 App 文案与对应dist测试两文件不同于已验证基线，其余559个运行时/测试/部署文件摘要相同；修复随上述 T42 精确 SHA 的完整CI通过，不能将成功归因于旧 T41 CI。没有更改迁移、依赖、身份、安全门禁或临床规则。另提交验收索引/完整性检查、运行指南和同等CI触发。这不是完整形式化证明或零漏洞声明。

## 范围和来源

- [逐项可点击矩阵](matrix.md)：42 行，每项有具体要求、源码、迁移、规格、原型、测试、证据及限制。机器可检查源为 [matrix.json](matrix.json)，不是“42项全部完成”的勾选表。
- [基线证据](baseline-evidence.json)：父会话确认的精确 SHA/CI、201 个引用文件原始摘要及 561 个运行时/测试/部署文件的有序摘要及两文件明确差异摘要。559文件复用同一源码；两文件修复由新增 T42 CI 证据覆盖，不借用旧CI成功。摘要不代表代码本身正确。
- [47页原型索引](prototype-map.json)：同环境合法落地 PNG 摘要重新核对。语义映射不等于逐像素复刻。UI-029 不符合项整改、UI-030 试剂设备维护没有独立实现；UI-033 完整账号授权管理界面、UI-034 全面字典管理等仅有底层或部分契约，不能把映射列当作页面完成率。
- 原始招标/PRD ZIP 正文与独立42任务总计划仍无可读副本，登记于 [来源说明](../prd/README.md)。本矩阵按用户逐项开发指令、已有提交和开发 PRD 重建，**没有声称核验原始招标条款或医院批准验收条款**。T01–T07 使用已有工程契约，不捏造独立临床 PRD。
- [中文运行与演示指南](../runbooks/t42-synthetic-demo.md) 集中提供前置条件、命令、核心链与异常回归入口，不需靠历史聊天寻找步骤。

## 证据分层与检查结果

“T42 CI 通过”来自父会话对上述精确 SHA/run 的完整成功核验，本任务没有重新请求被拒绝的 Actions API，也未持有该 run 的完整日志副本。历史 docs/api 中“CI待验/本地缺BOM”是当时真实状态，保留原文，不反向伪造历史本地结果。本报告是当前证据入口。

| 检查 | 当前结论 | 证据/限制 |
| --- | --- | --- |
| 后端 verify：真实 PG17 迁移/HTTP/权限/并发/审计/幂等与制品隔离 | T42 精确 SHA 完整 CI 通过；开发当时本地离线 BOM 解析失败，未进入编译 | [本地失败原文](../evidence/t42/backend-offline.txt)；不从旧 run 推定当前测试数量 |
| 真实服务 Chromium E2E | T42 CI 通过；本地因后端依赖阻塞未执行 | 47 项发现入口是本地清单，不冒充实际执行日志；代码 frontend/e2e |
| 真正 dist + 同源代理 + 真实 Spring 身份服务 | T42 CI 通过；本地未执行真实服务阶段 | frontend/dist-tests/real.spec.ts：深链接、刷新、Cookie、CSRF、未知API及退出 |
| 正式 JAR 冷启动、重启、失败候选与回切 | T42 CI 通过；本地缺可构建 JAR 未执行 | scripts/deployment/rehearse.py；仅同一构建双实例，不降级数据库；不是不同历史二进制升级 |
| 前端单测、lint、类型及构建 | T42 CI 通过；历史本地结果见下列原始输出 | [单测](../evidence/t42/unit.txt)、[lint](../evidence/t42/lint.txt)、[构建](../evidence/t42/build.txt)；build含typecheck |
| 全套合成浏览器 UI / 真正 dist 的 mock 身份 UI | T42 CI 通过；历史本地执行与真实服务分列 | [UI](../evidence/t42/ui.txt)、[dist UI](../evidence/t42/dist-ui.txt)；不能替代上一行真实服务 |
| 真实PG dump/restore与对象一致性/中断 | 开发当时本地实际执行；T42 CI 亦通过 | [原始结果](../evidence/t42/recovery.txt)：专用容器/临时对象，非生产在线备份 |
| release代理/端口生命周期 | T42 CI 通过；历史本地实际 HTTP/子进程检查 | [8项契约](../evidence/t42/deployment-contracts.txt)；测试zip不是可运行Spring JAR |
| 合成瓦片 provider、PG并发/回滚与架构 | T42 provider CI通过；PG SQL/源码探针为历史本地独立执行 | [provider](../evidence/t42/provider.txt)、[PG](../evidence/t42/postgres.txt)、[源码域检查](../evidence/t42/boundaries.txt)；探针不是完整应用 |
| 前端依赖审计 | T42 CI 成功；本轮未改依赖，复用此证据 | PR dependency-review按条件跳过；Java全依赖/秘密全量扫描仍缺失 |
| T42矩阵/链接/迁移序列/源摘要/必检步骤 | 已验证T42的1分钟CI步骤通过；本轮文档再做本地索引检查 | [检查结果](../evidence/t42/checks.txt)；验证文档完整性，不执行临床验收 |

以上列明现有工作流的适用阶段结果，不捏造本次CI的测试数量；PR dependency-review仅在PR运行，不记为push已执行。新的文档SHA不能沿用该完整成功结论。

完整历史本地结果与截图复核见 [执行记录](../evidence/t42/README.md)。失败的 Maven 入口如实留存；没有删测试、修改断言、放宽单项超时或用 mock UI 替代真实服务。CI verify 保持30分钟、全部既有步骤和固定 Actions/只读权限。

## 核心链的真实服务与安全回归定位

以下 E2E 为同一基线中的真实 Spring + PG + 浏览器（业务准备可能使用同一测试服务 API）；不是从登记到全部特殊分支一次性串成一条病人流程。各场景隔离合成账号/病例，故不会用已有病例或真实患者演示。并发和数据库故障主要在后端集成中覆盖，不能说浏览器本身完成了所有并发证明。

| 核心链 | 真实浏览器文件（frontend/e2e） | 后端/故障证据定位 |
| --- | --- | --- |
| 登记→修改→提交→接收/异常/退回 | accession.spec.ts、reception.spec.ts | RequestWorkflowTest；HTTP/CSRF、同键异参、跨范围和审计回滚 |
| 取材→任务交接→材料/重切→QC隔离返工 | grossing.spec.ts、technical.spec.ts、materials.spec.ts、quality.spec.ts | 两真实账号交接、非owner拒绝、QC与材料消费竞争、源身份约束 |
| 分配→领取/转交→草稿→复核/退回→模拟冻结→PDF→更正 | diagnosis.spec.ts、report.spec.ts、review.spec.ts、output.spec.ts、amendment.spec.ts | 当前资格、修订/模板/QC依赖、并发CAS、旧PDF不可变；不是临床签署 |
| 归档→预约/批准→借出/部分归还→盘点 | archive.spec.ts | archiveReportBindingPreservesFrozenBytesAndRejectsDifferentSourceVersion 等；独占/条码和审计回滚 |
| 原件→导入→数字QC→真实OSD/ROI | storage.spec.ts、scan.spec.ts、digitalqc.spec.ts、viewer.spec.ts | storageFileCompletionSurvivesDatabaseAuditRollbackAndReconcilesExactBytes、viewerManifestAuditRollbackDoesNotExposeCachedArtifact；缓存命中仍鉴权 |
| 合成任务→真实结果像素→人工引用→撤销/复核 | results.spec.ts、decisions.spec.ts | syntheticTaskSurvivesActualApplicationProcessCrashAndRestart；syntheticImpactKeepsSignedReportAndNewDependencyEpochReopensReview；syntheticImpactRevocationRaceNeverLeavesConsumableReference |
| 本地投递及医院adapter→ACK/重试/对账 | delivery.spec.ts、adapters.spec.ts | localAdapterActualProcessCrashAfterReceiptBeforeAckRecoversWithoutDuplicateBusiness；租约、重复/乱序/撤权和原子审计 |
| 受限运维→隔离恢复→实际dist/JAR回切 | adapters.spec.ts、dist-tests/real.spec.ts；独立脚本 | T40实际pg_restore中断回滚；T41真实JAR双实例；8项代理/生命周期测试 |

完整后端方法见 [RequestWorkflowTest](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)，公共边界回归见 [TechnicalDomainBoundaryTest](../../backend/src/test/java/com/pis/architecture/TechnicalDomainBoundaryTest.java)。上述路径存在与断言定位由本次核查，执行结论仍严格依赖确切基线 CI。

## 必须阻断真实上线的项目

| 阻碍 | 当前真实状态 | 需要的验收责任/证据（负责人未指定，不虚构批准） |
| --- | --- | --- |
| 医院业务规则/原PRD/完整UAT | 开发PRD和合成角色；原招标正文未核验 | 产品/病理负责人批准业务、异常、编号、岗位、保留期限与UAT条款 |
| 真实WSI/扫描仪/厂商格式 | 仅明确合成PNG管线，真实格式UNSUPPORTED/NOT_CONFIGURED | 数字病理负责人以代表性合法格式、尺寸、故障、设备逐项验证 |
| 临床坐标/测量/色彩 | 合成像素和XY校准测试；ICC/GPU/校准显示器未验证 | 临床/设备负责人确认MPP、校准、色彩、精度与终端兼容 |
| AI临床模型/验证/监管 | 无模型权重和推理；executionAllowed=false | 模型负责人/临床/监管确认性能、适用范围、审批和监控；本项目未批准 |
| HIS/EMR/收费/设备/CA | 本地自定义合同，无真实支付、通信或CA密钥/合法签署 | 医院集成与签名负责人批准协议、端点、身份和实际ACK/法律效力 |
| TLS/SSO/安全/合规 | TLS域名未配置；显式服务授权不等于医院身份认证 | 安全负责人完成部署账户分离、TLS、身份、渗透/合规、Java全依赖与秘密扫描 |
| 存储/备份/灾备 | 本地私有provider、合成一致恢复；S3/加密/异地未配置 | 平台负责人验证真实容量、在线一致性、密钥、RPO/RTO、异地恢复及故障 |
| 升级与回滚 | 同构建双实例、schema[37,37]；数据库不回退 | 发布负责人用真实不同历史制品证明兼容范围、在线数据与升级前后业务一致性 |
| 物理操作与外围模块 | 无真实打印、借还、电话确认、仪器；UI029/030未实现 | 业务/设备负责人验证实际动作、全原型差异及额外批准范围 |

已发送到合法客户端的像素无法远程收回；服务器每次重新校验，客户端通过后续请求/轮询清理，不声称零延迟撤销。当前合成性能门槛不是医院SLA或容量。没有签署UAT、建立凭据、真实部署或连接真实患者/外部服务。
