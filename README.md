# PIS · 开发基础

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
