# T11 取材开发验收记录

状态：T11 修复后的验证分支完整 CI 已通过；首轮失败及修复证据保留如下。main 整理提交推送后另行核验。

依据[开发规格](../prd/development-grossing-v1.md)，实际查看 UI-008 取材队列、UI-009 取材记录与蜡块 PNG。当前从授权申请列表进入已接收病例的取材页面，保留病例身份、大体描述、来源容器、盒列表及图像布局；盒状态明确待处理，T12 实际处理/包埋与 T13 玻片不提前完成。照片仅受控合成 PNG，限制及对象存储决定见 [ADR 0003](../adr/0003-controlled-synthetic-gross-photos.md)。医院岗位/复核/图像政策仍待批准。

## 接口契约

所有入口位于 `/api/grossing`，须当前启用合成账号、有效授权范围、READ+GROSS；Cookie/CSRF 和统一 ProblemDetail 沿用 T07。写入需要 `Idempotency-Key`，重放当前重新鉴权；未知 JSON 字段拒绝，客户端不能传 actor/case/patient。仅成功回执201/200表示保存完成。

| 路径与方法 | DTO/结果 |
| --- | --- |
| GET `/requests/{requestId}` | 已接收申请身份、病例身份、取材记录及有界历史；尚未建立时 record=null |
| POST `/requests/{requestId}` | requestVersion、description；创建DRAFT，返回201 GROSS_RECORD回执 |
| POST `/records/{id}/description` | expectedVersion、description、reason；DRAFT描述新修订 |
| POST `/records/{id}/correction` | 同上；仅COMPLETED追加非空更正 |
| POST `/records/{id}/cassettes` | expectedVersion、containerIds、site、pieces；新增稳定盒与来源关系 |
| POST `/records/{id}/cassettes/{target}/cancel` | expectedVersion、reason；取消同记录盒，不删除谱系 |
| GET `/requests/{requestId}/sample` | 受保护合成样本PNG |
| POST `/records/{id}/photos` | expectedVersion、containerId、base64、caption；仅已知样本 |
| GET `/photos/{photoId}/content` | 重新鉴权，未撤回/未取消的PNG；no-store、nosniff |
| POST `/records/{id}/photos/{target}/withdraw` | expectedVersion、reason；保留关联，拒绝新下载 |
| POST `/records/{id}/complete` 或 `/cancel` | expectedVersion、reason；完成要求描述及有效盒，取消保留历史 |

读锁/写锁顺序为当前主体→scope→grant→申请→记录；写校验最新权限、RECEIVED状态和版本。错误包括404隐藏无权资源、409版本/状态/谱系冲突、400照片内容拒绝、413附件JSON超限；事务审计失败回滚全部元数据。详细字段边界见规格和 `GrossContracts`。

V8 增量扩展 GROSS（默认false）与六张表，复合FK约束申请/病例/容器同属关系，修订/事件/谱系不可改写。V1–V7不变，老升级测试固定各自目标版本。新增迁移没有业务种子或自动授权。

## 验证范围

- 前端：63项Vitest单测已通过；lint、类型检查与构建已通过。8项浏览器合成UI测试全部通过（系统Chromium151），已实际查看取材移动端截图；此结果不替代真实Spring E2E。构建仍提示既有主包超过500KiB，未关闭警告。
- 真实PG17：本地隔离容器顺序执行V1–V8 SQL探针成功；不是Flyway升级或Java测试通过的证明。
- 测试源码新增：空库/升级历史、附件白名单、同病例来源与跨范围拒绝、版本竞争并观察PG锁等待、事务审计失败回滚、回放撤权、取消/更正历史、HTTP鉴权/CSRF/分块与固定长度超限、受保护下载；浏览器合成fixture及完整Spring流程分别覆盖。
- 本地Java/full E2E未运行成功：Maven Central持续429，获拒镜像/浏览器下载未绕过。授权完整CI继续执行原全部后端与浏览器门禁；本地UI仅使用已有系统Chromium。
- T11独立验证提交正常推送，父会话以已授权Actions连接核验确切SHA；通过后单独整理main。此文件不会把源码存在当作测试成功。

## 首轮 CI 与浏览器修复

父会话确认 SHA `914d394e88ac02e59d186dd3cd739c4e8638134e` 的 [CI 37042410858](https://github.com/archibaldzhou/PIS/actions/runs/37042410858) 在 verify 110955494395 的真实浏览器阶段失败：此前后端与前端步骤通过，浏览器21通过、2失败。失败不是临床业务验收证据。

申请测试原先以通用“查看 DEV-AP-”定位，两个worker产生多个申请时歧义；改成创建回执中的UUID绑定表格行，核验对应详情与就诊，再进行编辑/提交。业务场景各自使用独立合成患者和就诊，仍保留共享列表及并发worker以验证明确身份选择，不靠串行化掩盖问题。

取材更正测试原先会匹配仍未保存的输入文案，过早读取API；改成等待目标记录的更正HTTP成功回执、匹配资源UUID/新版本以及修订区域的确切表格单元格，然后检查API不可变历史。新增受控延迟回执UI回归，同时展示两个申请，验证确切资源选择，且输入框已改变时旧修订仍保持，回执返回后才出现新修订。不使用first()、删除测试、重试掩盖失败或放宽断言。

本次修复本地结果：63项Vitest、8项UI浏览器测试、lint、类型与构建通过。Java测试fixture及真实后端E2E复验交由新SHA完整CI执行，本地未宣称运行成功。

最终验收：父会话确认验证SHA `f458370628eed14001a0adabc8efff594b760ab0` 的 [CI 37043411839](https://github.com/archibaldzhou/PIS/actions/runs/37043411839) completed success，verify 110958811739 的后端、前端、真实浏览器及合成UI门禁通过，独立依赖审计通过，PR-only review按原条件跳过。main整理保留此已验证实现，仅更新验收说明与移除验证分支触发；没有部署或临床验收。
