# PostgreSQL 与迁移开发说明

## 当前边界

T04 接入 PostgreSQL 17、Spring JDBC、Flyway 和最小健康检查。生产迁移 V1 仅建立应用 schema 的初始化记录；T05 的增量 V2 建立核心数据结构，详见[核心数据模型与数据字典](core-data-model.md)。没有新增临床 HTTP API、身份权限或账号表。T04 独立合成升级 fixtures 与 T05 的真实生产 V1→V2 升级测试分开保留，执行结果应分别报告。

本工程目前只允许本机开发及合成数据测试，不可处理真实患者数据或直接开放公网。Compose 创建的 `POSTGRES_USER` 是 PostgreSQL 超级用户，仅用于这个本机开发起点，不能当作生产运行账号。

## 依赖

- Java 21，仓库 Maven Wrapper
- Docker Engine/Desktop 与 Compose v2，或自行准备隔离的 PostgreSQL 17
- PostgreSQL 官方镜像 `17.11-bookworm`，固定多架构摘要 `sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652`
- Spring Boot 4.1.1 BOM 管理 JDBC 驱动、Flyway 与相关 Spring 依赖，不单独覆盖版本

镜像摘要来自 [Docker 官方镜像元数据](https://github.com/docker-library/repo-info/blob/master/repos/postgres/remote/17.11-bookworm.md)，于 2026-10-02 核验。升级镜像需重新运行完整测试。

## 本机配置与启动

以下命令从仓库根目录执行，使用 Bash。首次复制 `.env.example` 为 `.env`，填写两个仅用于本机的密码，不覆盖已有 `.env`，不要提交 `.env`。

```bash
cp .env.example .env
# 编辑 .env 后，在每个运行 Java/npm 的终端导出：
set -a
. ./.env
set +a

docker compose up -d --wait postgres
SPRING_PROFILES_ACTIVE=dev ./backend/mvnw -f backend/pom.xml spring-boot:run
```

Docker Compose 会读取 `.env` 做插值，但不会替宿主机的 Java/npm 进程导出变量。示例文件只包含占位值，不会自动生成或保存密码。

默认开发地址为 `127.0.0.1:5432/pis_dev`。运行时可通过 `PIS_DB_URL`、`PIS_DB_USERNAME`、`PIS_DB_PASSWORD` 提供连接；除显式 `dev` profile 的本机 URL/用户名外，不提供默认连接或密码。非本机环境需单独评估 TLS、网络与身份权限，当前文档不是生产部署说明。

Flyway 在应用启动时创建 `pis` schema 并执行迁移；连接或迁移失败会使启动失败，不会切换为内存库或关闭迁移继续运行。只有 Flyway 负责结构变更，禁止同时使用 `schema.sql`、`data.sql` 或 ORM 自动建表。

## 测试

测试数据库与开发数据库分开。Compose 的 `postgres-test` 使用内存临时卷，仅供合成测试，停止/重建可能丢失数据。测试不会自动跳过，也不使用 H2。

```bash
# 同样先在当前终端导出 .env
docker compose --profile test up -d --wait postgres-test
./backend/mvnw -B -f backend/pom.xml verify

npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix frontend exec -- playwright install chromium
npm --prefix frontend run test:e2e
```

测试默认连接 `127.0.0.1:5433/pis_test`，用户名 `pis_test`，密码必须由 `PIS_TEST_DB_PASSWORD` 提供。可用 `PIS_TEST_DB_URL`、`PIS_TEST_DB_USERNAME` 指向其他隔离测试服务；数据库名必须以 `_test` 结尾，数据库必须是真实 PostgreSQL 17。

迁移测试逐用例、HTTP 测试逐类创建随机 `pis_test_*` schema，仅删除自己生成的 schema，不调用 Flyway clean，不删除数据库。Playwright 启动真实后端 JAR，将测试数据库变量传给后端并等待数据库 readiness 成功；原有真实前后端请求、重试和错误恢复测试继续执行。

覆盖范围：

- 空 schema 的生产 V1+V2 迁移及重复运行无新增迁移
- 真实生产 V1→V2 增量升级、核心表约束和内部 JDBC 查询，具体见核心数据模型说明
- 已执行迁移的校验和更改被拒绝
- 合成 V1→V2 升级保留原记录并新增字段
- PostgreSQL DDL 迁移失败的事务回滚
- 非空且没有迁移历史的 schema 不被自动接管
- 不可用数据库及错误迁移阻止应用启动
- Hello HTTP 响应与最小 health 暴露

GitHub Actions 使用固定摘要的 PG17 service，在真实数据库上执行这些测试和原有浏览器测试。只有对应提交的完整 CI 成功，才认为该提交通过；本地只编译或只运行某个用例不代表完整通过。

## 健康检查与运行边界

- `/actuator/health/readiness` 包含数据库连接状态；数据库不健康返回非就绪状态
- `/actuator/health/liveness` 不依赖数据库，避免数据库故障导致应用重启风暴
- 不返回 health components/details；不暴露 env、configprops、Flyway 管理端点
- 开发服务器仍仅监听 `127.0.0.1`

Hello 是工程连通性接口，不代表已有临床业务。后续业务模块必须依赖同一个真实数据源并正常传播数据库失败；不能用内存业务状态掩盖持久化失败。

## 迁移规则

1. 已执行的版本化 SQL 不再修改；新增 `V2__...sql` 等迁移进行变更
2. 不开启 `baseline-on-migrate`、`out-of-order` 或 `clean` 来跳过失败
3. 校验失败先核对代码/数据库版本，不自动 repair、删除 history 或删除开发卷
4. `src/test/resources/db/upgrade-fixture` 与 `db/invalid-fixture` 仅由指定测试加载，不进入应用 JAR
5. 新增业务模型时补充真实业务迁移、约束及旧版本升级测试
6. Flyway 不替代备份与恢复；生产前需单独演练升级、备份和恢复，不承诺自动逆向迁移

## 生产前必须完成的账号分离

当前本机超级用户配置不能带到生产。后续部署任务必须分离：

- 数据库管理员负责账号及基础授权
- 迁移账号拥有应用 schema 与必要 DDL 权限，由独立迁移作业短时使用
- 应用运行账号只获得必要的 CONNECT、USAGE、表/序列 DML 权限，不拥有 schema，不可执行 DDL，不可修改 Flyway history
- 配置未来新对象的 default privileges，并用自动化反向测试证明运行账号不能建表、删表或改迁移历史
- 迁移完成后才启动应用；运行环境不保存迁移账号凭据

## 停止服务

```bash
docker compose --profile test stop
```

开发库使用命名卷，停止容器不会删除它。不要把 `docker compose down -v` 当作普通停止命令；它会删除开发数据库卷。修改 `.env` 的数据库密码也不会重置已有卷中的数据库密码。
