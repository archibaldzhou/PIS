# T24 本地交付记录：特殊染色与 IHC 批次、对照及来源链

2026-10-03；起点 `61fdd88aac2fa1ff4b936bef6243d111f59c6ea8`，同一 `/workspace/PIS`、`validation/t15-20261002`。保留 T15–T23 和既有工作，只形成中文本地提交；不登录 GitHub、不处理凭据、不 push、不查询 CI、不部署。

依据 [开发 PRD](../prd/development-staining-v1.md)、[ADR 0014](../adr/0014-synthetic-stain-batches.md) 和根 AGENTS。实际查看授权 ZIP 中 UI-013/017/018；ZIP SHA256 为 `74f3305da4278f84c7660eb13625c1ff604174116d2fe13fbeffe85cf7a58291`。原型不是医院规则批准，未声称获得原始招标 PRD 正文。

## 实际实现

- V22 新增资格、不可变项目/方案、批次、独立检测申请、冻结成员、追加事件及最小拒绝审计。项目/方案按作用域、SPECIAL/IHC 类型、SYN-代码及版本唯一，同版本异元数据拒绝。迁移不种演示对象或权限，未新增依赖。
- 批次限同一申请/病例，1–20张已登记且材料/来源 QC 通过的玻片。冻结时逐张一对一转移为新染色身份，来源追加 STAIN_TRANSFER 并转 VOID；原身份、结果和 QC 历史保留。源患者、容器、蜡块、取材和任务链保持不变，支持 T23 无蜡块来源；不从蜡块量推算产率。单申请最多100材料/100批次。
- 检测申请记录源版本与初始依赖；成员冻结后禁止追加，唯一源约束阻止重复消费。失败、撤销不能恢复旧批次。重跑引用同申请旧冻结批次、强制原因，使用新来源并新建申请/批次/输出玻片，不覆盖原对象。
- 试剂批号、UTC业务日期有效期、独立对照UUID及人工材料说明在登记后不可变；到期当天包含在内，创建、冻结、结果等提交重新检查。对照缺失/失败/撤销、空结果、技术QC失败或来源变化不能视为有效。只记录人工合成观察，不提供厂商操作方案或默认阴性。
- 结果每成员一次，绑定确切冻结版本和通过的对照事件；撤销改变当前有效性，历史通过/结果内容不变。技术有效性不替代 T14 自身材料QC。隔离传播到材料质量投影、标签预览/打印、T16就绪和报告依赖摘要；既有冻结报告/PDF内容不改变。
- SYN-STAIN-1 显式资格拆分申请、执行、QC权限，同时校验医院/组织/病例及原工作流权限。管理员不自动取得资格。合成模式保持默认关闭，仅允许dev/test；不修改身份、CSRF、OAuth或安全配置。
- 申请根锁、资格重读、批次CAS、T07原键幂等与重放重新鉴权；来源转移、新玻片、条码身份、历史、审计及命令回执同事务。条码身份内部登记与对外预览放行分开，待对照玻片不能借登记绕过QC。
- 页面显示病例身份、项目/方案版本、批次/冻结版本、独立对照和结果历史及明确失效原因。切换批次/动作/病例保留脏输入确认，确认后清理旧字段；取消保留输入。迟到读取丢弃、双击互斥、未知提交冻结原意图原键重试。

## API 与错误边界

`GET /api/materials/requests/{request}/staining?batch={可选批次}&page=1`：授权及读审计；最多100批次/来源、20成员、历史20条/页，页号1–10000。

`POST /api/materials/requests/{request}/staining/{ACTION}?batch={既有批次}`：动作 CREATE / ADD / FREEZE / CONTROL_PASS / CONTROL_FAIL / REVOKE / RESULT；CSRF、Idempotency-Key。所有命令带 confirmedRequestId、expectedVersion（创建-1）和强制reason。CREATE/ADD明确sources的ID/version；CREATE额外带kind、项目/方案代码及版本、metadata、reagentLot、expiresOn、controlReference及可选rerunOf。控制命令绑定frozenVersion；撤销/结果绑定controlEventId，结果还需orderId、technicalQc和非空content。服务器确定actor，不接受管理员覆盖字段。

回执 STAIN_BATCH / ID / 新版本，200不等于临床染色有效。格式/长度错误400；对象或资格不可用404；状态、数量、过期、缺失记录、绑定或CAS冲突409；默认关闭沿用503。重放返回原回执，不证明当前结果仍有效。越权来源与不存在来源使用同一错误边界。

## 本次验证

执行目录 `/workspace/PIS`；Node22.23.3、npm11.21.0、Java21、固定digest的真实PG17临时容器（网络禁用，合成数据，已清理）。

- `npm --prefix frontend run lint`、`test`、`build` 通过：18文件、114项单测；TypeScript通过。构建保留现有大chunk警告（约1.22 MB），未降低门禁。
- `npm --prefix frontend run audit:dependencies`：0漏洞。
- `PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui`：全套40项通过；随后新增人工结果绑定/撤销历史UI场景，单项复测通过。此套件使用mock API，不是完整E2E。桌面及390px截图已实际查看，无整页水平溢出。新增场景首次因文本定位未包含同单元格状态前缀失败，修正定位后复测，未修改或放宽业务断言。
- `python3 backend/src/test/probes/staining-postgres.py`：V1–V22真实PG SQL、实际生产转移/详情/报告摘要SQL、冻结身份/标签、缺少结果隔离、明确接受后来源QC撤销失效、观察到的结果/撤销根锁CAS竞争、审计故障原子回滚、历史不可变通过。
- `python3 backend/src/test/probes/cytology-postgres.py`：T23三路径、守恒、竞争、审计回滚、来源失效及升级V22后报告摘要回归通过。
- 生产 StainPolicy 独立Java编译/执行：UTC到期边界、数量/重复与合成代码通过，7项非法输入拒绝。全Java源码语法解析通过；不等同全项目类型检查或JUnit。

新增 StainPolicyTest、StainMigrationTest、7项RequestWorkflowTest场景及真实 `frontend/e2e/staining.spec.ts` 和独立测试资格/合成就诊。覆盖冻结谱系、失效、权限/重放撤权、审计回滚、竞争、CSRF和未知字段；真实E2E源码包含IHC/SPECIAL、重跑、新身份、幂等、双结果竞争、撤销与页面。**这些完整后端集成及真实E2E测试尚未执行通过。**

本次离线 Maven verify 在 POM 解析阶段失败：Spring Boot4.1.1导入的 Zipkin Reporter3.5.3、Brave6.3.1、Cassandra4.19.3、gRPC1.83.1 等BOM不在本地缓存；尚未进入后端编译。未更改依赖或重试已拒绝下载。完整后端编译/全部JUnit、Flyway集成和真实Spring/PG E2E因此未验证；CI按用户要求未连接或等待。SQL探针不能证明Spring事务代理、完整服务鉴权与HTTP链路全部通过。

真实仪器协议、医院批准的特殊染色/IHC方案、真实资格与试剂/对照制度仍缺失，是投产阻塞；演示元数据不能替代。仅合成开发，不使用真实患者、AI、外部发送、实际设备、临床/CA签署或部署，不宣称可合并或可投产。
