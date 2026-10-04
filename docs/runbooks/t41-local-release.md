# T41 本地构建产物与回滚演练

仅Linux非root回环合成开发/CI。TLS、域名、外部服务、生产流量切换都未配置。不修改宿主网络、账户或安全配置，不生成凭据，不提供医院生产部署入口。

## 构建与清单

沿用仓库固定Maven/npm与lock：`cd backend && ./mvnw -B -ntp verify`、`cd frontend && npm ci --no-audit --no-fund && npm run build`。先完成既有检查。前端 dist 必须已存在，不运行Vite。

在仓库根目录创建自身临时目录，例如 `mktemp -d /tmp/pis-release-manual-XXXXXXXX`，只能在这个0700、当前用户拥有、直属系统临时目录的路径内新建release：

```
python3 scripts/deployment/release.py --jar backend/target/pis-backend-0.0.1-SNAPSHOT.jar --dist frontend/dist --output /tmp/pis-release-manual-XXXXXXXX/release --revision <当前完整提交SHA>
```

脚本拒绝已有输出、链接、危险目标、不完整dist/JAR、测试fixture混入、迁移摘要不符。`release.json` 与 `release.sha256` 包含真实JAR/dist digest、源码revision、lock摘要、版本和显式[37,37]迁移兼容区间。时间不进入清单，相同输入生成相同清单；本地已实测dist重复构建字节一致。没有宣称跨JDK/平台的JAR逐字节构建可重现。

## 实际构建前端代理

```
python3 scripts/deployment/local_proxy.py --dist frontend/dist --port 5175 --upstream-port 8080
```

只能127.0.0.1，不能传外部上游URL。服务实际dist文件，文件逐次哈希检查；未知静态资源404，扩展名为空的SPA深链接返回index。`/api`只反向代理，不回退HTML，不跟随重定向。Cookie/CSRF/Idempotency/Range保留；头白名单，no-store，日志不记录URL/正文/秘密。8个连接、请求/响应各64MiB、I/O15秒、连接绝对20秒；饱和503和Retry-After，故障502。它是演练代理，不是经过互联网安全验证的生产网关。

## 打包应用启动/重启/回滚

配置沿用已有 `PIS_TEST_DB_URL`（必须127.0.0.1、库名以_test结尾）、`PIS_TEST_DB_USERNAME`、`PIS_TEST_DB_PASSWORD`，不打印其值、不创建账号。运行：

```
python3 scripts/deployment/rehearse.py --jar backend/target/pis-backend-0.0.1-SNAPSHOT.jar --dist frontend/dist --revision <当前完整提交SHA>
```

先执行T40真实非空PG/对象恢复门禁。随后创建自身随机schema、0700临时路径；配置通过子进程环境及非秘密参数外置。真实java -jar启动，合成工作流/worker/账号初始化保持关闭。readiness通过且实际数据库迁移版本兼容才切换；失败候选不切换。实际停止/重启候选，验证断开为502，然后切回先前已验证实例。比较迁移checksum序列与身份/审计/对象计数；不降级迁移、不覆盖数据库。只终止自身子进程，临时schema保留在一次性_test库，不DROP已有数据。

本演练是同一实际构建的两个已校验副本/实例，不冒充不同历史应用二进制的升级兼容测试。[37,37]以外明确拒绝；不同历史制品、真实业务在线流量/迁移兼容、非空业务应用重启仍需后续环境验收。非空对象一致性由T40独立真实恢复门禁覆盖，不伪称空启动schema包含病例。

## 必检边界

- `python3 -m unittest discover -s scripts/deployment -p 'test_*.py' -v`：清单与代理真实HTTP契约，JAR为不可执行测试zip，**不是Spring运行**。
- `npm run test:dist-ui`：真正dist+浏览器，身份API显式合成fixture；不替代真实服务。
- `npm run test:dist`：同一dist代理连接原有test-classpath一次性Spring身份fixture，测登录/cookie/CSRF/深链接/刷新/拒绝/退出。该fixture不进入正式JAR。
- `rehearse.py`：真正正式JAR冷启动/readiness/重启/失败候选/回切，CI必检。本地若缺BOM必须明确未运行。

CI保留原有全部门禁和verify30分钟预算，新增步骤分别有界；失败日志只输出阶段/退出码/异常类名，私有JVM日志、DB配置和Cookie不作为制品。不是production ready、临床、TLS或灾备批准。
