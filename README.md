# PIS · Hello World

最小工程连通性验证：React + Ant Design 页面调用 Spring Boot 的 `GET /api/hello`，显示 **Hello World**。

## 范围

这是开发起点，尚未实现病理业务、身份认证、PostgreSQL 持久化、数字切片或 AI。仅在本机开发使用，不可处理真实患者数据，也不要直接暴露到公网。原始 `readme` 保留不变。

## 环境

- JDK 21
- Maven 3.9.x（当前使用系统 Maven，尚未加入 Wrapper）
- Node.js 22.12+，npm
- 本例不需要 PostgreSQL

已固定直接依赖版本。首版通过仓库接口创建，暂未包含 npm 锁文件；首次 `npm install` 会生成 `package-lock.json`。后续应审核并提交锁文件，改用 `npm ci`，再建立完整可重复构建基线。

## 启动

终端一（仓库根目录）：

```bash
mvn -f backend/pom.xml spring-boot:run
```

终端二：

```bash
cd frontend
npm install
npm run dev
```

打开 http://127.0.0.1:5173 。开发服务器把 `/api` 代理至 http://127.0.0.1:8080 ，无需开放跨域。

接口返回：

```json
{"message":"Hello World","application":"PIS"}
```

界面包含连接中、成功、失败及重新请求，离开页面时取消请求。两个服务默认只监听本机。

## 构建与测试

```bash
mvn -B -f backend/pom.xml verify
cd frontend
npm install
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

浏览器测试会自行启动后端 JAR 与 Vite，运行前先关闭占用 8080/5173 的开发服务。覆盖真实前后端请求、重复请求，以及接口错误后重试恢复。

后端产物：`backend/target/pis-backend-0.0.1-SNAPSHOT.jar`。
前端产物：`frontend/dist/`。生产部署需配置同源 API 反向代理，Vite 开发代理不包含在构建产物中。

## 持续集成

`.github/workflows/ci.yml` 在 main 推送和 PR 时运行后端测试/打包、前端单元测试/类型检查/构建以及 Chromium 集成测试。实际是否成功请查看该提交的 GitHub Actions 结果；配置工作流不代表测试已通过。

此工程使用现有账户权限提交，不包含密码、令牌、患者信息或自动部署步骤。
