# T42 本地执行与视觉QA

当前证据更新（2026-10-04）：父会话已核验 T42 `5e51683122a51660eff42ef55a6390d79227662f` 的 [完整CI 37198859288](https://github.com/archibaldzhou/PIS/actions/runs/37198859288) 成功，包含首页修复。以下本地执行记录保持原时间事实，“待CI”描述为当时状态；本地BOM缺失不因CI成功变成本地通过。本轮新文档提交的完整CI仍待父会话，不提前合main。

2026-10-04，同一 `/workspace/PIS`，已验证基线 `5be3b823a68dbddb2d8962b53492afe618f82f82`。命令详见[统一指南](../../runbooks/t42-synthetic-demo.md)。精确基线CI由父会话核验，不说成本地执行。本次独立修复首页过时说明：`80204cfe7744464bb32591a1fbd75f9a224f5207`；两文件明确差异登记于[基线记录](../../acceptance/baseline-evidence.json)，其余559个运行时/测试/部署文件摘要相同。新提交完整CI仍待父会话。

| 本次本地命令/范围 | 结果及原始文件 |
| --- | --- |
| `npm --prefix frontend test` | 208项、37文件通过：[unit.txt](unit.txt) |
| `npm --prefix frontend run lint` | 通过：[lint.txt](lint.txt) |
| `npm --prefix frontend run build`（含typecheck） | 通过，保留大chunk提示：[build.txt](build.txt) |
| `PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui` | 117项通过，5.3分钟：[ui.txt](ui.txt)；HTTP mock，不是真实服务 |
| 同浏览器 `npm --prefix frontend run test:dist-ui` | 真正dist、2项通过：[dist-ui.txt](dist-ui.txt)；身份API mock |
| Python deployment unittest discover | 8项真实代理/所属子进程生命周期通过：[deployment-contracts.txt](deployment-contracts.txt) |
| `python3 scripts/synthetic-recovery.py` | PG17、37迁移、145表、2,097,177字节对象、10项拒绝、实际中断恢复回滚通过：[recovery.txt](recovery.txt) |
| `bash backend/src/test/probes/viewer-validation.sh` | 2次128MiB/2线程/32任务、精确RGB/坏格式/取消及独立JVM一致性通过：[provider.txt](provider.txt) |
| `python3 backend/src/test/probes/ai-postgres.py` | 当前迁移及合成SQL并发/CAS/回滚探针通过：[postgres.txt](postgres.txt)；非Spring授权验收 |
| `python3 backend/src/test/probes/technical-boundaries.py` | 源码引用无环通过：[boundaries.txt](boundaries.txt)；非字节码/完整编译 |
| Maven offline verify | 退出1，在模型解析缺Zipkin/Brave等BOM，未进入编译：[backend-offline.txt](backend-offline.txt) |
| Playwright真实服务/真实dist `--list` | 47项/28文件、1项/1文件，仅发现：[e2e-discovery.txt](e2e-discovery.txt)、[dist-discovery.txt](dist-discovery.txt) |
| YAML与CI保留比较 | 通过，所有旧jobs/命令/超时/权限/Actions不变：[workflow.txt](workflow.txt) |
| `python3 scripts/acceptance.py` | 显式42项/37迁移/47原型/559未变文件及2项修复差异、路径/断言定位/证据/链接通过：[checks.txt](checks.txt) |

所有上述业务检查先在未改运行时基线执行。截图发现过时首页文案后，独立修复重新执行208单测、lint、typecheck/build和2项真实dist/mock身份浏览器断言；[修复后证据](../t42-notice-fix/)。没有因文案修改再次执行不相关PG/整套117 UI，未称这些旧结果验证了新功能。最终完整CI将执行全部必检项。

## 视觉QA（实际打开PNG，不是仅DOM断言）

- [dist登录](dist-login.png)：实际Chromium构建页面，输入框/登录按钮清晰；过时文案已替换，合成、无临床AI及不可诊疗/生产限制明确。[修复前后](../../api/t42-notice-fix.md)有独立证据。
- [dist错误](dist-error.png)：实际502显式“连接失败”，保留重试和工作区入口；没有虚构连接成功。页面仍是工程入口，不宣称原型整体视觉还原。
- [受限运维](operations-ui.png)：未知容量不是0或正常；未配置加密/异地、未验证恢复及失败/死信提示明确。该图为mock UI输入，不能当真实实例容量。
- [本地接口](adapter-ui.png)：实际本地合成页面，状态/来源/确认字段；不是医院联通证明。
- 本次另打开已有精确基线图：[真实合成叠加](../t36/synthetic-overlay.png)、[1024宽双视图](../t33/t33-dual-1024.png)：真实几何图、导航器和叠加可见，非临床/未知校准提示保留。复用既有像素验证，未重新声称真实WSI/色彩校准通过。
- 本次查看授权原型 UI-001 实际PNG，并核对全部47张PNG与原附件manifest摘要；原型示例待办数、患者样例、阈值不作为运行数据。UI-029/030独立页面未实现，其他映射也不是全功能或像素完成率。

本地独立探针和mock UI不替代真实Spring。后端编译/完整测试、真实服务E2E和正式JAR演练本次本地未运行；基线CI通过来源及新CI待验证范围见[报告](../../acceptance/report.md)。无真实部署、患者、模型、CA、外呼、新凭据或安全配置修改。

索引初检曾正确拒绝尚未生成的dist证据文件；另识别Windows Maven wrapper按`.gitattributes`检出的CRLF差异，检查仅对该明确路径规范为git blob的LF，其余字节不归一化。没有把证据缺失或任意代码差异动态接受为通过。
