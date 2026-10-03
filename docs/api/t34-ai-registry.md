# T34 模型注册与合成适用资格

T33经父会话核验完整CI成功后，已正常快进并推送main：`0f78e80a20b8400e5068b0229031ae75f1dee430`，远端引用已核验，main新CI由父会话核验。T34只在validation/t34，未通过完整CI前不合main。

## 实现与接口

见[开发PRD](../prd/development-ai-registry-v1.md)、[ADR0023](../adr/0023-synthetic-ai-registry-contract.md)。已查看授权UI-043实际图片；当前界面提供范围内模型版本、缺失证据、状态追加历史、手工合成资料和当前判定。原型中的临床批准开关不实现。

- `/api/requests/ai/scopes/{scope}/models` GET分页（1–50，每页20），POST不可变模型注册，`expectedHead=-1`为新系列；后续更新使用同series ID及当前head创建新版本。
- `.../models/{version}/state` POST CAS状态；`.../history` GET最多100条追加历史。状态修订和模型序号均0–99。
- `/api/requests/{request}/scans/{scan}/ai?publicationVersion=n` GET当前授权扫描依据。
- `.../ai/profile` POST人工合成病理类型、染色、扫描仪软件版本；允许UNKNOWN，要求manifestHash/publicationVersion/expectedVersion及原因，追加不覆盖。
- `.../ai/assess` POST准确modelVersionId/stateVersion/profileVersion/publicationVersion/manifestHash/calibrationVersion及原因；保存确定性快照，回执不是执行许可。
- `.../ai/assess/{id}` GET仅返回仍然符合当前依赖的判定；依赖变化409。扫描/对象/病例不存在或越权沿用资源门禁，模型/资格越权404 AI_NOT_FOUND；未知schema/临床状态400；CAS/旧依据409 AI_CONFLICT；缺资料409 AI_PROFILE_REQUIRED。

所有POST均CSRF、当前身份、Idempotency-Key、严格DTO及人工原因，事务内审计，原键异参拒绝。独立注册/验证/适用grant必须存在、当前有效且叠加医院/组织/病例/存储/扫描/QC权限；管理员无默认资格。查询事务10秒，命令沿用T07 3秒锁等待/5秒statement限制。没有CSV、外发、模型安装、推理任务或临床批准。

结果只有VALIDATION_ONLY_APPLICABLE、NEEDS_REVIEW、NOT_APPLICABLE，executionAllowed永远false。快照绑定模型digest、系列头/状态、profile、原件ID/hash、scanVersion、manifest、publication及校准版本；改变任何依赖必须重新判定。MISSING证据/未知元数据不通过。临床验证、真实模型二进制、性能指标、真实厂商输入与监管审查均缺失。

## 验证和交付

本地原始结果与截图存放于`docs/evidence/t34/`。后端新增策略、权限分离/撤销、跨范围、版本CAS并发、资格撤销竞态、校准/模型换版、QC撤销、原键重试、不可变性、审计回滚和HTTP CSRF/DTO回归。V31空库/从V30升级契约明确为31，不动态接受任意迁移。真实E2E在既有viewer完整来源链后注册合成模型、profile、判定、UI显示、停用、原键失效、QC撤销及越权；测试种子仅在testfixture，不改变运行环境默认开关。

完整后端编译/测试及真实服务E2E本地未运行：现有离线Maven仓库缺Boot4.1.1导入BOM（包括zipkin3.5.3、brave6.3.1等），在POM解析阶段失败。未再次下载或绕过访问拒绝。Java语法/词法探针不等于类型编译；PG17独立探针不等于服务集成；UI合成HTTP夹具不等于真实E2E。完整CI待父会话按最终SHA核验。

本地最终结果（2026-10-03）：173项前端单测、lint、TypeScript/build、84项全套UI及收尾5项AI复测通过；PG17 V1–V31并发/回滚探针、Java语法/词法/受检异常声明检查通过。真实E2E仅发现44项，未执行；既有提供者独立JVM/固定像素/有界并发探针通过，不代表完整服务重启。截图已实际查看，中文可读，缺失证据、禁止执行和人工操作清楚显示。

首次全套UI的82通过/1失败已留原始结果；发现的跨阅片实例队列容量缺口已单独修复，原8请求上限不变，见[根因与修复](t34-viewer-regression.md)。最终全套84通过（新增5项AI UI），不把失败记录抹去。现有构建仍有大chunk体积提示，未降低警告阈值。

复现：从仓库根执行`python3 backend/src/test/probes/ai-postgres.py`、`bash backend/src/test/probes/viewer-validation.sh`；前端`npm --prefix frontend test`、`npm --prefix frontend run lint`、`npm --prefix frontend run build`、`PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui`。完整环境执行`backend/mvnw -f backend/pom.xml -B -ntp verify`及`npm --prefix frontend run test:e2e`。本地版本：Node22.23.3/npm11.21.0、Java21.0.12.1、PG17固定digest；合成开发默认false。

阅片并发修复提交：`a4f0f63e135fcac5b41e9809c203e7cc7866c8e2`。记录中的文本日志仅规范化行尾空白，未删除失败信息或修改结果。

## T34 CI重放断言修复

父会话核验`943a693a56515a3f7b2e62caa4a1a171c782ee0c`的CI 37160350916：后端/前端阶段通过，真实E2E 43/44通过；失败读取了AI接口未承诺的`Idempotency-Replayed`响应头。T07要求`Result.replayed`，HTTP响应头是可选项。AI、扫描、数字QC控制器直接返回Result；申请/存储部分接口额外包装响应头。本次不修改控制器、不补造响应头或改变幂等业务实现。

原失败日志未给出重复请求HTTP状态/正文，本地完整服务也无法运行，因此不声称已重现那次具体响应。修复后的E2E首先检查实际HTTP状态（失败时附合成响应正文），再核对响应体`replayed:true`和完整原回执；独立GET核对判定内容及SHA-256相同，执行许可仍为false。增加同键不同原因必须409 IDEMPOTENCY_KEY_REUSED的断言。原有模型停用、QC撤销、资源权限和UI交互断言保留。

新增后端真实HTTP/PG回归覆盖：原回执ID/版本一致、判定内容/hash一致、存储snapshot不变、业务记录/幂等命令/AI_ASSESS_V1成功写审计各一条；有效CSRF下旧auth_version会话重放401 SESSION_EXPIRED，新会话重新认证后同键仍返回原资源；AI资格和workflow范围撤销后重放404。读取/授权审计允许正常追加，不把全部审计总数错误限定为一条。

本次本地通过：173单测、lint、TypeScript/build、13项AI/阅片UI、PG17 V1–V31独立探针、Java语法/词法/受检异常声明检查；真实E2E仅发现44项。新增后端HTTP/PG集成和真实服务E2E未本地执行，离线Maven仍缺Zipkin3.5.3、Brave6.3.1等BOM，在编译前失败。PG探针与UI合成HTTP夹具不是该HTTP回归通过的证据。原始检查输出（仅规范化行尾空白）见`docs/evidence/t34/replay-fix/`；完整CI由父会话按新SHA核验。T34不合main，不启动T35。
