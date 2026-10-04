# T38 本地交付与验证

## 基线与范围

按父会话确认的T37完整CI成功，核验远端main为edea2ae、validation/t37为dac5649及祖先关系后，在原有main工作树快进推送；远端main确认为 `dac56498dca7044cf49e4c85e44ef3f1bea172d5`。T38在同一环境 `/workspace/PIS` 的validation/t38独立开发，不合main，不查询Actions。

已读AGENTS.md、既有T36/T37版本/权限契约；未发现附加仓库skills目录。先写development-synthetic-impact-v1.md，并打开授权UI-047及UI-046实际PNG。API见docs/api/t38-synthetic-impact.md，架构见ADR0028。

当前状态计算覆盖模型state/head、重扫head、数字QC、来源QC摘要、校准/profile和当前任务版本；当前结果/瓦片/采纳原门禁保留，新增current-reference只允许当前有效、精确当前报告修订上的引用消费。授权历史单独返回失效原因及旧绑定，不提供像素。人工ACKNOWLEDGE/DEFER绑定snapshot hash与CAS，审计/幂等同事务；不能恢复结果、替换原版本、修改报告或签署。旧复核遇新依赖epoch重新待复核。

V35新增空的独立impact head/review表、精确原决定绑定及append-only触发器。显式基线35，保留旧版本升级测试，新增34→35无种子/重复执行/校验契约。5项后端工作流测试含撤销/重启用、QC/重扫、并发/审计回滚、HTTP/CSRF/权限和撤销竞争；扩展现有真实E2E的知悉/重放/引用拒绝及报告不变场景。

## 本地命令与结果

Node22.23.3/npm11.21.0，Chromium `/usr/bin/chromium`，Java21，PG17固定镜像（探针内记录digest）。只用合成夹具。

- frontend `npm test`：203通过（unit.txt）。
- frontend `npm run lint`、`npm run build`：通过，包含类型检查。保留既有大chunk提示，无门禁放宽。
- frontend `PIS_UI_BROWSER_PATH=/usr/bin/chromium npm run test:ui`：108项全通过（ui-full.txt），含全部T36/T37及新T38四场景。
- 后续增加取消写等待/迟到响应回归后，全T38五场景复测全部通过（ui-final.txt）。
- `python3 backend/src/test/probes/ai-postgres.py`：真实PG17 V1–V35迁移、snapshot绑定、排他CAS、回滚、不可变及原记录不变通过。是SQL探针，不是应用HTTP/JUnit。
- `bash backend/src/test/probes/viewer-validation.sh`：合成PNG固定像素、边界、并发、取消及独立provider JVM重启通过，不代表完整应用重启/真实WSI。
- Java syntax、lexical scope、HTTP checked-exception声明探针通过；不是类型编译。
- `PIS_TEST_DB_PASSWORD=synthetic-discovery-only npm run test:e2e -- --list`：发现46项/27文件；只是发现，不宣称执行。
- `screenshots/synthetic-impact-history.png`已实际打开做视觉检查：非诊断/历史不可消费、精确决定与snapshot、原因、已模拟签署但内容原样、人工确认及原草稿历史可见。该截图来自真实Chromium+mock HTTP，不替代真实服务；CI的decisions.spec.ts另生成真实服务截图。

## 修复与未验证边界

UI首轮3通过/1失败：测试getByRole(alert)误匹配多个警告，改为精确已知业务码的错误文案。相应业务错误码加入现有白名单，409保持明确失败而非未知重试；没有放宽断言。初始日志保留。审查时修正首笔影响读取的审计版本使用原决定版本，避免未复核head=-1；不削弱审计校验。取消/迟到新增场景单独复测。

本地离线Maven verify停在BOM解析：缺zipkin-reporter-bom3.5.3、brave-bom6.3.1等（backend-offline.txt）。完整后端编译/JUnit、打包、真实服务E2E、完整应用重启及完整CI未在本地通过；父会话读取精确提交CI。没有重试拒绝的下载或Actions接口。依赖未新增，审计留给既有CI。

服务端每次重查；已交付浏览器像素仍沿用最多5秒核验间隔，不宣称即时远程擦除。历史权限沿用当前合格已领取医生+结果所有者，不自动扩成跨人员共享。没有自动复核/采纳/诊断/更正/签署/重新运行模型。开发默认关闭、executionAllowed=false、无真实患者/实际模型/外发/部署；此交付不是临床可用或投产声明。

日志仅去终端ANSI颜色和行尾空白，保留失败事实与原有检查范围。
