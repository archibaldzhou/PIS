# T38 失效影响与人工复核

会话、当前医生资格/病例领取、医院组织资源、默认关闭合成worker开关继续生效。无管理员临床例外。下列响应只描述合成非诊断数据。

- `GET /api/requests/{request}/synthetic-results/{id}/validity-history`：按现有任务所有者/资源授权，返回旧Reference、当前DependencyStatus、epoch及原因；private/no-store，只授权历史，无像素或消费许可。
- `GET /api/requests/reports/cases/{case}/synthetic-decisions/{decision}/impact`：精确原决定、当前影响snapshot、sourceValid、consumable、pendingReview、复核版本与最多100条追加历史。executionAllowed=false。查询时状态不是后续操作的授权凭证。
- `GET .../{decision}/current-reference`：只有当前报告修订上的ACCEPT_REFERENCE且全部当前结果/产物/资格门禁有效才返回；否则409 AI_REFERENCE_INVALIDATED。历史下载不能代替此端点。
- `POST .../{decision}/impact-reviews`：CSRF和Idempotency-Key必需；正文confirmedCaseId、decisionId、expectedVersion（首笔-1）、snapshotHash（64位小写SHA256）、action ACKNOWLEDGE或DEFER、reason1–500、confirmed=true。禁止客户端指定actor/time或修改报告。当前有效来源无需失效复核，返回409。

复核回执T07 envelope，resourceType SYNTHETIC_IMPACT_REVIEW。相同键正文/当前epoch返回原回执；异参409，旧epoch AI_IMPACT_CHANGED、CAS AI_IMPACT_CONFLICT。撤权404，认证401，CSRF403，字段400。复核不会使consumable=true；来源仍失效时仅精确epoch的最新ACK消除pendingReview。新依赖再次变化则重新待复核。

审计AI_IMPACT_READ_V1、AI_REFERENCE_CONSUME_V1、AI_IMPACT_REVIEW_V1；写审计与review/head/idempotency原子提交。无外部通知、诊断/签署或原报告重写。
