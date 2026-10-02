# PIS · Hello World

最小工程连通性验证：React + Ant Design 页面调用 Spring Boot 的 `GET /api/hello`，显示 **Hello World**。

## 范围

这是开发起点，尚未实现病理业务、身份认证、PostgreSQL 持久化、数字切片或 AI。仅在本机开发使用，不可处理真实患者数据，也不要直接暴露到公网。原始 `readme` 保留不变。

## 环境

- JDK 21
- Maven 3.9.16，由仓库中的 Maven Wrapper 3.3.4 下载和运行，无需安装系统 Maven
- Node.js 22.23.3（根目录 `.nvmrc`），npm 11.21.0（安装 Node 后显式升级，CI 使用同一固定版本）
- 本例不需要 PostgreSQL

后端固定 Spring Boot 4.1.1；前端固定 React 19.2.4、Ant Design 6.6.5、Vite 7.3.6、Vitest 4.1.11 和 TypeScript 5.9.3。完整前端依赖树记录在 `frontend/package-lock.json`，日常开发和 CI 均使用 `npm ci` 冻结安装，不使用 `npm install` 重新解析依赖。`frontend/.npmrc` 会拒绝与声明不符的 Node/npm 版本。

使用 nvm 时，在仓库根目录运行 `nvm install && nvm use`；也可从 [Node.js 官网](https://nodejs.org/dist/v22.23.3/)安装对应版本。在仓库根目录运行 `npm install --global npm@11.21.0`，再确认 `node --version` 为 `v22.23.3`、`npm --version` 为 `11.21.0`，并用 `java -version` 和 `javac -version` 确认完整 JDK 21（只有 JRE 无法编译）。

Wrapper 使用 Apache 官方 `only-script` 发行，不提交 JAR。首次运行需要网络、JDK 21，以及 Linux/macOS 的 `curl` 或 `wget`、`unzip` 和 `sha256sum` 或 `shasum`，或 Windows PowerShell。它会校验已固定的 Maven ZIP 发行包 SHA-256；后续运行复用本地缓存。初次 Maven 构建与 npm 安装也需要访问各自的官方软件仓库。

## 启动

终端一（仓库根目录）：

```bash
cd backend
./mvnw spring-boot:run
```

终端二：

```bash
cd frontend
npm ci
npm run dev
```

两个终端都从仓库根目录开始。Windows PowerShell 中将 `./mvnw` 替换为 `.\mvnw.cmd`，其他参数相同。

打开 http://127.0.0.1:5173 。开发服务器把 `/api` 代理至 http://127.0.0.1:8080 ，无需开放跨域。

接口返回：

```json
{"message":"Hello World","application":"PIS"}
```

界面包含连接中、成功、失败及重新请求，离开页面时取消请求。两个服务默认只监听本机。

## 构建与测试

```bash
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

浏览器测试会自行启动后端 JAR 与 Vite，运行前先关闭占用 8080/5173 的开发服务。覆盖真实前后端请求、重复请求，以及接口错误后重试恢复。

测试约定：

- Vitest 自动运行 `frontend/src/` 内所有 `*.test.ts`、`*.test.tsx`、`*.spec.ts` 和 `*.spec.tsx`，新增单元测试不需要修改 npm 脚本。默认使用 Node 环境；需要 DOM 的组件测试应配置对应环境和依赖。
- Playwright 单独发现 `frontend/e2e/` 内的浏览器测试，不会被 Vitest 混跑。
- 在 `frontend` 中运行 `npm run test:list` 和 `npm run test:e2e -- --list` 可分别核对发现的测试。
- `npm run lint` 检查源码、测试和配置文件，启用 ESLint/TypeScript 推荐规则与 React Hooks 核心规则；任何警告都会使检查失败。`npm run build` 包含源码、测试和配置的 TypeScript 检查。

后端产物：`backend/target/pis-backend-0.0.1-SNAPSHOT.jar`。
前端产物：`frontend/dist/`。生产部署需配置同源 API 反向代理，Vite 开发代理不包含在构建产物中。

## 更新构建基线

仅在有意升级依赖时，使用上面固定的 Node/npm 版本，在 `frontend` 中运行 `npm install <包名>@<精确版本> --save-exact`（开发依赖加 `--save-dev`），同时提交 `package.json` 与 npm 实际生成的 `package-lock.json`。不要手工编辑锁文件。之后重新运行 `npm ci`、lint、测试、构建、依赖审计和浏览器测试。

升级 Maven 时同步修改 `backend/.mvn/wrapper/maven-wrapper.properties` 的发行地址与经过核验的 SHA-256；升级 Node/npm 时同步更新 `.nvmrc`、`frontend/package.json`、锁文件与本说明。CI 从同一 `.nvmrc` 读取 Node 版本，显式安装固定 npm，并通过同一 Wrapper 构建后端。

当前升级到 Vite 7.3.6 / Vitest 4.1.11 以修复已知开发工具漏洞；Vitest 4 的 peer 依赖图会触发 npm 10 的 `edgesOut` 解析崩溃，因此同步固定 npm 11.21.0。不要使用 `--force` 或 `--legacy-peer-deps` 绕过依赖检查。

npm 11.21 的依赖安装脚本采用明确允许机制；本项目的 `allowScripts` 仅允许锁定的 `esbuild@0.28.2` 安装脚本。升级 esbuild 时应审核新版本的安装脚本，再通过 `npm install-scripts approve esbuild` 更新版本限定条目，不要使用允许全部脚本的选项。

## 持续集成

`.github/workflows/ci.yml` 在 main 推送和 PR 时运行后端测试/打包、前端 lint、自动发现的单元测试、类型检查/构建以及 Chromium 集成测试，并独立检查前端依赖。实际是否成功请查看该提交的 GitHub Actions 结果；配置工作流不代表测试已通过。

依赖检查边界：

- 前端每次运行 `npm audit`，扫描锁文件中的生产和开发依赖，High/Critical 告警使 CI 失败。Low/Moderate 仍显示在报告中，需要评估修复，不能视为零风险。审计网络故障同样会使检查失败，不会跳过或伪装通过。
- PR 使用固定提交的 GitHub Dependency Review Action，审查 GitHub 依赖图识别到的 npm/Maven 变更，覆盖 runtime、development 和 unknown 范围，阻止新引入的 High/Critical 漏洞。仅保留 `contents: read`，不写 PR 评论，不新增密钥，也不设置尚未确定的许可证政策。
- Dependency Review 是变更审查，不是 Java 全量扫描；它不能保证覆盖 Maven 实际解析出的全部传递依赖、父 POM 或 BOM，也不能发现未变更依赖后来新增的告警。后续仍需补齐基于 Maven 实际解析依赖图或 SBOM 的全量安全扫描。当前 main 直接推送不会运行 PR 依赖变更审查。
- CI 失败会显示检查结果，但本任务不修改分支保护规则；是否强制阻止合并由仓库已有规则决定。

此工程使用现有账户权限提交，不包含密码、令牌、患者信息或自动部署步骤。
