# 合成申请与接收工作流

仅供本机工程开发/测试。T08/T09/T10 的工程测试通过不表示医院批准或可用于临床；不要输入真实患者、容器条码或报告。

## 默认安全边界

- `PIS_WORKFLOW_DEV_ENABLED` 默认为 false。开启只允许 dev/test，prod 或未指定这些 profile 时拒绝启动。
- 当前账号必须启用、会话有效且 `synthetic_only=true`；保留 Cookie、CSRF、撤权和同源限制。
- 迁移不创建医院、账号、工作范围或授权。`workflow_scope.enabled` 默认 false，READ/WRITE/RECEIVE/EXCEPTION/PRINT/REPRINT/GROSS 分别授予；WRITE、RECEIVE、EXCEPTION、PRINT、GROSS 均要求 READ；REPRINT 还要求 PRINT。登录、管理员或既有病例角色不会自动授予这些权限。
- 病例生成只创建权威归属，不自动授予 CASE_READ/CASE_EDIT。开发病理号 `DEV-P-<UUID>` 不是医院编号规范。
- 异常/退回保留追加事件；接收身份不符、容器缺失/重复/其他申请容器不能生成病例。有条件接收不支持，软件退回不等于实物交接。

## 可重复的隔离测试/演示入口

准备 JDK21、仓库锁定 Node/npm、真实 PostgreSQL17，以及专用名称以 `_test` 结尾的可丢弃数据库。在自己的终端环境设置 `PIS_TEST_DB_URL`、`PIS_TEST_DB_USERNAME`、`PIS_TEST_DB_PASSWORD`；不要写入仓库或聊天。地址/格式要求见根 README 和 `PostgresTestDatabase`。

自动化完整验证沿用 README：先后端 `./mvnw -B -ntp verify`，再前端 `npm ci`、`npm test`、`npm run lint`、`npm run build`、`npm run test:e2e` 和 `npm run test:ui`。首次浏览器安装沿用 CI 的 Playwright 安装步骤。UI fixture 测试不能代替真实后端 E2E。

交互演示可在 backend 运行测试 classpath 入口（先关闭占用8080/5173的服务）：

```sh
./mvnw -B -ntp spring-boot:test-run -Dspring-boot.run.main-class=com.pis.security.testfixture.SecurityE2eApplication
```

另一个终端在 frontend 运行 `npm run dev`。该后端入口自动启用 test profile 和开发工作流，仅在专用测试库的随机 schema 创建合成账号、组织、就诊及显式开发工作流授权；正常关闭时清理其自有 schema。可事先在本机环境设置 `PIS_E2E_USERNAME`、`PIS_E2E_PASSWORD` 覆盖代码中公开的合成测试默认值。该测试入口不在正式 JAR 中，不对公网开放。

登录后进入“申请登记工作区”，选择合成申请工作范围，通过精确就诊号登记并提交；在列表查看申请，再点击“处理此申请接收与异常”。核对患者 UUID、就诊号、全部容器 UUID 后接收，或记录异常及原因。资料异常需明确补充说明并重新核对；身份/数量异常只能保持阻断或记录退回。

普通 dev 入口需要显式设置 `SPRING_PROFILES_ACTIVE=dev`、`PIS_WORKFLOW_DEV_ENABLED=true` 以及数据库环境变量。开发账号初始化另见[安全说明](../security-access-control.md)，其开关不会创建工作流授权；组织、就诊、作用域和权限必须另行在隔离合成库受控配置。本版未实现工作流授权管理 UI/API，不提供生产自动初始化。

## 尚未批准或实现

医院编号分配、岗位资格及复核制度、各病种材料与固定规则、跨来源重复检测、临床身份更正、条件接收、扫码/打印设备、实物交接、异常处置责任和审计留存政策均须后续确认。数字阅片、AI辅助诊断和临床签发不属于本次 T08/T09 交付。

## 开发标签预览

从已接收申请详情进入“处理此申请标签”，选择确切容器创建任务。首次分配的条码身份固定，重打保留原容器/条码/模板快照并记录原因；重试保留原任务。页面可打开合成标签预览及浏览器打印对话框，但不确认物理输出，后端没有已打印成功状态。开发模拟失败仅变更合成任务状态，不连接打印机。

普通 dev 配置需另行显式授予 PRINT/REPRINT；迁移不自动授权。test-classpath 演示入口为合成账号显式授予这些开发权限。真实设备、医院模板和编号仍待批准，标签不得临床使用。详见[T10 规格](../prd/development-labels-v1.md)。

## 开发取材记录（T11 验证分支 CI 通过）

已接收申请详情进入“处理此病例取材”，建立描述后按“取材操作”选择新增取材盒、绑定来源容器、添加合成图像、完成或取消。修正盒先带原因取消旧盒再新增；完成后只允许追加描述更正。盒保持PLANNED，不能视为已处理/包埋。

GROSS需要独立授予；演示测试classpath显式授权且增加独立合成就诊SYN-GROSS-001。图像只能选择随包合成PNG，文件输入也仅接受同一字节样本；不能导入真实照片。撤回/取消保留记录并阻止新下载。换成真实对象存储、图片解析或医院资格策略需要后续审批与验证。细节见[T11规格](../prd/development-grossing-v1.md)。

## 技术任务与交接（T12 验证分支 CI 通过）

取材记录完成后，从申请详情进入“处理此病例技术任务”。选择有效盒与技术类别，填写路线说明；可选同盒已完成合成演练的前置任务，也可按说明使用不同路线。领取/交接/合成完成/中止均手工核对盒UUID，原因保存到追加历史。发起交接后由另一位有效授权用户确认，原owner不能自己确认。

普通dev需独立PROCESS/HANDOFF（均默认false）；HANDOFF依赖PROCESS。测试classpath使用独立合成患者/就诊SYN-TECH-001和第二合成账号，用户名/密码可用PIS_E2E_HANDOFF_USERNAME/PIS_E2E_HANDOFF_PASSWORD环境变量覆盖；默认值仅供隔离测试，不存在于正常JAR。接收人只需READ/PROCESS/HANDOFF，不获GROSS编辑权。

SIMULATED_DONE仅表示用户记录合成演练步骤，不是实际脱水/包埋/切片完成。中止或终态返工会新建关联任务UUID，原盒/原历史不改；没有实际蜡块/玻片产出。真实设备、工艺、岗位和临床验证另批。

## 蜡块与玻片（T13 验证分支完整 CI 通过）

从已接收申请详情进入“处理此病例材料谱系”。常规路径先建立有效取材盒和已完成合成包埋/切片任务，再按明确操作登记蜡块、原片或重切/加深；核对界面列出的确切来源 UUID。细胞学直制显式选择直制操作，只核对已接收容器，不补虚构蜡块或盒级任务。未知路线不自动推断。

普通 dev 独立授予 READ+MATERIAL；标签仍需 PRINT/REPRINT。测试classpath新增独立合成患者/就诊 SYN-MATERIAL-001 和 SYN-DIRECT-001。通过材料标签操作复用原预览和重打，条码保持稳定。作废蜡块同时作废全部有效玻片并留历史；不会修改已打印纸面。按确切条码反查完整来源和事件。

权限、版本或来源变化后刷新重审；网络结果未知时保留原幂等键确认，不能换键重建材料。V10标签兼容迁移有表锁，真实环境需另行评估，不在生产执行此开发说明。细节见[T13验收记录](../api/t13-development-status.md)。

浏览器测试账号隔离：认证/错误登录场景使用 PIS_E2E_USERNAME/PIS_E2E_PASSWORD；业务场景使用测试classpath中独立的 PIS_E2E_WORKFLOW_USERNAME/PIS_E2E_WORKFLOW_PASSWORD（默认 synthetic.workflow），具有显式合成工作流授权。交接接收者仍使用独立 HANDOFF 账号。不要把两类账号配置成同一用户名；认证账号不再自动获得工作流授权。此隔离避免不同测试共耗每账号登录预算，生产20次/5分钟及每来源100次限流保持原样；测试也不绕过来源限制。业务登录先核验204、当前用户身份及界面身份，再进入工作区，不自动重试登录。

## 技术QC与隔离（T14 验证分支完整CI通过）

从已接收申请详情进入“处理此病例技术QC”，选择确切材料，核对UUID及页面显示的材料/任务版本，显式选择合成结论并填写原因。普通dev另行授予READ+QC；返工还需PROCESS。测试classpath业务账号显式获QC，并使用独立SYN-QC-001场景。

FAIL/PENDING/撤销/失效保持隔离。常规返工创建新技术任务，在技术页面领取并记录合成完成，再在材料页面用该任务登记新蜡块或重切新片，返回QC独立判定。原身份和原判定不改；身份问题不提供解除入口。异常放行始终禁用，管理员也不能绕过。直制没有盒级任务，不强制补造；未质检身份标签不是放行证明。

并发冲突后刷新重审；结果未知保留原键确认，不换键重复判定。实际边界及待CI项目见[T14验收记录](../api/t14-development-status.md)。
