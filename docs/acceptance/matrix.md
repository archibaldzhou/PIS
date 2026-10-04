# T01–T42 要求、实现、测试与限制矩阵

由 `python3 scripts/acceptance.py --render` 从显式 matrix.json 生成；校验不推断功能正确。

基线完整 CI：[5be3b823a68dbddb2d8962b53492afe618f82f82](https://github.com/archibaldzhou/PIS/actions/runs/37196371021)，父会话核验成功。

任务序列依据用户逐项指令、提交及开发PRD重建；原始招标PRD和独立任务总计划未取得可读正文，不能声称逐条满足。

各行“已验证”仅限对应合成开发范围。原型映射是语义子集，未逐像素复刻；无医院/临床批准。

证据等级和本地/CI执行差异见 [验收报告](report.md)。原型所有47页含未实现项见 [原型索引](prototype-map.json)。

## T01 工程约束落地

- 状态：工程范围已实现并验证；非业务/临床批准
- 开发依据：[docs/adr/0001-engineering-baseline.md](../../docs/adr/0001-engineering-baseline.md)
- 实现：[AGENTS.md](../../AGENTS.md)、[docs/engineering-gates.md](../../docs/engineering-gates.md)
- 迁移：无新增；保留既有基线
- 原型：基础工程，无独立页面
- 回归入口：[scripts/acceptance.py](../../scripts/acceptance.py)
- 后端断言定位：见上述专用检查入口
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：工程基线不是医院批准；无可读原始招标PRD/独立42任务计划。

## T02 Java/React工具链和冻结构建

- 状态：工程范围已实现并验证；非业务/临床批准
- 开发依据：[README.md](../../README.md)
- 实现：[backend/pom.xml](../../backend/pom.xml)、[backend/.mvn/wrapper/maven-wrapper.properties](../../backend/.mvn/wrapper/maven-wrapper.properties)、[frontend/package.json](../../frontend/package.json)、[frontend/package-lock.json](../../frontend/package-lock.json)
- 迁移：无新增；保留既有基线
- 原型：基础工程，无独立页面
- 回归入口：[.github/workflows/ci.yml](../../.github/workflows/ci.yml)
- 后端断言定位：见上述专用检查入口
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：Temurin21补丁与ubuntu运行器未逐摘要冻结；无跨平台JAR逐字节重现保证。

## T03 CI测试与依赖门禁

- 状态：工程范围已实现并验证；非业务/临床批准
- 开发依据：[docs/engineering-gates.md](../../docs/engineering-gates.md)
- 实现：[.github/workflows/ci.yml](../../.github/workflows/ci.yml)
- 迁移：无新增；保留既有基线
- 原型：基础工程，无独立页面
- 回归入口：[frontend/package.json](../../frontend/package.json)、[backend/pom.xml](../../backend/pom.xml)
- 后端断言定位：见上述专用检查入口
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：前端全依赖审计；PR依赖变更检查不等于Java全量扫描，秘密全量扫描仍缺失。

## T04 PostgreSQL/Flyway基础

- 状态：工程范围已实现并验证；非业务/临床批准
- 开发依据：[docs/database-development.md](../../docs/database-development.md)
- 实现：[backend/src/main/resources/application.properties](../../backend/src/main/resources/application.properties)、[compose.yaml](../../compose.yaml)
- 迁移：[backend/src/main/resources/db/migration/V1__initialize_schema.sql](../../backend/src/main/resources/db/migration/V1__initialize_schema.sql)
- 原型：基础工程，无独立页面
- 回归入口：[backend/src/test/java/com/pis/database/PostgresMigrationTest.java](../../backend/src/test/java/com/pis/database/PostgresMigrationTest.java)、[backend/src/test/java/com/pis/database/DatabaseStartupFailureTest.java](../../backend/src/test/java/com/pis/database/DatabaseStartupFailureTest.java)
- 后端断言定位：见上述专用检查入口
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：PG17合成库验证；生产账号分离和规模迁移未验证。

## T05 患者/就诊/申请/病例/容器核心关系

- 状态：工程范围已实现并验证；非业务/临床批准
- 开发依据：[docs/core-data-model.md](../../docs/core-data-model.md)
- 实现：[backend/src/main/java/com/pis/core/CoreModelQueries.java](../../backend/src/main/java/com/pis/core/CoreModelQueries.java)
- 迁移：[backend/src/main/resources/db/migration/V2__core_data_model.sql](../../backend/src/main/resources/db/migration/V2__core_data_model.sql)
- 原型：UI-006
- 回归入口：[backend/src/test/java/com/pis/core/CoreModelConstraintsTest.java](../../backend/src/test/java/com/pis/core/CoreModelConstraintsTest.java)、[backend/src/test/java/com/pis/core/CoreModelVersionTest.java](../../backend/src/test/java/com/pis/core/CoreModelVersionTest.java)
- 后端断言定位：见上述专用检查入口
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：合成身份关系及编号域；没有真实MPI或医院身份合并政策。

## T06 会话、角色和对象作用域

- 状态：工程范围已实现并验证；非业务/临床批准
- 开发依据：[docs/security-access-control.md](../../docs/security-access-control.md)
- 实现：[backend/src/main/java/com/pis/security/SecurityStartupChecks.java](../../backend/src/main/java/com/pis/security/SecurityStartupChecks.java)、[backend/src/main/java/com/pis/security/access/CaseAccessPolicy.java](../../backend/src/main/java/com/pis/security/access/CaseAccessPolicy.java)
- 迁移：[backend/src/main/resources/db/migration/V3__identity_and_resource_access.sql](../../backend/src/main/resources/db/migration/V3__identity_and_resource_access.sql)
- 原型：UI-033
- 回归入口：[backend/src/test/java/com/pis/security/SecurityHttpTest.java](../../backend/src/test/java/com/pis/security/SecurityHttpTest.java)、[backend/src/test/java/com/pis/security/access/CaseAccessPolicyTest.java](../../backend/src/test/java/com/pis/security/access/CaseAccessPolicyTest.java)
- 后端断言定位：见上述专用检查入口
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：显式资格/范围，不提供生产SSO或完整用户授权管理界面；管理员无临床权。

## T07 API错误、原子审计及幂等

- 状态：工程范围已实现并验证；非业务/临床批准
- 开发依据：[docs/api-audit-idempotency.md](../../docs/api-audit-idempotency.md)
- 实现：[backend/src/main/java/com/pis/idempotency/IdempotentCommands.java](../../backend/src/main/java/com/pis/idempotency/IdempotentCommands.java)、[backend/src/main/java/com/pis/api/TraceIdFilter.java](../../backend/src/main/java/com/pis/api/TraceIdFilter.java)
- 迁移：[backend/src/main/resources/db/migration/V4__audit_and_idempotency.sql](../../backend/src/main/resources/db/migration/V4__audit_and_idempotency.sql)
- 原型：UI-037
- 回归入口：[backend/src/test/java/com/pis/idempotency/IdempotentCommandsTest.java](../../backend/src/test/java/com/pis/idempotency/IdempotentCommandsTest.java)、[backend/src/test/java/com/pis/api/TraceIdFilterTest.java](../../backend/src/test/java/com/pis/api/TraceIdFilterTest.java)
- 后端断言定位：见上述专用检查入口
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：同库事务成功审计不是医院合规长期防篡改归档；无OpenAPI生成链。

## T08 申请登记/查询/编辑/提交

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-accession-v1.md](../../docs/prd/development-accession-v1.md)
- 实现：[backend/src/main/java/com/pis/accession/RequestService.java](../../backend/src/main/java/com/pis/accession/RequestService.java)、[frontend/src/features/accession/Registration.tsx](../../frontend/src/features/accession/Registration.tsx)
- 迁移：[backend/src/main/resources/db/migration/V5__development_request_workflow.sql](../../backend/src/main/resources/db/migration/V5__development_request_workflow.sql)
- 原型：UI-002, UI-003, UI-004
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/accession.spec.ts](../../frontend/e2e/accession.spec.ts)
- 后端断言定位：`registersEditsSubmitsAndReplaysWithoutRepeatingHistoryOrContainers`, `auditFailureRollsBackTheWholeRegistrationAndSameKeyCanRecover`, `simultaneousSameKeyCreatesOneRequestAndOneAuditEvent`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：只用既有合成就诊；无真实HIS建档、医院编号或身份合并。

## T09 标本接收、核对、异常及退回

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-accession-v1.md](../../docs/prd/development-accession-v1.md)
- 实现：[backend/src/main/java/com/pis/specimen/ReceptionService.java](../../backend/src/main/java/com/pis/specimen/ReceptionService.java)、[frontend/src/features/specimen/Reception.tsx](../../frontend/src/features/specimen/Reception.tsx)
- 迁移：[backend/src/main/resources/db/migration/V6__specimen_reception.sql](../../backend/src/main/resources/db/migration/V6__specimen_reception.sql)
- 原型：UI-005, UI-007, UI-037
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/reception.spec.ts](../../frontend/e2e/reception.spec.ts)
- 后端断言定位：`writeDoesNotGrantReceptionAndRevocationBlocksReplay`, `receptionAuditFailureRollsBackCaseContainersStateEventAndReceipt`, `competingReceptionsWaitAndOnlyOneCaseIsCreated`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：整申请异常；身份/数量不符阻断，无条件接收或物理交接证明。

## T10 稳定条码和标签重打

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-labels-v1.md](../../docs/prd/development-labels-v1.md)
- 实现：[backend/src/main/java/com/pis/label/LabelService.java](../../backend/src/main/java/com/pis/label/LabelService.java)、[frontend/src/features/labels/Labels.tsx](../../frontend/src/features/labels/Labels.tsx)
- 迁移：[backend/src/main/resources/db/migration/V7__container_label_jobs.sql](../../backend/src/main/resources/db/migration/V7__container_label_jobs.sql)
- 原型：UI-007
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/labels.spec.ts](../../frontend/e2e/labels.spec.ts)
- 后端断言定位：`labelReprintKeepsEntityBarcodeTemplateAndHistoryWithoutCreatingSpecimens`, `labelFailedRetryCancelAreVersionedAndNeverClaimPhysicalSuccess`, `labelsRejectCrossScopeUnreceivedMismatchedBarcodeAndRevokedReprints`, `labelAuditFailureRollsBackIdentityJobAndReceipt`, `labelHttpRequiresCsrfPrintPermissionAndNonblankReprintReason`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：合成标签预览/打印请求，不证明真实扫码器/打印机输出。

## T11 取材记录、合成图像、取材盒

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-grossing-v1.md](../../docs/prd/development-grossing-v1.md)
- 实现：[backend/src/main/java/com/pis/grossing/GrossService.java](../../backend/src/main/java/com/pis/grossing/GrossService.java)、[frontend/src/features/grossing/Grossing.tsx](../../frontend/src/features/grossing/Grossing.tsx)
- 迁移：[backend/src/main/resources/db/migration/V8__grossing_records_and_cassettes.sql](../../backend/src/main/resources/db/migration/V8__grossing_records_and_cassettes.sql)
- 原型：UI-008, UI-009
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/grossing.spec.ts](../../frontend/e2e/grossing.spec.ts)
- 后端断言定位：`grossingDescriptionPhotoAndCassetteKeepCaseLineageAndRevisionHistory`, `grossingSupportsOneSourceSplitAndSameCaseMultipleSourcesWithoutChangingIdentity`, `grossingRejectsUnreceivedCrossCaseSourcesAndNonSyntheticPhotos`, `grossingCancellationAndPhotoWithdrawalKeepHistoryAndBlockNewDownloads`, `grossingPhotoAuditFailureRollsBackMetadataAndCanReplaySameIntentAfterRecovery`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：图像仅固定白名单合成PNG；无通用临床照片上传/真实取材验证。

## T12 技术任务、领取和交接

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-processing-v1.md](../../docs/prd/development-processing-v1.md)
- 实现：[backend/src/main/java/com/pis/processing/TechnicalService.java](../../backend/src/main/java/com/pis/processing/TechnicalService.java)、[frontend/src/features/processing/Technical.tsx](../../frontend/src/features/processing/Technical.tsx)
- 迁移：[backend/src/main/resources/db/migration/V9__technical_tasks_and_handoffs.sql](../../backend/src/main/resources/db/migration/V9__technical_tasks_and_handoffs.sql)
- 原型：UI-010, UI-011, UI-012
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/technical.spec.ts](../../frontend/e2e/technical.spec.ts)
- 后端断言定位：`technicalHandoffRequiresDistinctAuthorizedReceiverAndPreservesReworkLineage`, `technicalSourcesPredecessorsAndPermissionsAreRecheckedWithoutForcingOneRoute`, `technicalHandoffAuditFailureRollsBackOwnershipAndReceipt`, `technicalHttpRequiresAuthenticationDedicatedGrantCsrfAndExactIdentity`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：SIMULATED_DONE不是设备工艺完成；未实现跨病例真实设备批次。

## T13 蜡块/玻片身份、重切与来源链

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-materials-v1.md](../../docs/prd/development-materials-v1.md)
- 实现：[backend/src/main/java/com/pis/material/MaterialService.java](../../backend/src/main/java/com/pis/material/MaterialService.java)、[frontend/src/features/materials/Materials.tsx](../../frontend/src/features/materials/Materials.tsx)
- 迁移：[backend/src/main/resources/db/migration/V10__material_identity_and_lineage.sql](../../backend/src/main/resources/db/migration/V10__material_identity_and_lineage.sql)、[backend/src/main/resources/db/migration/V11__generated_identity_trigger_timing.sql](../../backend/src/main/resources/db/migration/V11__generated_identity_trigger_timing.sql)
- 原型：UI-006, UI-012
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/materials.spec.ts](../../frontend/e2e/materials.spec.ts)
- 后端断言定位：`materialIdsRecutsDeeperVoidingAndLabelReprintsKeepDistinctStableLineage`, `directCytologyHasNoInventedBlockAndCrossCaseSourcesAreRejected`, `materialCreationAndVoidingRollBackIdentityAndCascadeOnAuditFailure`, `materialHttpRequiresDedicatedPermissionCsrfAndRejectsUnknownPaths`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：新身份与直制链验证；实物设备及医院路线未验收。

## T14 技术QC、隔离、撤销和返工

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-quality-v1.md](../../docs/prd/development-quality-v1.md)
- 实现：[backend/src/main/java/com/pis/quality/QualityService.java](../../backend/src/main/java/com/pis/quality/QualityService.java)、[frontend/src/features/quality/Quality.tsx](../../frontend/src/features/quality/Quality.tsx)
- 迁移：[backend/src/main/resources/db/migration/V12__material_quality_and_quarantine.sql](../../backend/src/main/resources/db/migration/V12__material_quality_and_quarantine.sql)
- 原型：UI-014, UI-037
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/quality.spec.ts](../../frontend/e2e/quality.spec.ts)
- 后端断言定位：`qualityQuarantineReworkAndNewSlideKeepExactVersionsAndIndependentOutcomes`, `qualityIdentityHoldIsStickyAndPermissionsVersionsScopesRemainEnforced`, `qualityAuditFailureRollsBackJudgementAndReworkTaskTogether`, `qualityRevocationRacingLabelConsumptionObservesDatabaseLocksAndBlocksFurtherUse`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：人工合成QC；无医院缺陷标准、异常放行或真实仪器QC。

## T15 工作列表、批量领取和追踪

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-worklist-v1.md](../../docs/prd/development-worklist-v1.md)
- 实现：[backend/src/main/java/com/pis/worklist/WorklistService.java](../../backend/src/main/java/com/pis/worklist/WorklistService.java)、[frontend/src/features/worklist/Worklist.tsx](../../frontend/src/features/worklist/Worklist.tsx)
- 迁移：[backend/src/main/resources/db/migration/V13__scoped_worklist_and_trace_projections.sql](../../backend/src/main/resources/db/migration/V13__scoped_worklist_and_trace_projections.sql)
- 原型：UI-001, UI-006
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/worklist.spec.ts](../../frontend/e2e/worklist.spec.ts)
- 后端断言定位：`worklistCountsPaginationAndTraceUseDomainPermissionsAndScopedRealStates`, `bulkClaimsReportPartialFailureMaskForeignIdsAndNeverOverrideVersionOrQuarantine`, `worklistHttpPreservesSessionCsrfWhitelistPagingAndCurrentDomainPermissions`, `worklistSqlDeadlineUsesExactBoundaryAndExcludesCompletedItems`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：当前页逐项处理，不是全院危急消息平台；超期阈值仅开发规则。

## T16 诊断分配、领取与转交

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-diagnosis-assignment-v1.md](../../docs/prd/development-diagnosis-assignment-v1.md)
- 实现：[backend/src/main/java/com/pis/diagnosis/DiagnosisService.java](../../backend/src/main/java/com/pis/diagnosis/DiagnosisService.java)、[frontend/src/features/diagnosis/Diagnosis.tsx](../../frontend/src/features/diagnosis/Diagnosis.tsx)
- 迁移：[backend/src/main/resources/db/migration/V14__diagnosis_assignment.sql](../../backend/src/main/resources/db/migration/V14__diagnosis_assignment.sql)
- 原型：UI-019
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/diagnosis.spec.ts](../../frontend/e2e/diagnosis.spec.ts)
- 后端断言定位：`diagnosisAssignClaimTransferRequireExplicitQualifiedScopeAndKeepHistory`, `diagnosisBlocksExpiredTargetsIdentityQuarantineAndAuditFailureRollsBackEverything`, `diagnosisConcurrentAssignClaimTransferHaveOneCasWinnerAfterActualRootLockWait`, `diagnosisTargetRevocationDuringLockWaitRejectsAssignmentWithoutReceipt`, `diagnosisHttpMaintainsCsrfFieldsAndNoReportEndpoints`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：限时合成资格；没有真实排班自动派单或临床授权认证。

## T17 结构化字段、不可变模板与草稿

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-report-drafts-v1.md](../../docs/prd/development-report-drafts-v1.md)
- 实现：[backend/src/main/java/com/pis/report/ReportService.java](../../backend/src/main/java/com/pis/report/ReportService.java)、[frontend/src/features/report/ReportEditor.tsx](../../frontend/src/features/report/ReportEditor.tsx)
- 迁移：[backend/src/main/resources/db/migration/V15__versioned_report_drafts.sql](../../backend/src/main/resources/db/migration/V15__versioned_report_drafts.sql)
- 原型：UI-020, UI-034
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/report.spec.ts](../../frontend/e2e/report.spec.ts)
- 后端断言定位：`reportDraftBindsImmutableTemplatesHistoryAndCurrentClaimedDoctor`, `concurrentReportRevisionsHaveOneWinnerAfterRealRequestLockWait`, `reportHttpRequiresOwnerCsrfAndRejectsUnknownFieldsAndTemplateVersions`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：人工合成内容；无医院模板审批/术语系统或自动诊断。

## T18 复核、退回及模拟冻结

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-report-review-v1.md](../../docs/prd/development-report-review-v1.md)
- 实现：[backend/src/main/java/com/pis/report/ReviewService.java](../../backend/src/main/java/com/pis/report/ReviewService.java)、[frontend/src/features/report/ReviewEditor.tsx](../../frontend/src/features/report/ReviewEditor.tsx)
- 迁移：[backend/src/main/resources/db/migration/V16__synthetic_report_review.sql](../../backend/src/main/resources/db/migration/V16__synthetic_report_review.sql)
- 原型：UI-022
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/review.spec.ts](../../frontend/e2e/review.spec.ts)
- 后端断言定位：`reviewSeparationReturnAndSimulationFreezeKeepExactSnapshots`, `reviewInvalidatesAfterDraftQcAssignmentAndQualificationChanges`, `reviewAuditFailureRollsBackHeadEventAndReceipt`, `reviewHttpRequiresExplicitQualificationAndExactWhitelistedSnapshot`, `reviewRacesWithDraftQcAndAssignmentAlwaysLoseReadinessAfterDependencyChanges`, `reviewQualificationRevocationDuringGrantLockWaitCannotAuthorizeCommand`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：仅显式合成模拟签署，无临床、CA或法律效力；医院策略待批准。

## T19 固定PDF、预览与打印记录

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-report-output-v1.md](../../docs/prd/development-report-output-v1.md)
- 实现：[backend/src/main/java/com/pis/report/OutputService.java](../../backend/src/main/java/com/pis/report/OutputService.java)、[frontend/src/features/report/OutputEditor.tsx](../../frontend/src/features/report/OutputEditor.tsx)
- 迁移：[backend/src/main/resources/db/migration/V17__fixed_report_artifact_and_print_history.sql](../../backend/src/main/resources/db/migration/V17__fixed_report_artifact_and_print_history.sql)
- 原型：UI-024
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/output.spec.ts](../../frontend/e2e/output.spec.ts)
- 后端断言定位：`outputCurrentScopePrintRightsOwnResultAndQcAreEnforcedWithoutRegeneratingHistory`, `outputCreationAndAccessAuditFailuresRollbackArtifactHistoryAndReceipts`, `concurrentArtifactCreationKeepsOneBlobAndConcurrentPrintRequestsHaveOneCasWinner`, `outputHttpProtectsBinaryHeadersCsrfHashScopeAndForbidsUnauditedGetOrHardwareStatus`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：小型合成PDF≤4MiB/16页；打印自报不证明硬件成功。

## T20 报告补充、更正和版本链

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-report-amendments-v1.md](../../docs/prd/development-report-amendments-v1.md)
- 实现：[backend/src/main/java/com/pis/report/AmendmentService.java](../../backend/src/main/java/com/pis/report/AmendmentService.java)、[frontend/src/features/report/AmendmentEditor.tsx](../../frontend/src/features/report/AmendmentEditor.tsx)
- 迁移：[backend/src/main/resources/db/migration/V18__report_amendment_chain.sql](../../backend/src/main/resources/db/migration/V18__report_amendment_chain.sql)
- 原型：UI-023
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/amendment.spec.ts](../../frontend/e2e/amendment.spec.ts)
- 后端断言定位：`amendmentRequiresNewReviewAndKeepsOriginalPdfAndFrozenSnapshots`, `amendmentAuthorizationQcAndAuditFailureDoNotAdvanceChain`, `concurrentAmendmentCreationHasOneWinnerAndOneExplicitConflict`, `amendmentHttpRequiresCsrfStrictTypeReasonAndCurrentObjectScope`, `amendmentNewFreezeAndReplacementRollbackTogetherWhenAuditFails`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：旧PDF/冻结内容不改写；真实下游替换未接入。

## T21 本地投递、ACK、重试、对账和CA接口

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-delivery-v1.md](../../docs/prd/development-delivery-v1.md)
- 实现：[backend/src/main/java/com/pis/report/DeliveryService.java](../../backend/src/main/java/com/pis/report/DeliveryService.java)、[frontend/src/features/report/DeliveryEditor.tsx](../../frontend/src/features/report/DeliveryEditor.tsx)
- 迁移：[backend/src/main/resources/db/migration/V19__local_delivery_outbox_inbox.sql](../../backend/src/main/resources/db/migration/V19__local_delivery_outbox_inbox.sql)
- 原型：UI-024, UI-032
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/delivery.spec.ts](../../frontend/e2e/delivery.spec.ts)
- 后端断言定位：`deliveryRequiresRealLocalInboxBeforeAckAndDeduplicatesReceiver`, `deliveryTimeoutRejectsLateAckAndPoisonTerminatesWithRollbackOnAuditFailure`, `concurrentDeliveryClaimsHaveExactlyOneAttemptAndOldCasCannotProgress`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：仅本地outbox/inbox；HTTP成功非业务ACK；CA未配置，外部投递未实现。

## T22 独立冰冻、人工计时和沟通版本

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-frozen-v1.md](../../docs/prd/development-frozen-v1.md)
- 实现：[backend/src/main/java/com/pis/frozen/FrozenService.java](../../backend/src/main/java/com/pis/frozen/FrozenService.java)、[frontend/src/features/frozen/Frozen.tsx](../../frontend/src/features/frozen/Frozen.tsx)
- 迁移：[backend/src/main/resources/db/migration/V20__independent_synthetic_frozen.sql](../../backend/src/main/resources/db/migration/V20__independent_synthetic_frozen.sql)
- 原型：UI-016, UI-025
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/frozen.spec.ts](../../frontend/e2e/frozen.spec.ts)
- 后端断言定位：`frozenIsIndependentOfRoutineRoutingAndCommunicationProofIsSeparate`, `frozenRevocationQcDraftAndHandoffInvalidateExactReviews`, `frozenTimeCorrectionIsAppendOnlyAndCannotReverseDependentTimes`, `frozenAuditFailureRollsBackEventHeadAndIdempotency`, `frozenDraftAndReviewRacesHaveOneCasWinnerAfterObservedRootLockWait`, `frozenForeignScopesAdminAndRevokedReplaysCannotAccess`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：人工合成沟通不等于电话送达；无自动诊断/医院TAT承诺。

## T23 细胞学三路径与无蜡块玻片链

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-cytology-v1.md](../../docs/prd/development-cytology-v1.md)
- 实现：[backend/src/main/java/com/pis/material/CytologyService.java](../../backend/src/main/java/com/pis/material/CytologyService.java)、[frontend/src/features/materials/Cytology.tsx](../../frontend/src/features/materials/Cytology.tsx)
- 迁移：[backend/src/main/resources/db/migration/V21__cytology_preparation_lineage.sql](../../backend/src/main/resources/db/migration/V21__cytology_preparation_lineage.sql)
- 原型：UI-015
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/cytology.spec.ts](../../frontend/e2e/cytology.spec.ts)
- 后端断言定位：`cytologyAllPathsKeepIndependentIdsExactConsumptionAndCannotBypassLedger`, `cytologyFailureReturnsAccountedStockButNewPreparationHasIndependentIdentityAndSourceGeneration`, `cytologyForeignScopeAndRevokedQualificationDenyReadAndReplay`, `cytologyAuditFailureRollsBackMaterialLabelsStockAndRetryKey`, `cytologyConcurrentReservationsAndCompletionsHaveOneCasWinner`, `cytologyHttpChecksCsrfWhitelistObjectScopeAndKeyPayload`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：直接涂片/液基/可选细胞蜡块合成元数据；无临床分类或真实制备设备。

## T24 特殊染色/IHC批次与对照

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-staining-v1.md](../../docs/prd/development-staining-v1.md)
- 实现：[backend/src/main/java/com/pis/material/StainService.java](../../backend/src/main/java/com/pis/material/StainService.java)、[frontend/src/features/materials/Staining.tsx](../../frontend/src/features/materials/Staining.tsx)
- 迁移：[backend/src/main/resources/db/migration/V22__stain_batches_and_controls.sql](../../backend/src/main/resources/db/migration/V22__stain_batches_and_controls.sql)
- 原型：UI-013, UI-017, UI-018
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/staining.spec.ts](../../frontend/e2e/staining.spec.ts)
- 后端断言定位：`stainingFreezesExactOneForOneLineageAndControlRevocationInvalidatesAcceptedResult`, `stainingFailureAndChangedSourceNeverRestoreOldResultOrConsumeSourceTwice`, `stainingSchemeVersionMetadataIsImmutableAndCrossScopeSourceIsRejected`, `stainingAuditFailureRestoresOldSlideAndRollsBackNewIdentityLabelMembersAndCommand`, `stainingConcurrentResultAndRevokeHaveOneCasWinnerAndRevokeEventuallyInvalidatesResult`, `stainingDuplicateSourcesAndRevokedReplayFailWithoutAdditionalIdentities`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：单病例冻结成员、人工合成对照；无厂商方案或真实仪器验证。

## T25 院内会诊、复阅、意见与分歧

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-consultation-v1.md](../../docs/prd/development-consultation-v1.md)
- 实现：[backend/src/main/java/com/pis/report/ConsultationService.java](../../backend/src/main/java/com/pis/report/ConsultationService.java)、[frontend/src/features/report/ConsultationEditor.tsx](../../frontend/src/features/report/ConsultationEditor.tsx)
- 迁移：[backend/src/main/resources/db/migration/V23__case_scoped_consultations.sql](../../backend/src/main/resources/db/migration/V23__case_scoped_consultations.sql)
- 原型：UI-021
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/consultation.spec.ts](../../frontend/e2e/consultation.spec.ts)
- 后端断言定位：`consultationRequiresExplicitConfirmationAndAdoptsOnlyNewNotesRevision`, `consultationNewOpinionInvalidatesExactSummaryAndForeignOrUninvitedUsersCannotRead`, `consultationInputRevisionQcAndAssignmentChangesInvalidateWithoutDeletingOpinions`, `consultationRevocationBlocksParticipantReadsWritesAndIdempotentReplay`, `consultationAuditFailureRollsBackAdoptionAndRetryCreatesExactlyOneRevision`, `consultationParallelPersonalOpinionsHaveExplicitCasConflict`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：仅限时病例授权和人工意见；无院外共享/真实邀请/自动多数诊断。

## T26 归档、预约借还及冻结盘点

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-archive-v1.md](../../docs/prd/development-archive-v1.md)
- 实现：[backend/src/main/java/com/pis/archive/ArchiveService.java](../../backend/src/main/java/com/pis/archive/ArchiveService.java)、[frontend/src/features/archive/Archive.tsx](../../frontend/src/features/archive/Archive.tsx)
- 迁移：[backend/src/main/resources/db/migration/V24__archive_custody.sql](../../backend/src/main/resources/db/migration/V24__archive_custody.sql)
- 原型：UI-026, UI-027
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/archive.spec.ts](../../frontend/e2e/archive.spec.ts)
- 后端断言定位：`archiveReservationPartialReturnAndExactReplayRemainManual`, `archiveWrongBarcodeForeignItemAndRevokedReplayAreRejected`, `archiveInventoryDifferenceAndStaleSnapshotKeepOriginalHistory`, `archiveQcRevocationAndLossNeverEraseTraceabilityOrAllowLoan`, `archiveAuditFailureRollsBackItemRootEventAndOriginalKeyCanRetry`, `archiveConcurrentReservationHasOneWinnerAndOneVersionConflict`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：人工登记与物理事实分离；无自动实物确认、外部借阅或销毁。

## T27 工作量/TAT/QC固定统计快照

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-statistics-v1.md](../../docs/prd/development-statistics-v1.md)
- 实现：[backend/src/main/java/com/pis/statistics/StatisticsService.java](../../backend/src/main/java/com/pis/statistics/StatisticsService.java)、[frontend/src/features/statistics/Statistics.tsx](../../frontend/src/features/statistics/Statistics.tsx)
- 迁移：[backend/src/main/resources/db/migration/V25__synthetic_statistics_snapshots.sql](../../backend/src/main/resources/db/migration/V25__synthetic_statistics_snapshots.sql)
- 原型：UI-028, UI-031
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/statistics.spec.ts](../../frontend/e2e/statistics.spec.ts)
- 后端断言定位：`statisticsScopesBeforeAggregatingAndRevocationBlocksSavedTotalsAndReplay`, `statisticsFixedSnapshotDeduplicatesRetriesAndProtectsHistory`, `statisticsQcSnapshotRetainsOldEvidenceWhileNewSnapshotReflectsRevocation`, `statisticsAuditFailureRollsBackSnapshotAndPreservesRetryKey`, `statisticsConcurrentSameKeyFreezesOneSnapshot`, `statisticsHttpRequiresCsrfStrictFilterAndCurrentScope`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：实际保存合成事件、自然时长，开放病例单列；无CSV、医院SLA、工作日历或全院BI。

## T28 私有原件版本、哈希和容量

- 状态：本地provider合成验证；S3接口未配置/未验证
- 开发依据：[docs/prd/development-storage-v1.md](../../docs/prd/development-storage-v1.md)
- 实现：[backend/src/main/java/com/pis/storage/StorageService.java](../../backend/src/main/java/com/pis/storage/StorageService.java)、[frontend/src/features/storage/Storage.tsx](../../frontend/src/features/storage/Storage.tsx)
- 迁移：[backend/src/main/resources/db/migration/V26__private_original_storage.sql](../../backend/src/main/resources/db/migration/V26__private_original_storage.sql)
- 原型：UI-042
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/storage.spec.ts](../../frontend/e2e/storage.spec.ts)
- 后端断言定位：`storagePreservesOriginalVersionsAndReplaysFinalizeWithoutDuplicateOutbox`, `storageFailedStageIsDryRunOnlyAndDoesNotExposeBytes`, `storageFileCompletionSurvivesDatabaseAuditRollbackAndReconcilesExactBytes`, `storageStageRecoveryAndRevokedScopeRemainFailClosed`, `storageQuotaRaceReservesOnlyOneBoundedOriginal`, `storageHttpKeepsAuthenticationCsrfRangeAndStrictMetadata`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：本地受控根目录provider；S3未配置/未集成，容量只反映该文件系统。

## T29 扫描导入、身份映射和重扫

- 状态：合成导入已验证；厂商格式与设备接入未实现
- 开发依据：[docs/prd/development-scan-import-v1.md](../../docs/prd/development-scan-import-v1.md)
- 实现：[backend/src/main/java/com/pis/scan/ScanService.java](../../backend/src/main/java/com/pis/scan/ScanService.java)、[frontend/src/features/scan/Scan.tsx](../../frontend/src/features/scan/Scan.tsx)
- 迁移：[backend/src/main/resources/db/migration/V27__synthetic_scan_import.sql](../../backend/src/main/resources/db/migration/V27__synthetic_scan_import.sql)
- 原型：UI-038
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/scan.spec.ts](../../frontend/e2e/scan.spec.ts)
- 后端断言定位：`scanExactObjectIdentityAndRepeatCompletionNeverPublish`, `scanBothManualAndFileIdentityMismatchStayQuarantined`, `scanCancellationRejectsLateResultAndRescanKeepsOriginal`, `scanAuditFailureRollsBackHeadJobAndHistory`, `scanParallelClaimsUseOneAttemptAndQcChangeInvalidatesCompletion`, `scanBatchReportsEachItemAndRevocationDeniesReadsAndReplay`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：有界合成格式白名单；真实厂商WSI/OpenSlide和扫描仪未配置，不伪造预览。

## T30 数字扫描QC、隔离和发布

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-digital-qc-v1.md](../../docs/prd/development-digital-qc-v1.md)
- 实现：[backend/src/main/java/com/pis/digitalqc/DigitalQcService.java](../../backend/src/main/java/com/pis/digitalqc/DigitalQcService.java)、[frontend/src/features/digitalqc/DigitalQc.tsx](../../frontend/src/features/digitalqc/DigitalQc.tsx)
- 迁移：[backend/src/main/resources/db/migration/V28__digital_scan_qc.sql](../../backend/src/main/resources/db/migration/V28__digital_scan_qc.sql)
- 原型：UI-039
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/digitalqc.spec.ts](../../frontend/e2e/digitalqc.spec.ts)
- 后端断言定位：`digitalQcRequiresEveryExplicitItemAndNeverInfersImageCapability`, `digitalQcDefectRegionsAndWrongVersionCannotPass`, `digitalQcRevocationAndRepeatedPublishReceiptNeverRestoreAccess`, `digitalQcNewEvaluationRevokesPublishedVersionWithoutOverwritingHistory`, `digitalQcRescanInvalidatesOldPublicationAndCannotBorrowItsChecklist`, `digitalQcSourceRevocationIsEffectiveWithoutRewritingHistoricalPublication`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：精确版本人工检查单；不是图像算法或真实WSI质量验证。

## T31 OpenSeadragon、真实合成瓦片与缓存

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-tile-viewer-v1.md](../../docs/prd/development-tile-viewer-v1.md)
- 实现：[backend/src/main/java/com/pis/viewer/ViewerService.java](../../backend/src/main/java/com/pis/viewer/ViewerService.java)、[frontend/src/features/viewer/TileViewer.tsx](../../frontend/src/features/viewer/TileViewer.tsx)
- 迁移：[backend/src/main/resources/db/migration/V29__viewer_manifest.sql](../../backend/src/main/resources/db/migration/V29__viewer_manifest.sql)
- 原型：UI-040
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/viewer.spec.ts](../../frontend/e2e/viewer.spec.ts)
- 后端断言定位：`viewerExactManifestBinaryAndCacheStillRequireCurrentQcAndVersion`, `viewerCacheEvictsAcrossNineIndependentScopesAndColdInstanceRechecksAuthorization`, `viewerDoesNotInventPixelsFromHeaderOnlyFormat`, `viewerCrossScopeAndRevokedPermissionCannotUseCachedPng`, `viewerManifestAuditRollbackDoesNotExposeCachedArtifact`, `viewerConcurrentPreparationFreezesOneManifestAndNoOverwrite`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：实际PNG金字塔逐资源授权；普通合成栅格不代表真实WSI/GPU兼容。

## T32 ROI、测量、双视图及坐标版本

- 状态：合成开发范围已验证；完整医院/原型范围部分实现
- 开发依据：[docs/prd/development-roi-v1.md](../../docs/prd/development-roi-v1.md)
- 实现：[backend/src/main/java/com/pis/roi/RoiService.java](../../backend/src/main/java/com/pis/roi/RoiService.java)、[frontend/src/features/viewer/RoiEditor.tsx](../../frontend/src/features/viewer/RoiEditor.tsx)
- 迁移：[backend/src/main/resources/db/migration/V30__roi_versions.sql](../../backend/src/main/resources/db/migration/V30__roi_versions.sql)
- 原型：UI-041
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/viewer.spec.ts](../../frontend/e2e/viewer.spec.ts)
- 后端断言定位：`roiPersistsExactPixelsCasAuthorAndAppendOnlyHistory`, `roiCalibrationHasIndependentXYAndNeverRewritesOldMeasurements`, `roiQcRevocationBlocksCurrentAndEditsButExplicitAuthorizedHistoryRemains`, `roiConcurrentSavesShareCollectionCas`, `roiAuditFailureRollsBackHeadRevisionAndOriginalKeyCanRetry`, `roiCountLimitAndForeignAuthorCannotBeBypassed`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：像素坐标/独立XY合成校准；无临床精度或自动配准，重扫不迁移标注。

## T33 格式、色彩、坐标、负载和故障验证

- 状态：合成管线开发验证；真实WSI/ICC/GPU未验证
- 开发依据：[docs/prd/development-viewer-validation-v1.md](../../docs/prd/development-viewer-validation-v1.md)
- 实现：[backend/src/main/java/com/pis/viewer/ViewerService.java](../../backend/src/main/java/com/pis/viewer/ViewerService.java)、[frontend/src/features/viewer/TileViewer.tsx](../../frontend/src/features/viewer/TileViewer.tsx)
- 迁移：无新增；保留既有基线
- 原型：UI-040, UI-041
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/viewer.spec.ts](../../frontend/e2e/viewer.spec.ts)
- 后端断言定位：`viewerCacheEvictsAcrossNineIndependentScopesAndColdInstanceRechecksAuthorization`, `viewerHttpRequiresAuthAndEachTileUsesPrivateHeaders`, `viewerHttpAccountingRollsBackWithBusinessAndNeverCachesQcAuthorization`, `viewerCachedReadAuditFailureNeverReturnsBytes`, `roiPersistsExactPixelsCasAuthorAndAppendOnlyHistory`, `roiCalibrationHasIndependentXYAndNeverRewritesOldMeasurements`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：开发32固定任务/128MiB探针；真实WSI、ICC临床保真、GPU和校准显示器未验证。

## T34 AI模型注册与适用资格契约

- 状态：合成契约范围已验证；临床AI执行未实现且禁止
- 开发依据：[docs/prd/development-ai-registry-v1.md](../../docs/prd/development-ai-registry-v1.md)
- 实现：[backend/src/main/java/com/pis/ai/AiRegistryService.java](../../backend/src/main/java/com/pis/ai/AiRegistryService.java)、[frontend/src/features/ai/AiRegistry.tsx](../../frontend/src/features/ai/AiRegistry.tsx)
- 迁移：[backend/src/main/resources/db/migration/V31__synthetic_ai_registry.sql](../../backend/src/main/resources/db/migration/V31__synthetic_ai_registry.sql)
- 原型：UI-043
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/viewer.spec.ts](../../frontend/e2e/viewer.spec.ts)
- 后端断言定位：`aiImmutableVersionsSeparateQualificationStateCasAndReplay`, `aiExactAssessmentCannotSurviveProfileModelOrQcRevocation`, `aiRegisterAuditRollbackPreservesOriginalKey`, `aiConcurrentMetadataVersionsHaveExactlyOneHead`, `aiHttpKeepsCsrfStrictDtoAndRevokedGrant`, `aiStateRaceAndQualificationRevocationNeverCreateClinicalRights`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：只有元数据，无权重、性能证书或临床APPROVED；executionAllowed始终false。

## T35 持久化合成任务、租约、取消及恢复

- 状态：合成契约范围已验证；临床AI执行未实现且禁止
- 开发依据：[docs/prd/development-synthetic-worker-v1.md](../../docs/prd/development-synthetic-worker-v1.md)
- 实现：[backend/src/main/java/com/pis/ai/AiTaskService.java](../../backend/src/main/java/com/pis/ai/AiTaskService.java)、[frontend/src/features/ai/SyntheticTasks.tsx](../../frontend/src/features/ai/SyntheticTasks.tsx)
- 迁移：[backend/src/main/resources/db/migration/V32__synthetic_contract_tasks.sql](../../backend/src/main/resources/db/migration/V32__synthetic_contract_tasks.sql)
- 原型：UI-044
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/results.spec.ts](../../frontend/e2e/results.spec.ts)
- 后端断言定位：`syntheticTaskPersistsOutboxExactBindingArtifactAndOneCallback`, `syntheticTaskCancelRejectsLateWrongAndUntrustedCallbacks`, `syntheticTaskRevocationPreventsAcceptanceAndConsumption`, `syntheticTaskQcRevocationDuringLeaseNeverBecomesResult`, `syntheticTaskConcurrentClaimHasSingleGeneration`, `syntheticTaskFrozenClockTimeoutBackoffAndBoundedAttempts`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：技术合成worker不执行模型；独立开关默认false；不证明GPU任务或真实推理。

## T36 不可变合成结果与实际热图叠加

- 状态：合成契约范围已验证；临床AI执行未实现且禁止
- 开发依据：[docs/prd/development-synthetic-result-v1.md](../../docs/prd/development-synthetic-result-v1.md)
- 实现：[backend/src/main/java/com/pis/ai/AiResultService.java](../../backend/src/main/java/com/pis/ai/AiResultService.java)、[frontend/src/features/viewer/ResultOverlay.tsx](../../frontend/src/features/viewer/ResultOverlay.tsx)
- 迁移：[backend/src/main/resources/db/migration/V33__synthetic_result_versions.sql](../../backend/src/main/resources/db/migration/V33__synthetic_result_versions.sql)
- 原型：UI-045
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/results.spec.ts](../../frontend/e2e/results.spec.ts)
- 后端断言定位：`syntheticResultBindsImmutableBytesAndReplaysWithoutDuplicateIdentity`, `syntheticResultSchemaHasActualBoundedPixelsAndRejectsNonFiniteGeometry`, `syntheticResultAuditFailureLeavesRecoverableUnpublishedVersion`, `syntheticResultModelAndScopeRevocationRejectMetadataTileAndReplay`, `syntheticResultQcRevocationBlocksPreparedCompletion`, `syntheticResultConcurrentPrepareHasOneIdentity`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：实际绑定像素为合成强度，不是疾病风险/阴性；没有真实模型输出。

## T37 医生人工采纳/拒绝/暂缓及报告引用

- 状态：合成契约范围已验证；临床AI执行未实现且禁止
- 开发依据：[docs/prd/development-synthetic-decision-v1.md](../../docs/prd/development-synthetic-decision-v1.md)
- 实现：[backend/src/main/java/com/pis/report/SyntheticDecisionService.java](../../backend/src/main/java/com/pis/report/SyntheticDecisionService.java)、[frontend/src/features/report/SyntheticDecision.tsx](../../frontend/src/features/report/SyntheticDecision.tsx)
- 迁移：[backend/src/main/resources/db/migration/V34__synthetic_report_decisions.sql](../../backend/src/main/resources/db/migration/V34__synthetic_report_decisions.sql)
- 原型：UI-046
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/decisions.spec.ts](../../frontend/e2e/decisions.spec.ts)
- 后端断言定位：`syntheticDecisionAcceptCopiesOnlyManualFieldsAndReplaysSingleImmutableReference`, `syntheticDecisionRejectAndDeferKeepReportAndAuditFailureRollsEverythingBack`, `syntheticDecisionConcurrentChoicesHaveOneWinner`, `syntheticDecisionRevokedResultBlocksNewAndReplayButKeepsAuthorizedHistory`, `syntheticDecisionFrozenAndForeignCaseRemainRejected`, `syntheticDecisionHttpStrictCsrfBindingConfirmationAndQualifiedScope`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：只写明确非诊断引用，不自动写诊断、改签署内容或授予执行许可。

## T38 模型/QC/重扫失效与人工复核

- 状态：合成契约范围已验证；临床AI执行未实现且禁止
- 开发依据：[docs/prd/development-synthetic-impact-v1.md](../../docs/prd/development-synthetic-impact-v1.md)
- 实现：[backend/src/main/java/com/pis/report/SyntheticDecisionService.java](../../backend/src/main/java/com/pis/report/SyntheticDecisionService.java)、[frontend/src/features/report/ImpactReview.tsx](../../frontend/src/features/report/ImpactReview.tsx)
- 迁移：[backend/src/main/resources/db/migration/V35__synthetic_reference_impact_review.sql](../../backend/src/main/resources/db/migration/V35__synthetic_reference_impact_review.sql)
- 原型：UI-047
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/decisions.spec.ts](../../frontend/e2e/decisions.spec.ts)
- 后端断言定位：`syntheticImpactKeepsSignedReportAndNewDependencyEpochReopensReview`, `syntheticImpactQcAndRescanRetainOldIdentityAndRejectConsumption`, `syntheticImpactConcurrentReviewAndAuditFailureAreAtomic`, `syntheticImpactRevocationRaceNeverLeavesConsumableReference`, `syntheticImpactHttpRetainsCsrfScopeAndCurrentReferenceSeparation`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：当前消费拒绝与授权历史分离；不自动用新结果替代旧版或重新诊断。

## T39 HIS/EMR/收费/设备合成合同

- 状态：本地合成适配部分实现；医院实际接口未配置
- 开发依据：[docs/prd/development-hospital-adapter-v1.md](../../docs/prd/development-hospital-adapter-v1.md)
- 实现：[backend/src/main/java/com/pis/integration/HospitalAdapterService.java](../../backend/src/main/java/com/pis/integration/HospitalAdapterService.java)、[frontend/src/features/integration/Adapters.tsx](../../frontend/src/features/integration/Adapters.tsx)
- 迁移：[backend/src/main/resources/db/migration/V36__local_hospital_adapter.sql](../../backend/src/main/resources/db/migration/V36__local_hospital_adapter.sql)
- 原型：UI-032
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[frontend/e2e/adapters.spec.ts](../../frontend/e2e/adapters.spec.ts)
- 后端断言定位：`localAdapterDeduplicatesBusinessWithoutConfusingReceiveAckOrReconciliation`, `localAdapterOrderRepairAndLostAckRecoveryKeepOneBusinessRecord`, `localAdapterExhaustionPoisonAndCancellationNeverInventSuccess`, `localAdapterAtomicAuditFailureDoesNotConsumeSequenceOrReceipt`, `localAdapterParallelClaimsHaveOneLeaseAndRevokedReplayIsDenied`, `localAdapterHttpStrictIdentityCsrfBodyBoundsAndCurrentScope`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)
- 限制：自定义SYN-HOSPITAL-1及本地EMR别名；HL7/FHIR/厂商/真实支付均未配置。

## T40 受限运维、脱敏日志及实际隔离备份恢复

- 状态：受限运维与隔离合成恢复已验证；生产灾备未实现
- 开发依据：[docs/prd/T40-operations-recovery.md](../../docs/prd/T40-operations-recovery.md)
- 实现：[backend/src/main/java/com/pis/operations/OperationsService.java](../../backend/src/main/java/com/pis/operations/OperationsService.java)、[backend/src/main/java/com/pis/security/SecurityStartupChecks.java](../../backend/src/main/java/com/pis/security/SecurityStartupChecks.java)、[backend/src/main/java/com/pis/api/TraceIdFilter.java](../../backend/src/main/java/com/pis/api/TraceIdFilter.java)、[scripts/synthetic-recovery.py](../../scripts/synthetic-recovery.py)、[frontend/src/features/operations/Operations.tsx](../../frontend/src/features/operations/Operations.tsx)
- 迁移：[backend/src/main/resources/db/migration/V37__synthetic_operations_scope.sql](../../backend/src/main/resources/db/migration/V37__synthetic_operations_scope.sql)
- 原型：UI-035, UI-036
- 回归入口：[backend/src/test/java/com/pis/accession/RequestWorkflowTest.java](../../backend/src/test/java/com/pis/accession/RequestWorkflowTest.java)、[backend/src/test/java/com/pis/security/SecurityStartupChecksTest.java](../../backend/src/test/java/com/pis/security/SecurityStartupChecksTest.java)、[backend/src/test/java/com/pis/api/TraceIdFilterTest.java](../../backend/src/test/java/com/pis/api/TraceIdFilterTest.java)、[frontend/e2e/adapters.spec.ts](../../frontend/e2e/adapters.spec.ts)、[scripts/synthetic-recovery.py](../../scripts/synthetic-recovery.py)
- 后端断言定位：`operationsRequiresExplicitCurrentScopeAndAuditsWithoutExposingContent`, `operationsReadCannotSucceedWhenAuditFails`
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)、[docs/evidence/t41/recovery-gate.txt](../../docs/evidence/t41/recovery-gate.txt)
- 限制：真实PG/对象合成恢复；未实现生产在线一致备份、加密/异地、RPO/RTO及合规认证。

## T41 构建产物代理、部署前检查和安全回切

- 状态：同构建双实例合成演练已验证；历史二进制与生产未验证
- 开发依据：[docs/prd/T41-local-release-rehearsal.md](../../docs/prd/T41-local-release-rehearsal.md)
- 实现：[scripts/deployment/local_proxy.py](../../scripts/deployment/local_proxy.py)、[scripts/deployment/release.py](../../scripts/deployment/release.py)、[scripts/deployment/rehearse.py](../../scripts/deployment/rehearse.py)
- 迁移：无新增；保留既有基线
- 原型：UI-035, UI-036
- 回归入口：[scripts/deployment/test_deployment.py](../../scripts/deployment/test_deployment.py)、[scripts/deployment/test_proxy_lifecycle.py](../../scripts/deployment/test_proxy_lifecycle.py)、[frontend/dist-tests/real.spec.ts](../../frontend/dist-tests/real.spec.ts)、[frontend/dist-tests/contract.spec.ts](../../frontend/dist-tests/contract.spec.ts)
- 后端断言定位：见上述专用检查入口
- 证据：[docs/acceptance/baseline-evidence.json](../../docs/acceptance/baseline-evidence.json)、[docs/evidence/t41-port-fix/contracts.txt](../../docs/evidence/t41-port-fix/contracts.txt)、[docs/evidence/t41-port-fix/dist-second.txt](../../docs/evidence/t41-port-fix/dist-second.txt)
- 限制：sameBuiltArtifactTwoInstances=true；不同历史二进制兼容未测；TLS/域名未配置，productionReady=false。

## T42 全任务开发验收、矩阵及中文运行指南

- 状态：开发验收待本提交CI
- 开发依据：[docs/prd/T42-development-acceptance.md](../../docs/prd/T42-development-acceptance.md)
- 实现：[docs/acceptance/matrix.json](../../docs/acceptance/matrix.json)、[docs/acceptance/report.md](../../docs/acceptance/report.md)、[docs/runbooks/t42-synthetic-demo.md](../../docs/runbooks/t42-synthetic-demo.md)、[scripts/acceptance.py](../../scripts/acceptance.py)、[frontend/src/App.tsx](../../frontend/src/App.tsx)
- 迁移：无新增；保留既有基线
- 原型：UI-001
- 回归入口：[scripts/acceptance.py](../../scripts/acceptance.py)、[frontend/dist-tests/contract.spec.ts](../../frontend/dist-tests/contract.spec.ts)
- 后端断言定位：见上述专用检查入口
- 证据：[docs/evidence/t42/checks.txt](../../docs/evidence/t42/checks.txt)、[docs/api/t42-notice-fix.md](../../docs/api/t42-notice-fix.md)
- 限制：本提交完整CI待父会话；无原始招标正文逐条验收，无医院UAT/生产批准。 本次独立修正首页过时说明，完整CI另验。
