# T15 工作列表与追踪验收记录

状态：实现与本地适用检查完成，验证分支完整CI待核验。依据[开发规格](../prd/development-worklist-v1.md)及[投影ADR](../adr/0005-scoped-worklist-projections.md)。授权上传ZIP SHA256 `74f3305da4278f84c7660eb13625c1ff604174116d2fe13fbeffe85cf7a58291` 已重新验证可读，实际查看UI-001/006/008，沿用已查看的UI-037交互；附件未入库。T14 main `a36fbff996a57180eb7edb64313b89a5345d6e71` 的CI 37059736770由父会话确认成功。

## API与授权

| `/api/worklists` 路径 | 行为 |
| --- | --- |
| GET `/scopes/{id}` | kind=ALL/REQUEST/RECEPTION/TECHNICAL/QUALITY；state真实状态枚举；due=ALL/OVERDUE/NOT_OVERDUE；sort=OLDEST/NEWEST/NUMBER；page默认1，pageSize默认20、最多50 |
| GET `/requests/{id}/trace` | 现有七域事件，page/pageSize同上，按时间/domain/事件ID稳定分页 |
| POST `/scopes/{id}/claims` | batchId UUID、items（1–20个taskId/expectedVersion/confirmedCassetteId）、reason；Cookie与CSRF；逐项独立领取 |

列表与计数使用同一SQL语句、同一当前范围及域权限过滤。指定无权类别、无权/不存在scope均404；ALL只返回有权域。追踪先验证申请READ，再按事件域权限过滤；无权申请与不存在同404。隐藏域未展示不代表没有发生。未知枚举、非法分页/字段、重复任务、超量或空原因400。page上限10000，排序白名单与查询5秒超时，返回页数上限不代表大库容量验证。

默认关闭的合成dev/test开关、synthetic_only、当前账号与授权期限不变；没有新默认权限。REQUEST=READ；RECEPTION=RECEIVE；TECHNICAL=PROCESS；QUALITY=QC；追踪另外按GROSS/MATERIAL/PRINT过滤相应域。范围和总数不能通过客户端患者字段扩大。返回最小稳定身份/版本/状态，不返回姓名、诊断病史或事件原因正文。

批次返回200表示收到逐项结果，不表示整批成功；每项包含taskId、SUCCESS/REJECTED/UNKNOWN、真实HTTP语义status、安全业务code、成功version/replayed。失败没有成功版本。无权/不存在/跨批次scope项统一404 WORKLIST_ITEM_UNAVAILABLE；锁忙、超时和数据库故障保守标为UNKNOWN。编程或连接级整体故障可能中断响应，客户端同样显示结果待确认，不能断言整批回滚。

每项调用现有技术CLAIM和T07事务，主体/组织/操作键域不变；键为`bulk-{batchId}-{taskId}`，摘要含原对象、版本、来源盒和原因。每项再次验证当前授权及指定scope，并在申请锁后校验来源、QC门禁、状态和CAS版本；审计及回执同事务。外层没有大事务，前项成功在后项失败时保留。原批次原输入重试，已成功项只重放；同批同项修改输入拒绝。客户端只保留当前页明确选择，换页/筛选需确认丢弃脏输入；待确认结果阻断导航和输入，双击只发一次，逐项结果明确显示。

## 超期和追踪

`pis.worklist.synthetic-due-minutes` 默认240，启动验证1–10080；可用环境变量`PIS_WORKLIST_SYNTHETIC_DUE_MINUTES`设置。响应返回统一UTC asOf（微秒精度）及阈值。只有active工作项严格晚于创建/接收提交时间加阈值才超期；相等不算，终态无dueAt且不超期。不是医院TAT、节假日或优先级策略，不触发临床动作。QC来源隔离/失效映射为真实有效状态。

V13仅新增三个只读SQL投影，聚合现有行和追加事件；V1–V12不改字节。投影不是RLS或数据库授权边界，应用必须添加当前scope/grant过滤。追踪稳定ID关联已有申请、接收、取材、技术、材料、标签、QC；不伪造报告/WSI/AI节点。新服务单向调用公开业务接口，技术域不依赖worklist；源码引用门禁扩展八域。

## 实际运行与待CI验证

本地固定Node22.23.3/npm11.21.0：lint、72项单测、TypeScript与构建通过；14项路由mock UI浏览器测试通过（系统Chromium151），桌面/移动端截图已检查。新测试覆盖当前页选择、分页丢弃、双击断网原输入重试、导航阻断、部分成功及无权与空集区分。主包超过500KiB警告保留。

真实PG17无网络临时容器：V1–V12初始化→V13 SQL升级，旧材料/标签/身份隔离及只追加历史约束通过；从WorklistService源码提取的实际列表/追踪SQL经PREPARE及带参数EXECUTE，授权过滤计数符合合成夹具。临时容器已清理。这不是Java/JDBC/Flyway集成测试通过证明。Java源码语法解析、八域源码引用无环探针和V1–V12字节比对通过，也不是编译结果。

新增待完整CI运行：V12→V13迁移校验和/旧审计/最小字段/默认无授权；真实PG授权行与计数/分页、HTTP认证与CSRF/未知字段/非法排序、撤权重放；数据库查询截止前/相等/后一微秒及结束项边界；批量部分失败/异参/跨组织scope替换/QC隔离/审计故障逐项回滚/并发真实锁等待；独立SYN-WORKLIST-001合成患者的真实UI/API批量部分成功、竞争版本和原批次重放、追加追踪E2E。既有测试未删减。

本地Maven依赖429及下载/Actions权限拒绝没有换路绕过。完整Java、Flyway、Spring E2E、固定CI浏览器、正式JAR隔离和依赖审计仍需父会话核验确切验证SHA；通过后才整理独立T15 main并再次核验。未部署、未临床启用，不包含T16。
