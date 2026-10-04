# T37 人工合成结果决定

路径 `/api/requests/reports/cases/{caseId}/synthetic-decisions`，会话、当前医生资格、已领取病例、组织/资源权限和默认关闭的合成 worker 开关沿用现有策略。管理员无特殊临床权限。

GET：`resultId` 必填；`page` 默认1，1–100，每页20，按决定版本降序。返回精确 result/source binding、当前报告修订、分配版本、决定 head、history、ready/invalidReason，executionAllowed 固定 false。依赖失效时可授权追溯，不授权获取像素或新处理。

POST：需有效 CSRF 和 Idempotency-Key。白名单正文：confirmedCaseId、resultId、targetRevisionId、reportVersion、assignmentVersion、expectedVersion（首笔-1）、action（ACCEPT_REFERENCE/REJECT/DEFER）、reason（非空，最多500）、confirmed=true。当前病例、报告和来源版本均重新验证。采纳只增加原字段不变的修订和独立引用；另两种操作不改草稿。每病例/结果最多100个决定。

回执沿用 T07 `{receipt:{status:200,resourceType:"SYNTHETIC_REPORT_DECISION",resourceId,version},replayed}`。同键同正文返回同一事件；同键异参409，不重复修订或命令审计。撤权、来源撤销后的重放仍拒绝。审计操作 `AI_RESULT_DECISION_V1`；读取 `AI_DECISION_READ_V1` / `AI_RESULT_REFERENCE_READ_V1`。

认证401、CSRF403、不可见对象404、参数400、版本/依赖/冻结冲突409；不得将错误当成功。当前目标已模拟签署拒绝新决定。历史事件保留原目标、采纳目标、原因、actor/time、完整来源 binding 和 hash，不修改既有内容。
