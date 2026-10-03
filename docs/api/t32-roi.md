# T32 ROI、测量及双视图交付记录

[PRD](../prd/development-roi-v1.md) / [ADR0022](../adr/0022-versioned-roi-pixel-coordinates.md)。T31经父会话确认CI37149732462成功，已快进推送main精确cf87b0773ba4aa796e9cd175b1792913a725578b；本T32独立validation/t32，完整CI未通过前不合main。

基础路径`/api/requests/{requestId}/scans/{scanId}/roi`：

- GET `?publicationVersion=N`：当前manifest绑定、集合版本、当前用户、最新合成校准及每ROI最新修订，最多50项，当前QC发布与资格/范围/资源权限及审计均需通过。
- POST：publicationVersion/manifestHash/expectedVersion/roiId/expectedRevision/kind/points/deleted/reason/calibrationVersion。首次集合/ROI版本-1；仅本人更新，软删除不能改动几何，原因强制。CSRF、严格DTO、原键幂等与原子审计；409精确冲突，400几何/校准无效，404隐藏资源或作者越权。当前源失效沿用T30错误。
- POST `/calibration`：publicationVersion/manifestHash/expectedVersion/mppX/mppY/basis=SYNTHETIC_TEST/reason；合成测试校准，X/Y各(0,100]um/px、最多20个不可变版本。与ROI使用同一集合CAS，不静默改变旧测量。
- GET `/{roiId}/history`：显式独立历史授权，最多100个追加修订；可以在QC撤销后返回仍有权限的历史，但不会恢复图像/编辑，也不绕过人员资格或组织/病例权限。

真实OSD叠加支持矩形/多边形/点、选择与顶点重定位、撤销本地顶点、显式保存/软删除、旋转/翻转/中心裁剪。双视图默认独立，仅同一精确manifest可显式恒等同步。A/B切换、迟到响应、未确认保存有独立清理及原键重试。

## 实际本地证据

- 已实际查看合法UI-041原型；[真实浏览器ROI截图](../evidence/t32/synthetic-roi.png)已查看，矩形、多边形和点均真实叠加在旋转后的合成PNG上；[双视图实际截图](../evidence/t32/synthetic-dual-view.png)已查看并修正画布垂直错位。
- Java `RoiProbe`实际编译几何源码并验证非有限值/边界、自交、像素默认及独立X/Y版本化测量，通过。
- `roi-postgres.py`真实PG17 V1–V30：manifest外键、集合并发CAS、不可变校准/修订、回滚与MPP边界，通过；不替代Spring/Flyway完整测试。
- 新增后端测试覆盖集合CAS/幂等、作者与跨范围、配额、校准修订、软删除、QC撤销/授权历史、原子审计回滚、HTTP认证/CSRF和过期身份。迁移最新契约明确30，新增29→30升级，保留历史基线、checksum与空种子断言。
- 前端163项单测、lint、类型/构建通过。73项全套UI通过；收尾双视图对齐、迟到保存/裁剪新增回归后10项阅片/ROI复测通过（其中1项新增，未声称74项全套重跑）。明确放弃未确认操作后的重新读取改为有界当前请求，避免迟到刷新覆盖；该收尾的5项ROI复测、lint和构建均通过。真实服务E2E共发现44项，未本地执行；在既有viewer独立账号来源链追加ROI实际绘制、持久化、旋转截图、QC撤销、历史与越权断言，不增加共享账号压力。

本地Maven离线verify仍在模型解析阶段失败：Boot4.1.1依赖Zipkin3.5.3/Brave6.3.1等BOM未缓存。完整后端编译/集成与真实服务E2E未在本地运行，完整CI交父会话核验，不重试既有Forbidden接口或已拒绝下载。AST/独立Java/PG探针和模拟API的浏览器UI均不能替代完整CI。

不含真实WSI、医院MPP校准、临床准确性、AI/自动配准、设备、真实患者、CA、外发或部署。合成模式默认关闭；本地部分通过不等于临床可用或可投产。
