# T23 本地交付记录：细胞学及无需蜡块的制片来源链

2026-10-03；起点 `43c0d3e0c0e772dfe77328beb25149572b8d2f9b`，同一 `/workspace/PIS`、`validation/t15-20261002` 分支。保留 T15–T22 历史，本任务只中文本地提交；不登录 GitHub、不处理凭据、不 push、不查询 CI、不部署。

依据 [开发 PRD](../prd/development-cytology-v1.md)、[ADR 0013](../adr/0013-cytology-preparation-lineage.md) 与根 AGENTS。当前环境读取已授权附件并实际查看 UI-015 细胞学工作站图片；ZIP SHA256 `74f3305da4278f84c7660eb13625c1ff604174116d2fe13fbeffe85cf7a58291`。原型不等于医院规则批准，未声称取得原始招标 PRD 正文。

## 实际实现

- V21 新增 `cytology_grant / cytology_specimen / cytology_preparation / cytology_event / cytology_rejection`。独立标本、制备和玻片 UUID 保留患者、申请、病例、容器关联；复合 FK、不可变身份/历史、单制备唯一蜡块及明确路径触发器阻止串来源。
- DIRECT_SMEAR（人工涂片方法/固定记录）、LIQUID_BASED（人工介质/制备方法记录）、CELL_BLOCK（人工制备/包埋记录）。元数据为路径明确标注的必填人工合成文本，不解释为已验证临床参数。前两类没有蜡块也合法；后一类新建独立细胞蜡块及关联玻片。不虚构取材盒或石蜡任务，不默认阴性或生成诊断。
- 初始库存严格取自容器登记数量，单位 SYN_PORTION；范围 1–99，未登记时不推算库存。预留即扣减；完成要求预留＝消耗＋废弃＋退回，玻片 1–20 且不超过消耗。失败产物数/消耗为零，须完整核对废弃和返还。该保守开发模型不是体积、细胞数或临床产率模型。
- 重复制备必须新 ID，可引用同标本旧制备并记录原因；输出材料使用原 T13 身份、条码及标签体系。单来源最多 100 制备、单申请最多 100 材料。既有材料、取材盒来源、冰冻来源不能直接纳入新库存；已登记台账容器不能再从旧直制、取材盒或冰冻登记入口消费。常规照片读取不受此材料互斥影响。
- 显式 SYN-CYTOLOGY-1 资格分 can_prepare / can_qc，并要求现有 MATERIAL / QC 权限；详情要求范围读取权限和有效细胞学资格。技术制备资格不赋予医生诊断权；T16 及报告接口仍要求原医生资格、已领取分配和材料 QC。管理员无自动资格。合成开关及 dev/test 约束保持默认关闭，迁移不种权限或演示材料。
- 来源 QC 从 PENDING 开始；QC 变化递增独立代次。制备绑定确切来源代次，撤销再通过也不能复活旧制备；失败可记账返还，但不能恢复来源可用性。身份不符不可解除，且传播到同申请其他材料的 QC/标签/诊断就绪。普通来源失效阻断其制备输出消费；旧事件和旧材料身份保留。
- 材料自身仍需原 QC；质量投影/详情显式显示 SOURCE_QUARANTINED。T16 就绪及报告依赖摘要包含来源/制备，来源变化使旧依赖失效；不修改冻结正文、PDF 字节或哈希。
- 申请根锁、库存 CAS、制备 CAS、事务内资格重读；材料、标签、数量历史、成功审计和幂等回执同事务。读审计与授权对象的业务拒绝记录独立保留，拒绝记录只含静态代码和关联身份，不保存请求正文。
- React 从申请详情进入，明确展示申请/患者/病例/容器/标本/制备/玻片及可选蜡块 ID、来源代次和数量。路径、操作、容器、历史切换有脏输入确认；取消保留原输入，确认切换清除隐藏字段。迟到响应丢弃、未知写结果冻结原意图、原键重试。实际检查桌面/390px手机截图，无水平整页溢出。

## API 契约

`GET /api/materials/requests/{request}/cytology/{container}?page=1`：授权、读审计、当前来源/制备/材料及追加历史；20条/页，页号1–10000。

`POST /api/materials/requests/{request}/cytology/{container}/{ACTION}`：CSRF、Idempotency-Key，动作 REGISTER / QC_PASS / QC_FAIL / IDENTITY_MISMATCH / PREPARE / COMPLETE / FAIL。

命令显式绑定 confirmedContainerId、expectedVersion（未登记 -1）、强制 reason。登记/制备需 metadata；制备需 path/transferred、可选 repeatOf；核对需 preparationId/preparationVersion、consumed/discarded/returned/slides。服务器会话决定主体，不能传入 actor、授权或隔离放行标志。回执为 CYTOLOGY_CONTAINER / 容器ID / 新库存版本。每次请求摘要包括所有输入，原键异参拒绝。

字段格式错误 400；对象/资格不可用 404；数量、状态、隔离、陈旧版本 409；合成模式禁用沿用 503。确认重放仅返回旧回执，不能解释为当前来源/QC仍有效。未确认写操作不因关闭页面而撤销。

## 已执行检查与证据

| 检查 | 本地结果及边界 |
| --- | --- |
| `npm --prefix frontend run lint` | 通过 |
| `npm --prefix frontend test` | 最终 107 项、17 文件通过 |
| `npm --prefix frontend run build` | TypeScript strict + Vite 通过；保留约1.21MB chunk提示，未做性能验收 |
| `PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui` | 完整36项通过；收尾改动后细胞学/QC定向5项再次通过；修正蜡块行说明后完成制备1项再测通过。全部是HTTP mock UI，不是真实Spring E2E |
| `npm --prefix frontend run audit:dependencies` | 0 vulnerabilities；仅npm依赖范围 |
| `python3 backend/src/test/probes/cytology-postgres.py` | 实际PG17、固定镜像摘要、network none、合成数据。V1–V21 SQL及旧材料/标签保留；三路径及无蜡块、库存与核对、来源代次、历史不可变、路径拒绝、重复准备、观察到根锁等待后的CAS竞争、审计失败回滚、病例隔离传播及实际报告依赖SQL通过。只测SQL/协议，不是Flyway/Spring/JDBC集成 |
| 独立编译并运行生产 `CytologyPolicy` | 3个有效数量样例及10个拒绝边界通过；不是完整后端编译 |
| Java源码语法、diff、文档链接、祖先检查 | 当前可执行静态检查；不替代Java类型检查/架构JUnit |

PG探针初轮扩展测试样本使用了不合规范条码及重复展示编号，被原T13约束正确拒绝；修正合成样本后全部通过，未放宽条码或唯一约束。临时日志 `/tmp/pis-t23-pg.log`、`/tmp/pis-t23-maven.log`，截图 `frontend/test-results/cytology-{desktop,mobile}.png`；探针已作为测试辅助源码提交，退出时清理自己的容器。

## 新测试源码与未验证项目

新增 CytologyPolicyTest、CytologyMigrationTest（V20→V21/Flyway校验）、RequestWorkflowTest 三路径/重放/旧CAS/数量/失败返还/重复制备/源QC及身份撤销/跨范围及撤权/审计回滚/并发预留与完成/HTTP CSRF及字段白名单/旧入口互斥；迁移最新版本断言更新为21。新增真实 `frontend/e2e/cytology.spec.ts`，使用测试classpath独立合成就诊及资格，覆盖真实本地API、三路径、并发、失败、重放及页面；没有HTTP mock或外部系统调用。

**完整后端编译/完整JUnit、HTTP、Flyway集成测试没有运行通过。** 本次离线 Maven verify 在 POM 解析阶段失败：Boot 4.1.1 导入的 Zipkin Reporter 3.5.3、Brave 6.3.1、gRPC 1.83.1 等 BOM 本地缺失，尚未进入编译。未更改依赖、尝试凭据或绕过既有下载拒绝。

**真实 Spring/PG E2E 未运行，CI 未验证。** 后端构建依赖缺失阻塞真实服务启动和其E2E前置门禁；用户要求不等待且本次不连接GitHub。源码已补齐不等于测试通过。未作完整临床/安全/性能验收，不将局部本地通过称为可合并或可投产。

仅合成人工记录，无真实患者/AI/设备/外发/CA/临床签署/部署。混样、跨容器材料转移、医院计量换算与临床制备参数不在此开发规格内，需要另行批准及验证。
