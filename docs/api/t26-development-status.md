# T26 本地交付：人工归档、借阅、归还与盘点

2026-10-03；起点 `c7ecfefc7f9c9281ab424f56d44cddbb56e90f5f`，同一 `/workspace/PIS`、`validation/t15-20261002`。已逐一核验 T15–T25 为当前历史祖先；开工工作区干净，无历史重写、登录、凭据处理、push 或部署。

已读 AGENTS、工程基线、门禁、构建/CI及相关代码；本地 `.agents` 无可读技能文件。先编写[开发PRD](../prd/development-archive-v1.md)，实际查看授权 UI-026 入库定位、UI-027 借阅归还图片。现有授权附件ZIP SHA256再次核验为 `74f3305da4278f84c7660eb13625c1ff604174116d2fe13fbeffe85cf7a58291`。原型不构成医院批准；架构见[ADR0016](../adr/0016-manual-archive-custody.md)。

## 实现范围

- V24 新增独立 archive 域及档案资格、申请台账、库位、档案项、借阅及逐项占用、盘点与快照、追加事件/最小拒绝审计。复合FK绑定申请/医院/组织/病例/患者；来源唯一键、独占位置和活动借阅唯一索引防止重复身份和双借出。旧迁移、材料、报告、PDF不改。
- 档案项只引用既有蜡块、玻片或确切报告产物。材料条码必须一致；报告档案标识为 `SYN-PDF-{artifactId}`，不是伪造物理扫描反馈。报告绑定原产物ID、版本、修订及SHA256，由插入触发器核对，身份不可变；不重渲染、不复制PDF。原有输出API继续独立授权、校验字节与审计；档案权限不额外授予下载或编辑/签署权。
- `archive_grant` 明确 `SYN-ARCHIVE-1` 资格及申请/审批/管理权限、到期和撤销；同时要求合成账户和有效组织READ范围。提交、等待锁后、幂等重放重新授权，资格/账号/组织授权加锁；无管理员默认临床权。借用者必须是同范围有效合成人员，不创建授权或发邀请。
- 人工上架、下架、移位/移交登记，稳定库位最多100个/组织，档案最多100项/申请。所有事件固定 `physicalConfirmation=UNVERIFIED`，登记不证明物理动作已发生。开发策略标识和保全元数据冻结于档案项，无期限清理、删除或销毁入口。
- 借阅明确目的/借用者/选择的项版本及带时区时间。`pis.archive.max-loan-days` 默认30、范围1–365，仅开发上限。申请即逐项预约，审批须另一位合格人员。逐项借出和归还分别更新，支持部分借出、部分归还；有在借项时拒绝整体取消/拒绝，不能释放尚未归还占用。归还全部项才关闭。
- 借出提交时再验借用者、申请者及审批资格、当前材料QC/身份及保管状态。作废、隔离、QC失败和未评估仍可归档追溯，但不允许借出。遗失保留在借占用；找回只变为待核对，不自动恢复可借；损坏不可通过找回抹除。归还不会把材料QC改为通过。
- 盘点冻结项ID/版本/位置/保管及占用状态。核对结果MATCH或DIFFERENCE由人工明确输入；差异不修改位置。更正引用同项确切差异事件和当前项版本，需原因；重复更正或陈旧快照冲突。原快照、历史保留，数量始终一项一件，无自动增减。
- 申请根锁、台账和逐项CAS、独占约束、事件/审计/幂等同事务。批量最多10条同申请命令，逐项独立授权和事务，完整输入及各自原键；按索引返回SUCCESS、FAILED、UNKNOWN、NOT_ATTEMPTED。未知失败后停止，不把未执行项标成功；不是整个批次全有或全无。
- React从已接收申请详情进入：身份/精确PDF元数据、当前位置/来源QC、借阅逐项状态、冻结快照及分页历史。表单切动作/盘点/病例确认脏数据，取消保留，确认清除；条码随物品切换清空；迟到读取取消并丢弃。写入双击互斥，未知结果锁定导航/原意图及幂等键，确定冲突保留输入供复核。界面逐项操作，批量入口为API。

## API

`GET /api/requests/{requestId}/archive?inventory={可选快照ID}&page=1`：病例与组织授权、读审计；历史20条/页，页1–10000；最多100项/借阅/盘点，借阅成员最多2000条，来源最多200条（材料100+产物100）。无权限不返回受限内容。

`POST /api/requests/{requestId}/archive/{ACTION}`：CSRF与Idempotency-Key，动作 LOCATION / REGISTER / MOVE / REMOVE / LOAN / APPROVE / REJECT / CANCEL / CHECKOUT / RETURN / DAMAGE / LOST / FOUND / INVENTORY / CHECK / CORRECT。公共字段 confirmedRequestId、expectedVersion（首次-1）、reason、items（无选择传空数组）。物品命令另绑定itemId、itemVersion、barcode；来源登记绑定sourceId/sourceVersion与保管元数据；借阅绑定borrowerId/purpose/dueAt及items[id,version]；盘点核对绑定inventoryId/observation，差异更正绑定referenceId。人员取服务器会话。

成功回执 `ARCHIVE_BOOK / requestId / expectedVersion+1`。`POST .../archive/batch` 接收 `entries[{key,action,command}]`，逐项返回 `{index,status,code,result}`；无外发副作用。400必填/类型，404对象或资格不可用，409身份/条码/占用/版本/QC/快照/状态冲突，503开发开关未启用。错误不含正文、他人身份或数据库细节。状态更改拒绝追加最小代码审计，不记录请求正文。

## 本地执行证据与边界

使用现有Node22.23.3/npm11.21.0、Java21及固定digest PG17，未改变依赖、锁文件、安全配置或合成开关。PG测试容器为当前环境内网络禁用、临时存储的合成测试工具，使用后清理。

- `npm --prefix frontend run lint`、`test`、`build`通过：20文件127项单测，包含档案边界7项；TypeScript严格检查通过。构建保留约1.26MB大chunk提示，未调低门禁；依赖审计0漏洞。
- Chromium HTTP mock UI：新增4项档案定向回归通过；完整48项通过，收尾截图关闭动画后1项复测通过；桌面与390px截图已实际查看、无整页横向溢出。覆盖错误条码、未知写入原键重试/双击、脏数据取消/病例离开、盘点迟到响应与失败非空状态。真实后端未参与这些UI测试。
- `python3 backend/src/test/probes/archive-postgres.py`：V1–V24真实PG SQL加载、稳定身份/追加历史/冻结快照不可变、实际观察到并发预约等待及唯一胜者、生产项CAS SQL、快照失效、归还/位置/根版本/事件在审计失败时回滚、归还后可重新预约通过。不是Flyway或Spring集成通过。
- `consultation-postgres.py`、`staining-postgres.py`加载当前全部迁移后的既有来源、控制门禁、意见/结果竞争和审计回滚回归通过；其旧日志标题仍标原任务版本，不代表仅加载旧迁移。
- 独立编译并执行生产 `ArchivePolicy` 的期限边界、QC不可借、遗失/找回分离检查通过。全部Java源语法解析、13域源码依赖无环检查通过；两者不等于完整项目类型编译。

新增11项Spring/真实PG工作流测试源码、3项策略测试及V23→V24迁移测试，覆盖部分归还/重放、条码/跨范围/撤权、QC/遗失找回、盘点更正、并发预约/借出/归还、审计回滚、固定PDF、批量部分结果/停止及期限。新增 `frontend/e2e/archive.spec.ts` 的真实本地双角色预约/审批/借出/归还/陈旧盘点/原键重放及页面验证，无HTTP模拟。

**完整后端编译/JUnit/HTTP/Flyway集成、真实E2E未执行通过。** 离线Maven verify本次再次失败于POM解析，Boot4.1.1导入的Zipkin Reporter3.5.3、Brave6.3.1、Cassandra4.19.3等BOM本地缺失，未进入编译；无可用依赖JAR可支持完整后端运行。未重试已拒绝下载、处理凭据或更改配置。CI未连接/等待；应用级并发、数据库角色最小权限与物理设备确认仍未验证。

医院保管/借阅资质、保管期限和法律保全制度、实际库存及物理交接证据未批准/接入，属于投产阻塞。本交付仅合成开发，不能据部分本地通过宣称可合并或投产。
