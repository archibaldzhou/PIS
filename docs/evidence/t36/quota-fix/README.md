# T36 真实重显 429 根因与计量修复

## 已证实路径

父会话提供 CI 37174679421（365af9e）：生成和首次像素通过；重显 metadata=200、PNG=429、elapsedMs=31598，UI 明确 VIEWER_RATE。不是未知图层或总超时。

对应计数表为 viewer_read_budget，非 storage_read_budget。AiResultService.tile 两次校验 current，每次 source 内 tasks.detail 引出3次 manifest，tasks.artifact 引出4次，共14次；metadata为7次。每次内部 manifest 都消耗同一用户/分钟的一个阅片频次单位，与底图/导航器/轮询的真实请求叠加。

## 实现及回归

[ADR 0026](../../../adr/0026-viewer-http-quota-accounting.md) 记录请求内计量、短事务和授权边界。没有提高240次或32MiB限额，没有缓存授权决定，没有放宽QC/模型/身份门禁。请求属性只能由服务器创建，失败扣减不留已计量标记；字节仍逐次计量。非HTTP服务路径仍保留原逐调用计量。

新增 ViewerRequestMeterTest 覆盖重复依赖、实际字节、跨分钟、失败计量、并发、独立请求/用户。真实 HTTP 回归核对生成扣1、每轮metadata+PNG扣2、原字节不变、头部计数、240处429/Retry-After及伪造请求头无效；事务回归验证业务审计回滚、资源计数保留以及同一已计量请求内QC撤销仍阻断。后两者本地未运行，需完整 CI。

E2E 的生成和重显诊断追加本请求内部检查数、实际请求单位和有界 Retry-After；仅安全数字，不输出凭据或病例。保留初次/重显PNG哈希和颜色、401/404/409、撤销清理、取消与迟到响应断言。

## 本地命令与边界

```sh
bash backend/src/test/probes/viewer-request-meter.sh
python3 backend/src/test/probes/ai-postgres.py
# 原环境固定 Node/npm PATH
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run build
PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui
PIS_TEST_DB_PASSWORD=Synthetic-discovery-only npm --prefix frontend run test:e2e -- --list
```

实际 Java 计量器探针通过：14检查→1单位、所有字节、跨分钟、失败后重计、16并发；PG17 V1–V33通过，239→240并发只准一个，32MiB后多一字节被拒。探针只证明计量器/SQL，不是 Spring、实际HTTP或应用重启验证。

187项前端单测、typecheck/lint/build通过；完整UI 98项通过，见 ui-full.txt。Java语法/作用域/受检异常源检查通过，不是完整编译。E2E仅列举45项，不是执行通过。

本地 Maven verify 仍因 Boot 4.1.1 BOM（zipkin-reporter 3.5.3、brave 6.3.1等）未缓存而未进入编译/完整测试；真实服务 E2E 未运行。首次新探针遇到本地缺少 javac 启动器及 SQL 字符串引号错误，已改用同一 JDK 的 compiler Main 并修正探针后复测通过，无产品门禁变更。

main 保持不动；本修复完整 CI 由父会话核验，通过前不合并。仅合成、默认关闭、executionAllowed=false，无临床效力。
