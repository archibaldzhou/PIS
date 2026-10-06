# 合成手工申请接口

2026-10-06；用户明确授权新增手工患者/就诊登记。开发依据见 [申请开发规格](../prd/development-accession-v1.md)。仅合成开发，不是医院临床批准。

`POST /api/requests/manual`：Cookie登录、CSRF和`Idempotency-Key`必需。body：`{scopeId, patientName, encounterNumber, draft}`；draft与已有申请创建契约一致。服务端只接受当前有WRITE+READ的范围；患者名/就诊号必填、最多255且禁止首尾空白。

201返回既有`IdempotentCommands.Result`，receipt为`PATHOLOGY_REQUEST`、resourceId为申请UUID、version为0。随后通过`GET /api/requests/{id}`读取确切患者/就诊UUID、显示信息、草稿及容器身份。

400字段错误；401未认证；403无范围/写权限；409同键异参或`ENCOUNTER_NUMBER_EXISTS`。业务开关关闭返回503。客户端不发送actor、医院、科室、来源或已有患者ID。重复就诊号必须改用已有就诊录入，不能自动合并。

全部身份及草稿写入与`REQUEST_MANUAL_CREATE_V1`审计、回执处于同一短事务；核心身份公开服务要求MANDATORY事务。重放验证当前申请权限。审计记录申请对象且通过确切外键追溯身份，不保存患者名/病史正文到审计差异。

UI提供列表“手工申请”按钮和侧栏入口；保存成功后重查列表，未知结果冻结全部输入并沿用原body/key确认，切页/切范围遵循既有脏输入提醒。正式患者匹配、医院编号和临床登记权限需另行批准。

## 本次本机验证（2026-10-06）

- 根目录：`backend/mvnw.cmd -f backend/pom.xml -B -ntp -Dtest=ManualRequestTest,TechnicalDomainBoundaryTest test`，JDK21、真实PostgreSQL17.11，9项通过（7项手工申请及2项架构检查）。包含真实HTTP登录/CSRF/低权限直调、跨范围拒绝、撤权后重放、同键异参、同号并发唯一赢家、审计失败全部回滚及同键重试。
- frontend：固定Node22.23.3/npm11.21.0；`npm ci --no-audit --no-fund`、`npm run lint`、`npm test`（208项）、`npm run build`通过；构建仍提示既有大chunk警告。
- 根目录：`npm --prefix frontend run test:ui -- workflow.spec.ts`，使用本机Chrome、独立5174和合成HTTP夹具，52项通过。新增验证必填字段、双击只写一次、重开无旧身份、未知结果冻结姓名/就诊号并重试同key/body。该套件不代替真实服务验证。
- 当前5173/8080真实服务：实际浏览器手工输入新的合成姓名、就诊号和样本部位，保存后列表可查；原10条mock保留，另外新增1条明确命名的合成手工验收草稿。
- 根目录：`python -X utf8 scripts/acceptance.py`和`git diff --check`通过。新增显式差异清单保留旧T42基线；当前新增文件被篡改时索引拒绝。Windows文本按Git的text属性换行规范化，二进制仍逐字节校验。
- 新增`frontend/e2e/accession.spec.ts`真实服务场景进入全套CI：无已有就诊手工创建、列表确认及刷新后仍可查。本机已做实际浏览器保存验证，但未执行该Playwright完整真实服务套件。

完整后端verify此前在Windows因POSIX私有存储实现受限，完整E2E/部署演练/恢复/依赖审计本轮未运行，不能将专项通过视为完整门禁成功。最终提交的完整CI需独立核验；历史T42成功不替代此增量。
