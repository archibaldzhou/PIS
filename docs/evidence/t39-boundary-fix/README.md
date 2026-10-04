# T39 模块依赖环修复

父会话确认 `e0a4fcf0625ec9e74e92e739ca68b034b8333c71` 的 CI 37187174615 后端506项仅架构测试失败。原环境、validation/t39，开工工作区干净，main不修改。

实际模块环：

- `report.DeliveryService → integration.CaAdapter`，调用具体工厂取得能力状态。
- `integration.HospitalAdapterService → report.DeliveryPolicy`，复用有界尝试规则。
- 另有 `integration.LocalEmrAdapterController → report.DeliveryService/DeliveryContracts`，原扫描器漏掉了通配符 import。

这些是模块边，不表示 CaAdapter 自身在运行时反调其他服务。修复保留上层集成依赖报告方向，报告用例改依赖自己拥有的只读 SignatureCapability 端口，集成provider实现并经构造器注入。没有移动既有类规避扫描，没有循环引用开关；没有改迁移、权限、幂等、状态机、审计或事务边界。端口只允许 NOT_CONFIGURED/UNVERIFIED，无签名能力。

架构测试继续全部原域无环检查及 processing 白名单，增加通配符扫描、完整路径诊断、明确单向依赖断言和扫描器回归。新增provider能力单测；既有真实HTTP回归增加EMR别名/原入口响应一致及NOT_CONFIGURED断言，保留原去重与撤权测试。

可复现本地检查：

- `python3 backend/src/test/probes/technical-boundaries.py --revision e0a4fcf0625ec9e74e92e739ca68b034b8333c71 --expect-cycle`：精确复现环，before.txt。
- `python3 backend/src/test/probes/technical-boundaries.py`：当前13域含通配符依赖无环，after.txt。只是源码检查，不是字节码分析/JUnit执行。
- Java syntax、lexical-scope、HTTP受检异常声明探针通过，java-source.txt；不是类型编译。
- `python3 backend/src/test/probes/ai-postgres.py`：PG17约束/并发/回滚探针，postgres.txt；不是Spring/HTTP测试。
- 离线 Maven verify 仍在POM解析阶段被原缓存缺失BOM阻断，maven-offline.txt。没有重试下载或改变依赖。

本次完整后端编译/JUnit、Spring端口装配、真实HTTP/E2E和完整CI未验证，需父会话核验新SHA。前端未变，本次不重复运行UI并冒充真实服务验证。没有合main、部署、访问Actions或更改凭据/OAuth/安全设置。所有既有历史与安全断言保留。
