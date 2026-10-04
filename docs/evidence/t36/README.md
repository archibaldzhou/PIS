# T36 本地交付证据

同一环境 `/workspace/PIS`，基线为已验证 T35 修复 `67dd0fa86e0d3b44f24a7a1ec9b3c6870ff772d4`。T36 位于独立 validation/t36，未合 main。全部数据为合成夹具；clinical executionAllowed=false，默认模式关闭。

日志仅规范化行末空白，保留测试内容及错误。

## 最终结果

- 181 项前端单测通过（32 文件）。
- 92 项全套真实 Chromium UI 合约通过，零重试；HTTP 为明确合成夹具，不等于真实服务 E2E。
- 收尾 9 项 ROI/结果专项通过；全套重跑包含这些测试。
- typecheck、lint、构建、git diff --check 通过。
- npm 审计包含开发依赖，0 vulnerabilities，无依赖/锁文件变更。
- PG17 V1–V33 SQL 探针通过：结果来源/版本/用途、唯一、并发CAS、回滚及不可变；非完整应用测试。
- 现有阅片提供者有界并发、实际PNG/像素/边界/取消、两个独立JVM一致性检查通过。不是完整Spring进程重启证明。
- Java语法、234文件作用域、27个HTTP helper受检异常源检查通过；**不是Java完整类型编译**。
- 真实服务E2E发现44项成功；新结果链路已加入其中的viewer场景，本地未执行。
- 离线Maven verify在POM解析失败，缺少zipkin-reporter-bom 3.5.3、brave-bom 6.3.1等。完整后端编译/测试、应用全链路及本次完整CI未验证，交父会话读取精确SHA。

## 可复现命令

在仓库根执行（本环境已有固定Node/npm工具及Chromium）：

```sh
export PATH=/tmp/pis-frontend-tools/npm/node_modules/.bin:/tmp/pis-frontend-tools/node-v22.23.3-linux-x64/bin:$PATH
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run build
npm --prefix frontend run audit:dependencies
PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui
PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui -- roi.spec.ts synthetic-result.spec.ts
PIS_TEST_DB_PASSWORD=Synthetic-discovery-only npm --prefix frontend run test:e2e -- --list
python3 backend/src/test/probes/ai-postgres.py
bash backend/src/test/probes/viewer-validation.sh /tmp/pis-t36-provider-recheck
sh /tmp/pis-t08-maven/apache-maven-3.9.16/bin/mvn -o -Dmaven.repo.local=/tmp/pis-t08-maven/repository -f backend/pom.xml -B -ntp verify
```

PG脚本使用既有固定digest的PG17容器，独立临时数据库、无网络，无真实数据。语法/作用域/HTTP helper辅助检查使用当前环境已有 `/tmp/PisSyntaxProbe.java`、`/tmp/PisScopeProbe.java`、`/tmp/PisHttpThrowsProbe.java`，仅辅助源码检查，完整编译仍必须以Maven/CI为准。

`ui-initial-duplicate-key-failure.txt`保留首次全套90通过/1失败：新结果组件与ROI历史同key导致QC撤销后重复面板。已修复独立key命名空间并新增精确数量回归，最终92项通过。

`unit-initial-floating-point-failure.txt`保留新双镜像测试使用深度精确相等时约1e-13的IEEE754舍入差异；新测试明确1e-10像素容差后181项通过，不改变旧测试阈值。没有跳过、重试、睡眠或扩大既有超时来求绿。

两张PNG为真实Chromium截图，已实际打开视觉检查：基础叠加可见4×4强度格和矩形，旋转翻转裁剪图只显示中央规范像素范围。自动化另检查实际PNG样本RGB、矩阵行列式、隐藏前后截图差异、取消和撤销清空。截图不代表真实WSI、模型性能、疾病风险或临床准确性。
