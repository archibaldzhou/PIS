# 中文运行与合成开发演示指南（T42）

适用代码基线：`5be3b823a68dbddb2d8962b53492afe618f82f82` 及增加验收文档/检查、修正首页过时说明的 T42。当前已验证 T42 为 `5e51683122a51660eff42ef55a6390d79227662f`（[完整CI成功](https://github.com/archibaldzhou/PIS/actions/runs/37198859288)，父会话核验）；本轮文档收尾的新SHA尚待CI。完整结论和所有42项入口见 [验收报告](../acceptance/report.md) 与 [矩阵](../acceptance/matrix.md)。**仅隔离本地/CI，不是临床系统、医院 UAT 或生产部署指南。** 不输入真实患者/条码/报告，不连接真实模型、扫描仪、打印机或医院接口。

## 1. 前置条件与安全启动

- Linux 非root用户、JDK21（含javac）、Python3标准库、Docker及Compose v2；Node `22.23.3`、npm `11.21.0`。后端 Maven `3.9.16` 由 `backend/mvnw` 固定摘要下载。首次安装需允许访问官方 Maven/npm 和固定镜像来源；访问拒绝就停止，不换凭据绕过。
- 专用可丢弃 PostgreSQL17，数据库名以 `_test` 结尾；用既有本地测试配置注入 `PIS_TEST_DB_URL`、`PIS_TEST_DB_USERNAME`、`PIS_TEST_DB_PASSWORD`。不要把值提交、截图、回显或发到聊天。本指南不创建新凭据，不覆盖 `.env`。
- 若环境已有配置好的测试库，直接使用。仓库 Compose 的测试库映射 `127.0.0.1:5433`，CI用5432；URL端口必须对应实际服务。运行既有 Compose 前需其 `.env` 已正确配置开发及测试所需变量，不能为解决插值错误填入生产密码。
- 8080/5173（真实E2E）、5174（mock UI）、5175（真实dist）、5176（dist mock UI）需可用。冲突时停止自己启动的服务；不要杀任意占端口进程或开启 `reuseExistingServer`。
- 正常应用 `PIS_WORKFLOW_DEV_ENABLED` 和 `PIS_AI_SYNTHETIC_WORKER_ENABLED` 均默认false，普通登录不会自动获得病例/专业权限。临床 `executionAllowed=false` 永远不由演示开关改变。

如需启动已配置的 Compose 测试服务，在仓库根目录：

```bash
docker compose --profile test up -d --wait postgres-test
```

不执行 `down -v`，不删除任何开发卷、用户文件或已有数据。测试自身创建随机schema和临时对象，不能指向生产库。只运行已有fixture，不手工SQL授予管理员临床权。

## 2. 完整检查命令（按顺序，任一步失败即停止调查）

以下命令均从仓库根目录开始，已注入测试库变量。实际执行结果以终端/CI为准；这份命令清单本身不证明通过。

```bash
(cd backend && ./mvnw -B -ntp verify)
npm --prefix frontend ci --no-audit --no-fund
npm --prefix frontend test
npm --prefix frontend run lint
npm --prefix frontend run build
npm --prefix frontend run audit:dependencies
(cd frontend && npx playwright install chromium)
bash backend/src/test/probes/viewer-validation.sh
python3 scripts/synthetic-recovery.py
python3 -m unittest discover -s scripts/deployment -p 'test_*.py' -v
python3 scripts/deployment/rehearse.py --jar backend/target/pis-backend-0.0.1-SNAPSHOT.jar --dist frontend/dist --revision "$(git rev-parse HEAD)"
npm --prefix frontend run test:e2e
npm --prefix frontend run test:dist
npm --prefix frontend run test:dist-ui
npm --prefix frontend run test:ui
python3 scripts/acceptance.py
```

CI为上述必检入口提供真实PG；verify总预算30分钟，部署演练9分钟、恢复3分钟、dist真实4分钟及dist合成2分钟均保持有界。发布清单校验仅兼容schema37，不自动接受未来迁移。T42索引检查遇未来运行时变更会失败，必须重新评估证据，不能动态更新摘要来冒充测试通过。

本执行环境 Maven 离线缺失 BOM，尚不能本地重跑后端/真实服务/正式JAR；最新精确基线完整 CI 已由父会话核验。不要把单独 provider/PG SQL 脚本或以下mock UI当作代替。部署脚本会再次调用真实合成备份恢复门禁，重复是安全前置条件，不可跳过。

## 3. 推荐的可见演示：使用真实服务的现有隔离场景

后端 verify 成功、前端依赖就绪后，在有显示环境的机器运行下列命令；无显示的CI去掉 `--headed`。Playwright自行启动一次性 Spring 测试入口和浏览器，不需手工选择账号或填写测试UUID。测试种子为每文件独立合成账号，浏览器上下文独立，保留登录限流和CSRF，不把认证重试当作演示步骤。

```bash
npm --prefix frontend run test:e2e -- --headed accession.spec.ts reception.spec.ts grossing.spec.ts technical.spec.ts materials.spec.ts quality.spec.ts diagnosis.spec.ts
npm --prefix frontend run test:e2e -- --headed report.spec.ts review.spec.ts output.spec.ts amendment.spec.ts archive.spec.ts
npm --prefix frontend run test:e2e -- --headed storage.spec.ts scan.spec.ts digitalqc.spec.ts viewer.spec.ts results.spec.ts decisions.spec.ts
npm --prefix frontend run test:e2e -- --headed delivery.spec.ts adapters.spec.ts
```

| 链路 | 用户应看到/核对的事实 | 安全边界 |
| --- | --- | --- |
| 登记/接收 | 合成就诊、申请/容器UUID，修改和提交后刷新仍存在；身份错配阻断、异常/退回留历史 | 不新建真实患者，不默认接收 |
| 取材/技术/QC | 盒来源、不同账号交接、新玻片身份、独立QC与返工 | “合成完成”不是机器完成，旧片不覆盖 |
| 分配/报告 | 合格人员领取、草稿/模板版本、复核依赖、明确模拟签署确认 | 未保存内容切动作须确认；管理员无临床权 |
| PDF/更正/归档 | 同一固定PDF及摘要、每页非临床、新版本链；扫码身份/部分归还与盘点差异 | 不向物理打印机发送，不把人工登记视作已实际归还 |
| 存储/阅片/ROI | 精确原件/扫描版本，真实RGB瓦片和导航图，规范像素ROI，QC撤销后资源拒绝 | 普通合成图不是病理WSI；未知校准不显示虚构微米 |
| 合成任务/结果/人工复核 | 技术任务代次、真正合成强度叠加、显隐/透明度、人工理由及目标草稿绑定、失效复核 | 强度不是风险；不运行模型、不自动改诊断或签署 |
| 接口/运维 | 本地消息、独立ACK与对账、失败原因；受限容量/审计/未知恢复状态 | HTTP200不是送达，无外呼、真实支付或CA |

冻结、细胞学、染色和会诊可分别用 `frozen.spec.ts`、`cytology.spec.ts`、`staining.spec.ts`、`consultation.spec.ts` 运行；工作列表和统计用 `worklist.spec.ts`、`statistics.spec.ts`。这些文件均在全套test:e2e中，没有为演示跳过失败测试。

需要自由交互时，可沿用 [原工作流runbook](synthetic-workflow.md) 的 `SecurityE2eApplication` 测试classpath入口；其自动启用test合成开关并创建自身schema，正式JAR不含此入口。当前业务账号带文件后缀，例如 `synthetic.workflow.accession`，不可再使用早期无后缀说明假定权限。自动化演示会使用代码中公开的隔离fixture值或已配置的 `PIS_E2E_WORKFLOW_*` 覆盖；不要借此建立持续生产访问。

## 4. 真正构建产物演示与退出

`npm run build` 成功生成 dist 后：

```bash
npm --prefix frontend run test:dist -- --headed
npm --prefix frontend run test:dist-ui -- --headed
```

第一项使用真实测试Spring服务，验证深链接、刷新、HttpOnly/Lax Cookie、缺少CSRF拒绝、未知API不是HTML成功及退出；第二项为真正dist界面的mock身份/网络故障展示。两者不可混称。截图来自实际浏览器，不是原型PNG伪装运行页面。

手工代理命令（后端已由你在本机启动）：

```bash
python3 scripts/deployment/local_proxy.py --dist frontend/dist --port 5175 --upstream-port 8080
```

只监听127.0.0.1，Ctrl-C清理本次代理连接并等待线程退出。自动套件等待自己创建的进程退出，不杀其他进程。实际release/正式JAR演练完整命令在上节；[T41运行手册](t41-local-release.md)详细列出清单、私有临时路径和兼容门禁。**sameBuiltArtifactTwoInstances=true，TLS=NOT_CONFIGURED，productionReady=false**；不等同不同历史二进制升级，也不回退数据库迁移。

## 5. 异常处理和证据入口

- 401重新认证；403核对CSRF及真实权限，不关闭过滤器；404可能是不可见资源，不枚举病例；409刷新确切版本重审，不能覆盖旧稿；429遵循Retry-After，不盲重试；未知写结果保留原幂等key/body，取消等待不代表服务端撤销。
- 模型/QC/身份撤销后，历史仍可按授权追溯，但当前结果、瓦片和引用不可继续消费。不得修改数据库状态为READY来“修复演示”。
- 本地全套原始输出与截图 QA：[T42证据](../evidence/t42/README.md)；完整CI来源和差异：[验收报告](../acceptance/report.md)。所有阶段限制和未配置外部能力在报告中集中列出。
- 在本环境遇到 Maven BOM缺失，保留错误并交由既有CI核验；不更改凭据、不查询被拒的Actions接口。任何新代码改动需重新完整验证，不能沿用旧SHA成功替代。
