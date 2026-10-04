# T36 生成 POST 调查、场景隔离与安全诊断

## 证据与未决项

父会话核验 c957376eda6aee9802ffb2ca450a5539d6872aa2 的 CI 37171721398 在生成结果后未见确认 ID；没有生成 POST 的 status/body，因此不把本次失败归因为 30 秒总预算，也不声称已证实服务端返回某个错误码。

代码核查发现一个确定的隔离缺陷：同一 viewer.spec.ts 的两个独立场景仍使用 `synthetic.workflow.viewer`。StorageService 按 user/minute 限制 60 次读取，ViewerService 按 user/minute 限制 240 次，AI 来源资格验证通过多层服务调用 manifest；拆成两个 test 不会隔离这些计数。是否正是原失败原因仍需真实响应确认。

创建契约已逐项核对：POST /synthetic-results 输入 taskId/expectedTaskVersion/reason；前端取已确认任务版本，经 CSRF 获取和原幂等键提交；服务端重新核验 owner auth_version、任务状态、输入模型/QC 依据，再 prepare/finish 不可变存储。成功回执是 resourceType=SYNTHETIC_AI_RESULT、status=200、version=0；READY 实体版本为 1。此次未修改或绕过这些产品检查。

## 修复与回归

- 结果场景移动到 results.spec.ts；共同的真实 HTTP/UI 准备保留在 viewer-fixture.ts。E2eFixtureConfiguration（仅 src/test）创建独立账号和医院/病例范围，同等合成专业资格；角色 404、像素、哈希、版本和撤销断言完整保留。无新增生产账号或凭据。
- 生成 POST 在点击前注册精确 method/path 响应监听，核对提交的确切任务版本、200 回执和确认 ID。没有增加测试/全局超时或自动重试。
- 每次生成在日志及附件输出 `T36_RESULT_CREATE_DIAGNOSTIC`：HTTP status、8KiB 内解析且仅白名单字段的 body 摘要、必要版本事实、已知 UI 错误分类。响应体超限/非 JSON 明确标记。无 cookie、token、请求头、任意 message/detail、资源 ID 或病历正文；仅服务端 UUIDv4 traceId 用于对应安全服务端日志。
- 诊断脱敏、超限、非法类型和账号隔离均有单测。
- UI 受控 429 回归验证：生成未确认不显示 ID；显式原键重试保留完全相同 body/key，不自动重试；只有确切成功回执显示 ID。
- 新后端回归从真实 HTTP 登录开始完成 task submit/claim/run，再生成结果、重放，核对确切绑定、单一结果及单次 READY 审计，并保持无 CSRF 的 403。密码设置在任务创建前，避免制造过期 principal/owner auth_version。此测试本地未运行，需 CI 验证。

## 本地检查及复现

原环境 PATH：

```sh
export PATH=/tmp/pis-frontend-tools/npm/node_modules/.bin:/tmp/pis-frontend-tools/node-v22.23.3-linux-x64/bin:$PATH
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run build
PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui -- synthetic-tasks.spec.ts synthetic-result.spec.ts
PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui
PIS_TEST_DB_PASSWORD=Synthetic-discovery-only npm --prefix frontend run test:e2e -- --list
```

186 单测、typecheck/lint/build、10 项针对性 UI 通过；完整 UI 94 项通过，见 ui-full.txt。真实 E2E 仅列举 45 项/26 文件，不表示执行通过。UI 使用合成 HTTP 拦截及真实 Chromium/OpenSeadragon，不冒充真实后端链路。

Java 语法/词法作用域/HTTP helper 受检异常源扫描通过，仅源检查。Maven 离线 verify 仍因 Boot 4.1.1 引入的 zipkin-reporter-bom 3.5.3、brave-bom 6.3.1 等未缓存失败。本地完整后端及真实服务 E2E 未运行；不尝试被拒绝的下载或 Actions API。

main 不变。父会话需核验此修复 SHA 的完整 CI；若再次失败，优先读取 `T36_RESULT_CREATE_DIAGNOSTIC` 及同 traceId 的 api_error，再判断产品原因，不以本地 mock 成功代替结论。临床 executionAllowed=false、默认关闭及合成非诊断限制保持。
