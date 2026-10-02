# 合成申请与接收工作流

仅供本机工程开发/测试。T08/T09/T10 的工程测试通过不表示医院批准或可用于临床；不要输入真实患者、容器条码或报告。

## 默认安全边界

- `PIS_WORKFLOW_DEV_ENABLED` 默认为 false。开启只允许 dev/test，prod 或未指定这些 profile 时拒绝启动。
- 当前账号必须启用、会话有效且 `synthetic_only=true`；保留 Cookie、CSRF、撤权和同源限制。
- 迁移不创建医院、账号、工作范围或授权。`workflow_scope.enabled` 默认 false，READ/WRITE/RECEIVE/EXCEPTION/PRINT/REPRINT 分别授予；WRITE、RECEIVE、EXCEPTION、PRINT 均要求 READ；REPRINT 还要求 PRINT。登录、管理员或既有病例角色不会自动授予这些权限。
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
