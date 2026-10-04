# T36 配额事务及早期 HTTP 回归

## 证据与判断边界

父会话提供 ddb6419 / CI 37176249592：后端、前端、provider通过；真实E2E资格GET=500，ROI面板未加载。未取得该次服务端异常类；不能把只读错误或连接池耗尽当成已经从CI异常栈证实的事实。

源码核验 AiRegistryService.scan、RoiService.view 使用默认可写 TransactionTemplate；DigitalQcService 在其中锁病例并追加审计。旧HTTP配额挂起该事务并申请第二连接（REQUIRES_NEW）；默认池5个、连接等待5秒。若其余连接正等待病例锁，持锁线程反而无连接可借。这是此次引入的可确定连接依赖缺陷。修复消除第二连接依赖，不提高池容量，不串行化套件、不睡眠或重试。

配额现在加入业务事务，以完成回调补偿请求内标记。失败不留部分计数或审计；早先已提交的频次保持。240频次、32MiB、429/Retry-After、逐次授权/QC/模型校验不变，clinical executionAllowed=false。详见ADR0026。

## 新回归及诊断

- 真实 Spring/HTTP：认证后占用4/5连接，资格及ROI GET必须200、分别1计数；精确版本错误409，撤权404。旧嵌套事务需要第2连接，不能通过此场景。
- 业务回滚计数与审计一起回滚，再读必须补计；同一请求QC撤销不能复用授权。
- 只读事务失败关闭，配额与审计不发生提交。
- 独立计量：回滚撤销标记，已提交频次不被后来字节回滚撤销。
- 统一500日志增加最多4层异常类型、每层最多6个来源栈帧，不输出message、SQL、参数或suppressed。测试启动器只转发这一精确安全日志模板到stderr；不转发任意服务日志。
- E2E资格和ROI请求输出安全status/code/traceId，保留精确200、像素、重显、撤权和清理断言。

## 执行命令与边界

原环境 `/workspace/PIS`，原Node/npm/JDK/本地PG17镜像，无新依赖或环境。

```
bash backend/src/test/probes/viewer-request-meter.sh
python3 backend/src/test/probes/ai-postgres.py
java /tmp/PisSyntaxProbe.java
java /tmp/PisScopeProbe.java
java /tmp/PisHttpThrowsProbe.java
npm --prefix frontend test
npm --prefix frontend run lint
npm --prefix frontend run build
PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui -- results.spec.ts viewer.spec.ts
PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui -- synthetic-result.spec.ts roi.spec.ts viewer-validation.spec.ts
PIS_TEST_DB_PASSWORD=Synthetic-discovery-only npm --prefix frontend run test:e2e -- --list
sh /tmp/pis-t08-maven/apache-maven-3.9.16/bin/mvn -o -Dmaven.repo.local=/tmp/pis-t08-maven/repository -f backend/pom.xml -B -ntp verify
```

独立Java实际计量/脱敏、PG17迁移/并发/配额、语法/作用域检查通过。187前端单测、lint、typecheck/build通过。22项相关UI通过（阅片5项，结果/ROI/验证17项）。UI为真实浏览器+mock HTTP，不是Spring真实服务。

Maven离线缺少Spring Boot4.1.1导入BOM（包括zipkin/brave等），未进入完整编译及测试；未下载或绕过此前拒绝。新增真实HTTP、只读/回滚集成、真实服务E2E及完整CI未在本地运行。E2E --list仅发现45项，不证明运行通过。此次具体500异常链仍须父会话/下一轮CI安全诊断确认。
