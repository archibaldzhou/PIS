# T38 测试编译修复

父会话报告 CI 37184036701 / verify 111382153589 在 testCompile 阶段失败：RequestWorkflowTest 跨包调用 package-private ReportService.current(UUID)。本次仅将两处调用改为现有 public、带事务与资格/病例授权的 `reports.detail(id).current()`。内部 current/revision 可见性不变，不直接查询数据库替代授权，不删减断言。

原测试仍在同一合格合成医生身份下，于模拟签署后记录完整 Revision，并在停用、人工知悉、重新启用及旧请求拒绝之后比较完整 Revision 相等，保留 ID、版本、字段、模板、作者和时间等原不变断言。其余失效、历史、幂等、权限与审计断言不变。

本地执行：

- Java syntax parse、lexical scope、HTTP checked-exception声明探针通过。
- 逐项检查 T38 测试直接service调用的公共声明，通过（public-api-review.txt）；该检查不是编译器类型检查，也不声称可发现全部编译错误。
- `python3 backend/src/test/probes/ai-postgres.py` 通过（postgres.txt）；SQL探针不等于应用测试。
- 离线 Maven verify 仍在BOM解析失败：缺 zipkin-reporter-bom3.5.3、brave-bom6.3.1等（backend-offline.txt）。未执行本地完整后端编译/testCompile/JUnit/打包或真实E2E；此次完整CI由父会话核验。

前端与产品实现未改，不重复运行无关UI套件。不以先前T38本地UI通过代替失败CI的后续阶段。main保持T37，不查询Actions、不改变凭据或安全配置、不改写历史。

日志仅规范行尾空白，未删减失败信息。
