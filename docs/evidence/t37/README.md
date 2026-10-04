# T37 本地交付记录 — 2026-10-04

## 基线与恢复

原环境 `/workspace/PIS`，任务分支 `validation/t37`。T36 独立修复 `edea2ae907e0ff9f426c3f111b4a549e3b631bf7` 已按父会话完整 CI 成功授权快进 main，普通推送后远端 SHA 一致；main CI 由父会话核查。

恢复前核验 stash `cdac462843af0aa47dc4e9ed0270377e72e3bf16` 的30个文件 SHA-256，原清单保存在 protected-file-hashes.json。恢复后29文件逐字节一致；唯一合并冲突为 CI 分支列表，保留 T36-main-fix 并新增 T37。保存 stash 未删除。后续修改均为本任务收尾，不改写历史。

## 实现与审查

PRD：docs/prd/development-synthetic-decision-v1.md；ADR 0027；API：docs/api/t37-synthetic-decisions.md。V34新增空表与不可变触发器，无生产账号/病例种子。新测试固定V33单步升级并新增V33→V34/重复执行/无种子契约，空库显式版本序列1–34。

医生明确采纳非诊断引用、拒绝、暂缓；完整来源、理由、actor/time、确切目标草稿。保留当前领取/专业资格/医院病例/结果所有者/QC门禁；当前模拟签署草稿不可改变；CAS、幂等与审计同事务。采纳只生成字段与模板不变的新修订，不自动生成诊断或签署。撤销来源拒绝新命令和重放，历史仍按授权查询。

## 实际验证

命令均在本仓库或 frontend 子目录执行。Node 22.23.3 / npm11.21.0，Chromium `/usr/bin/chromium`；Java21；固定 PG17 镜像参见探针源。

- `npm test`：196通过，unit.txt。首轮夹具隔离显式列表遗漏decisions，已扩充三场景隔离断言并保留原登录/IP上限；初始失败记录另存。
- `npm run lint`、`npm run build`：最终通过，含TypeScript；初始E2E请求头类型推导失败已修复并保留日志。Vite现有大chunk警告仍存在。
- `PIS_UI_BROWSER_PATH=/usr/bin/chromium npm run test:ui`：全套104项，首轮103通过/1失败。新增测试错误读取combobox内部文本，已改为明确选项aria-selected断言；随后全部T37的5项通过（ui-final.txt）。未宣称修正后再次完整跑104项。
- `python3 backend/src/test/probes/ai-postgres.py`：PG17真实V1–V34 SQL、CAS竞争、审计式失败回滚、来源hash/字段绑定、不可变记录通过。初始触发器SQL别名old冲突修复为previous，保留初始失败日志。探针最后的V1–V33行描述前序契约，单列V34 PASS描述新契约。
- `bash backend/src/test/probes/viewer-validation.sh`：真实合成PNG、像素、并发、取消、独立provider进程重启通过；不是完整应用重启。
- Java syntax/scope/HTTP checked-exception探针通过；仅源检查，不是类型编译或JUnit。
- `PIS_TEST_DB_PASSWORD=synthetic-discovery-only npm run test:e2e -- --list`：发现46测试/27文件，只是发现，不连接数据库。初始未设测试配置时的拒绝保留在initial-config日志。
- 浏览器实际截图 `screenshots/synthetic-human-reference.png` 已打开检查：非诊断提示、精确来源快照、动作/理由、确认按钮、原报告字段可见。截图使用显式mock HTTP的合成UI，不能代表真实后端成功；真实服务截图由新增decisions.spec.ts在CI运行时生成。

## 尚未验证

离线Maven verify 在BOM解析阶段失败：缺 zipkin-reporter-bom 3.5.3、brave-bom6.3.1 等（backend-offline.txt）。没有绕过下载/认证拒绝。因此完整后端编译/JUnit、打包、真实服务E2E及完整应用重启本地未运行。已补7项后端/迁移测试和1项真实服务E2E，需父会话读取此次精确SHA完整CI。

依赖锁未改变，依赖审计交由既有CI；没有查询已拒绝的Actions API。T37未合main。开发默认关闭、clinical executionAllowed=false，不含真实患者、真实模型、外部推理、临床签名或部署，不表示可合并/临床可用/投产。

记录仅清理终端ANSI颜色及行尾空白，不删减失败事实。
