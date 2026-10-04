# PIS · 合成开发系统

当前统一入口：[T42验收报告](docs/acceptance/report.md)、[42项要求/实现/测试矩阵](docs/acceptance/matrix.md)、[中文运行与演示指南](docs/runbooks/t42-synthetic-demo.md)。T41精确基线 `5be3b823a68dbddb2d8962b53492afe618f82f82` 的完整CI已由父会话核验成功；T42自身CI另验。以下分任务“本地未验证/待CI”保留其历史时点，不代表当前基线仍未验证。仅合成开发，不是临床/生产批准。

开发前请阅读根级 [AGENTS.md](AGENTS.md)、[工程基线 ADR](docs/adr/0001-engineering-baseline.md)、[真实门禁与缺口](docs/engineering-gates.md)及[需求来源登记](docs/prd/README.md)。这些文件落实 T01 工程约束，不代表临床批准或全部未来功能已实现。

T08 申请、T09 接收/异常与 T10 标签/重打开发工作流已通过验证分支完整 CI，见[T08 记录](docs/api/t08-development-status.md)、[T09 记录](docs/api/t09-development-status.md)、[T10 记录](docs/api/t10-development-status.md)及[开发运行说明](docs/runbooks/synthetic-workflow.md)。功能默认关闭且仅合成数据 dev/test 可启用；main 整理提交 CI 另行核验。不得在临床环境启用。

T11 取材描述、受控合成图像与取材盒已通过验证分支完整 CI；见[T11 记录](docs/api/t11-development-status.md)。

T12 技术任务与人员交接开发实现已通过验证分支完整 CI，见[T12 记录](docs/api/t12-development-status.md)。

T13 蜡块/玻片身份、重切加深、直制路径与标签复用已实现，完整验证分支 CI 已通过，见[T13 记录](docs/api/t13-development-status.md)。

T14 技术QC、隔离和返工已实现，完整验证分支CI已通过，见[T14记录](docs/api/t14-development-status.md)。异常放行仍禁用。

T15 已实现授权工作列表、当前页逐项批量领取、合成超期和既有流程追踪，完整CI待核验，见[T15记录](docs/api/t15-development-status.md)。

T16 诊断分配、领取与转交已完成本地实现，独立合成资格与材料QC门禁，未包含报告/签署。完整后端与真实E2E尚未验证；按本次指示不等待GitHub，见[T16记录](docs/api/t16-development-status.md)。

开发连通性与认证验证：React + Ant Design 登录后调用 Spring Boot 的 `GET /api/hello`，显示 **Hello World**。

## 范围

这是开发起点，已接入 PostgreSQL 17 与 Flyway，并建立患者、就诊、申请、病例、标本容器的合成数据结构模板及内部只读查询。已加入会话登录、CSRF、账号撤销及内部资源权限策略，尚未实现临床业务接口、临床签发、数字切片或 AI。仅在本机开发使用，不可处理真实患者数据，也不要直接暴露到公网。原始 `readme` 保留不变。模型关系、编号唯一域、版本契约与待确认假设详见[核心数据模型](docs/core-data-model.md)。

已加入统一ProblemDetail错误、严格JSON/参数校验、事务成功审计和短事务幂等基础，契约与生产权限边界见[API、审计与幂等](docs/api-audit-idempotency.md)。这些是后续授权业务接口的基础，不代表临床接口、合规审计或生产防篡改已经验收。

## 环境

- JDK 21
- Maven 3.9.16，由仓库中的 Maven Wrapper 3.3.4 下载和运行，无需安装系统 Maven
- Node.js 22.23.3（根目录 `.nvmrc`），npm 11.21.0（安装 Node 后显式升级，CI 使用同一固定版本）
- PostgreSQL 17；本机推荐 Docker Compose v2，详见[数据库开发说明](docs/database-development.md)

后端固定 Spring Boot 4.1.1；前端固定 React 19.2.4、Ant Design 6.6.5、Vite 7.3.6、Vitest 4.1.11 和 TypeScript 5.9.3。完整前端依赖树记录在 `frontend/package-lock.json`，日常开发和 CI 均使用 `npm ci` 冻结安装，不使用 `npm install` 重新解析依赖。`frontend/.npmrc` 会拒绝与声明不符的 Node/npm 版本。

使用 nvm 时，在仓库根目录运行 `nvm install && nvm use`；也可从 [Node.js 官网](https://nodejs.org/dist/v22.23.3/)安装对应版本。在仓库根目录运行 `npm install --global npm@11.21.0`，再确认 `node --version` 为 `v22.23.3`、`npm --version` 为 `11.21.0`，并用 `java -version` 和 `javac -version` 确认完整 JDK 21（只有 JRE 无法编译）。

Wrapper 使用 Apache 官方 `only-script` 发行，不提交 JAR。首次运行需要网络、JDK 21，以及 Linux/macOS 的 `curl` 或 `wget`、`unzip` 和 `sha256sum` 或 `shasum`，或 Windows PowerShell。它会校验已固定的 Maven ZIP 发行包 SHA-256；后续运行复用本地缓存。初次 Maven 构建与 npm 安装也需要访问各自的官方软件仓库。

## 启动

首次按[数据库开发说明](docs/database-development.md)创建 `.env`、替换本机密码，并在运行 Java/npm 的终端导出变量。以下是 Bash 示例；不要覆盖已有 `.env`。

首次登录前，按[身份与资源授权说明](docs/security-access-control.md)在本机环境中显式设置 `PIS_DEV_AUTH_ENABLED=true`、`PIS_DEV_USERNAME` 和 `PIS_DEV_PASSWORD`。账号必须是合成测试身份，密码没有默认值，也不会获得任何病例权限。

终端一（仓库根目录）：

```bash
set -a; . ./.env; set +a
docker compose up -d --wait postgres
cd backend
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

终端二：

```bash
cd frontend
npm ci
npm run dev
```

两个终端都从仓库根目录开始。Windows PowerShell 使用 `mvnw.cmd`，并通过 `$env:变量名` 设置同名数据库变量及 `SPRING_PROFILES_ACTIVE=dev`；Bash 的导出命令不能直接用于 PowerShell。

打开 http://127.0.0.1:5173 。开发服务器把 `/api` 代理至 http://127.0.0.1:8080 ，无需开放跨域。

使用自己配置的本机合成账号登录后，接口返回：

```json
{"message":"Hello World","application":"PIS"}
```

界面包含登录、会话恢复、退出、连接状态和重新请求；过时响应不能在退出后恢复已登录界面。两个服务默认只监听本机。认证与权限边界、未启用的并发会话上限及生产门槛见[安全说明](docs/security-access-control.md)。

## 构建与测试

```bash
set -a; . ./.env; set +a
docker compose --profile test up -d --wait postgres-test
cd backend
./mvnw -B -ntp verify
cd ..
cd frontend
npm ci
npm run lint
npm test
npm run build
npm run audit:dependencies
npx playwright install chromium
npm run test:e2e
```

测试使用独立的真实 PostgreSQL 17 测试库；`PIS_TEST_DB_PASSWORD` 必须已导出。数据库或迁移失败会使测试失败，不会跳过或回退到 H2。浏览器测试会通过 Maven `spring-boot:test-run` 启动测试classpath中的隔离入口与 Vite，并等待包含数据库的 readiness 成功；运行前先完成后端 verify，并关闭占用 8080/5173 的开发服务。入口只在独立 `_test` 数据库的随机 schema创建合成账号，正式 JAR不含fixture或测试Controller。覆盖真实登录/退出/CSRF、会话恢复、错误重试与过时请求。后端 verify还会在打包后检查正式 JAR的测试隔离。

测试约定：

- Vitest 自动运行 `frontend/src/` 内所有 `*.test.ts`、`*.test.tsx`、`*.spec.ts` 和 `*.spec.tsx`，新增单元测试不需要修改 npm 脚本。默认使用 Node 环境；需要 DOM 的组件测试应配置对应环境和依赖。
- Playwright 单独发现 `frontend/e2e/` 内的浏览器测试，不会被 Vitest 混跑。
- 在 `frontend` 中运行 `npm run test:list` 和 `npm run test:e2e -- --list` 可分别核对发现的测试；Playwright 列表命令同样需要已导出的测试数据库配置。
- `npm run lint` 检查源码、测试和配置文件，启用 ESLint/TypeScript 推荐规则与 React Hooks 核心规则；任何警告都会使检查失败。`npm run build` 包含源码、测试和配置的 TypeScript 检查。

后端产物：`backend/target/pis-backend-0.0.1-SNAPSHOT.jar`。
前端产物：`frontend/dist/`。生产部署需配置同源 API 反向代理，Vite 开发代理不包含在构建产物中。

## 更新构建基线

仅在有意升级依赖时，使用上面固定的 Node/npm 版本，在 `frontend` 中运行 `npm install <包名>@<精确版本> --save-exact`（开发依赖加 `--save-dev`），同时提交 `package.json` 与 npm 实际生成的 `package-lock.json`。不要手工编辑锁文件。之后重新运行 `npm ci`、lint、测试、构建、依赖审计和浏览器测试。

升级 Maven 时同步修改 `backend/.mvn/wrapper/maven-wrapper.properties` 的发行地址与经过核验的 SHA-256；升级 Node/npm 时同步更新 `.nvmrc`、`frontend/package.json`、锁文件与本说明。CI 从同一 `.nvmrc` 读取 Node 版本，显式安装固定 npm，并通过同一 Wrapper 构建后端。

当前升级到 Vite 7.3.6 / Vitest 4.1.11 以修复已知开发工具漏洞；Vitest 4 的 peer 依赖图会触发 npm 10 的 `edgesOut` 解析崩溃，因此同步固定 npm 11.21.0。不要使用 `--force` 或 `--legacy-peer-deps` 绕过依赖检查。

npm 11.21 的依赖安装脚本采用明确允许机制；本项目的 `allowScripts` 仅允许锁定的 `esbuild@0.28.2` 安装脚本。升级 esbuild 时应审核新版本的安装脚本，再通过 `npm install-scripts approve esbuild` 更新版本限定条目，不要使用允许全部脚本的选项。

## 持续集成

`.github/workflows/ci.yml` 在 main 推送和 PR 时先启动固定摘要的 PostgreSQL 17 service，再运行后端迁移/集成测试及打包、前端 lint、自动发现的单元测试、类型检查/构建以及 Chromium 集成测试，并独立检查前端依赖。实际是否成功请查看该提交的 GitHub Actions 结果；配置工作流不代表测试已通过。

依赖检查边界：

- 前端每次运行 `npm audit`，扫描锁文件中的生产和开发依赖，High/Critical 告警使 CI 失败。Low/Moderate 仍显示在报告中，需要评估修复，不能视为零风险。审计网络故障同样会使检查失败，不会跳过或伪装通过。
- PR 使用固定提交的 GitHub Dependency Review Action，审查 GitHub 依赖图识别到的 npm/Maven 变更，覆盖 runtime、development 和 unknown 范围，阻止新引入的 High/Critical 漏洞。仅保留 `contents: read`，不写 PR 评论，不新增密钥，也不设置尚未确定的许可证政策。
- Dependency Review 是变更审查，不是 Java 全量扫描；它不能保证覆盖 Maven 实际解析出的全部传递依赖、父 POM 或 BOM，也不能发现未变更依赖后来新增的告警。后续仍需补齐基于 Maven 实际解析依赖图或 SBOM 的全量安全扫描。当前 main 直接推送不会运行 PR 依赖变更审查。
- CI 失败会显示检查结果，但本任务不修改分支保护规则；是否强制阻止合并由仓库已有规则决定。

此工程使用现有账户权限提交，不包含真实密码、令牌、患者信息或自动部署步骤；CI 数据库凭据仅用于每次运行的临时合成测试服务。

## 数据库与迁移边界

- 生产 V1 仅初始化应用 schema；业务模型留待后续任务，不包含患者表或临床数据
- 空 schema 迁移、重复迁移、合成 V1→V2 升级、校验和错误、DDL 回滚与启动失败均有测试
- 开发库只映射回环地址并使用持久卷；测试库独立且可丢弃，测试不会删除开发卷
- Actuator 仅开放不带详情的 health；readiness 包含数据库，liveness 不依赖数据库
- 当前 Compose 账号是仅供本机使用的超级用户；生产前必须分离迁移 DDL 账号与最小权限运行账号

配置、环境变量、数据保留、迁移规则及账号分离路线见[数据库开发说明](docs/database-development.md)。

T17本地实现：[人工结构化报告草稿交付记录](docs/api/t17-development-status.md)。不可变模板与修订、当前已领取医生权限和CAS保存；完整后端及真实E2E受依赖缺失阻塞，CI未验证。

T18本地实现：[复核与模拟签署交付记录](docs/api/t18-development-status.md)。独立合成资格、显式职责分离、依赖失效、退回及冻结快照；无临床/CA效力，完整后端、真实E2E和CI未验证。

T19本地实现：[固定合成PDF与打印记录](docs/api/t19-development-status.md)。保存不可变字节/哈希及冻结版本，审计预览下载和追加打印自报；每页标明非临床，仅合成演示。完整后端、真实E2E和CI仍未验证。

T20本地实现：[补充、更正与新版本链](docs/api/t20-development-status.md)。原冻结报告/PDF不可变，新草稿独立复核及合成模拟签署，替代关系只标记待替换、未发送。完整后端、真实E2E和CI未验证。

T21本地实现：[合成投递、ACK、重试与对账](docs/api/t21-development-status.md)。人工驱动持久化outbox/inbox，仅本地数据库模拟端，无外部传输；CA仅NOT_CONFIGURED/UNVERIFIED。完整后端、真实E2E和CI未验证。

T22 已增加独立术中冰冻合成工作站：人工时间、更正、草稿/复核、转交、本地沟通/回读/确认及确切常规报告版本关联。仅本地开发，默认关闭，无真实通信或临床用途。范围、实际检查和 Maven/真实 E2E/CI 阻塞见 [T22 交付记录](docs/api/t22-development-status.md)。

T23 已增加细胞学三类合成制备路径与无需蜡块的可追溯玻片、数量预留/核对、独立重复身份和来源QC隔离。仍默认关闭，无临床使用。实际检查与完整后端/真实E2E/CI阻塞见 [T23 本地交付记录](docs/api/t23-development-status.md)。

T24 已增加特殊染色/IHC合成批次、不可变项目/方案版本、逐张新身份、冻结成员及对照撤销失效传播。默认关闭，未接仪器或临床方案。实际检查与后端/真实E2E/CI阻塞见 [T24本地交付记录](docs/api/t24-development-status.md)。

T25 已增加院内合成会诊/复阅：病例限时参与、版本化个人意见、明确汇总与分歧、独立确认及非签署草稿追加采纳。实际证据和后端/真实E2E/CI阻塞见[T25本地交付记录](docs/api/t25-development-status.md)。

T26 已实现合成人工档案、排他预约、逐项借还及冻结盘点/差异更正，报告绑定原不可变产物；无物理确认、外发或销毁。完整后端与真实E2E未验证，见[T26本地交付记录](docs/api/t26-development-status.md)。

T27 已实现合成工作量/TAT/QC固定统计快照与同权限来源下钻，默认开发开关关闭，无CSV或对外投递；见[指标开发PRD](docs/prd/development-statistics-v1.md)及[接口/本地验证边界](docs/api/t27-statistics.md)。T27修复510856a484e616e76abba5334d49ab31f946dbe3的完整CI由父会话确认通过，已正常快进main；该结论仅为合成开发基线验证。

T28 新增私有本地合成原件、不可变版本/哈希、流式暂存与完成对账、范围读取及真实容量；S3未配置，非WSI验证。见[开发PRD](docs/prd/development-storage-v1.md)及[接口/验证边界](docs/api/t28-storage.md)，T28验证分支完整CI尚待核验。

T28已按父会话确认的完整CI正常快进main，确切SHA为7eb4975e9062e6d2ecc0a5fe4f2277e5a2845aed。T29新增合成扫描导入、双向身份核对、租约重试/取消及独立重扫历史；厂商适配未配置，导入不等于阅片可用，见[T29接口及验证边界](docs/api/t29-scan-import.md)。T29完整验证分支CI待父会话核验。

T29已通过完整验证并快进main（e482d3e1e5587fae959dc2c641463ab5fa116326）。T30新增精确版本人工合成数字QC与受控发布，独立验证分支待CI；[范围与验证边界](docs/api/t30-digital-qc.md)。发布只表示合成契约访问资格，不证明真实切片可阅片。

T30及修复已由父会话确认完整CI成功并快进main（fb1388276f9953c27bd63e6ca03586035a1599b7）。T31新增OpenSeadragon合成RGB阅片、实际PNG多层瓦片、导航与受控私有缓存；[接口/实际截图/验证边界](docs/api/t31-viewer.md)。仅明确合成格式，不支持真实WSI或物理测量，独立validation/t31待完整CI。

T31已完整验证并快进main（cf87b0773ba4aa796e9cd175b1792913a725578b）。T32新增真实OSD矩形/多边形/点ROI、像素测量、版本化合成X/Y校准和双视图；[接口及验证边界](docs/api/t32-roi.md)。默认未校准，不提供临床精度保证；T32已由父会话核验完整CI并快进main（a75d11f5757803c034acc662ea87544f62f6d7bb）。

T33增加可复现格式/像素/坐标/并发/故障验证及两项客户端加固：瓦片流读取上限、主图与导航图共享并发队列。[兼容矩阵与原始证据](docs/api/t33-viewer-validation.md)明确实际合成PNG覆盖及真实WSI/ICC/GPU/完整应用重启缺口。独立validation/t33待完整CI，未合main；不代表临床可用。

T34 已新增模型注册、不可变契约版本与合成适用资格开发实现（不安装或运行模型），[接口/验证边界](docs/api/t34-ai-registry.md)。仅validation/t34，完整CI待父会话核验；开发默认关闭，无临床或监管批准。
