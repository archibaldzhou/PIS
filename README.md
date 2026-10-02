# PIS · Hello World

最小工程连通性验证：React + Ant Design 页面调用 Spring Boot 的 `GET /api/hello`，显示 **Hello World**。

## 范围

这是开发起点，尚未实现病理业务、身份认证、PostgreSQL 持久化、数字切片或 AI。仅在本机开发使用，不可处理真实患者数据，也不要直接暴露到公网。原始 `readme` 保留不变。

## 环境

- JDK 21
- Maven 3.9.16，由仓库中的 Maven Wrapper 3.3.4 下载和运行，无需安装系统 Maven
- Node.js 22.23.3（根目录 `.nvmrc`），npm 10.9.9（该 Node 版本自带）
- 本例不需要 PostgreSQL

后端固定 Spring Boot 4.1.1；前端固定 React 19.2.4、Ant Design 6.6.5、Vite 7.3.1 和 TypeScript 5.9.3。完整前端依赖树记录在 `frontend/package-lock.json`，日常开发和 CI 均使用 `npm ci` 冻结安装，不使用 `npm install` 重新解析依赖。`frontend/.npmrc` 会拒绝与声明不符的 Node/npm 版本。

使用 nvm 时，在仓库根目录运行 `nvm install && nvm use`；也可从 [Node.js 官网](https://nodejs.org/dist/v22.23.3/)安装对应版本。确认 `node --version` 为 `v22.23.3`、`npm --version` 为 `10.9.9`，并用 `java -version` 和 `javac -version` 确认完整 JDK 21（只有 JRE 无法编译）。

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
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

浏览器测试会自行启动后端 JAR 与 Vite，运行前先关闭占用 8080/5173 的开发服务。覆盖真实前后端请求、重复请求，以及接口错误后重试恢复。

后端产物：`backend/target/pis-backend-0.0.1-SNAPSHOT.jar`。
前端产物：`frontend/dist/`。生产部署需配置同源 API 反向代理，Vite 开发代理不包含在构建产物中。

## 更新构建基线

仅在有意升级依赖时，使用上面固定的 Node/npm 版本，在 `frontend` 中运行 `npm install <包名>@<精确版本> --save-exact`（开发依赖加 `--save-dev`），同时提交 `package.json` 与 npm 实际生成的 `package-lock.json`。不要手工编辑锁文件。之后重新运行 `npm ci`、测试、构建和浏览器测试。

升级 Maven 时同步修改 `backend/.mvn/wrapper/maven-wrapper.properties` 的发行地址与经过核验的 SHA-256；升级 Node/npm 时同步更新 `.nvmrc`、`frontend/package.json`、锁文件与本说明。CI 从同一 `.nvmrc` 读取 Node 版本，并通过同一 Wrapper 构建后端。

## 持续集成

`.github/workflows/ci.yml` 在 main 推送和 PR 时运行后端测试/打包、前端单元测试/类型检查/构建以及 Chromium 集成测试。实际是否成功请查看该提交的 GitHub Actions 结果；配置工作流不代表测试已通过。

此工程使用现有账户权限提交，不包含密码、令牌、患者信息或自动部署步骤。
