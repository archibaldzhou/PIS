# T31 合成阅片器：接口与交付记录

[开发PRD](../prd/development-tile-viewer-v1.md) / [ADR0021](../adr/0021-bounded-synthetic-tile-viewer.md)。T30及修复保留历史快进main，远端精确SHA为fb1388276f9953c27bd63e6ca03586035a1599b7。父会话确认其验证分支CI37146763601成功；本T31独立validation/t31，未通过完整CI前不合main。

基础路径`/api/requests/{requestId}/scans/{scanId}/viewer`：

- POST `{publicationVersion}`，Cookie会话、CSRF、Idempotency-Key；生成有界合成PNG金字塔，事务内冻结清单及审计，返回VIEWER_MANIFEST回执。原键重放不覆盖，重新验证当前资格。
- GET `?publicationVersion=N`：完整有界清单，精确医院/申请/扫描/玻片/原件/源hash/源版本、provider、每瓦片层级坐标尺寸hash、总清单hash；capability固定SYNTHETIC_RGB_ONLY，calibration固定UNAVAILABLE_NO_PHYSICAL_SCALE。
- GET `/tiles/{level}/{x}/{y}?publicationVersion=N`和`/thumbnail?publicationVersion=N`：PNG字节；private,no-store/nosniff、Content-Length、X-Content-SHA256。三类资源都在返回前重查当前授权/QC，缓存不会延长权限。
- 401身份失效；404隐藏未授权对象；409旧发布/未准备/来源变化；400未知格式/越界；429预算或生成并发占满。无外部URL、文件路径、公开存储或CDN入口。

V29只追加元数据，明确最新迁移基线29，保留历史升级、checksum、空种子、重复迁移断言并补28→29升级。PNG通过真实JDK本地提供者生成；原件来自T28不可变文件，T29解析PISRGB1实际头/像素长度，T30精确版本人工合成发布仍是前置条件。旧合成头不会被伪造为图像。

## 本地证据

- `java backend/src/test/probes/ViewerProbe.java`实际编译生成器、生成两组48张真实PNG，解码尺寸/像素差异/确定性hash/坏输入检查通过。
- `python3 backend/src/test/probes/viewer-postgres.py`真实PG17执行V1–V29，检查错误源绑定、不可变性、审计回滚和并发配额，通过。
- 前端156项单测、lint、类型与构建通过；npm audit零已知漏洞。构建有既有bundle体积警告，不关闭检查。
- 新增4项UI真实Chromium交互全部通过：实际像素、缩放/键盘平移/适配、QC撤销、缺片与重试、迟到A/B切换、取消/销毁。使用真实Java生成PNG与模拟API；不能等同真实后端E2E。已查看[实际浏览器截图](../evidence/t31/synthetic-viewer.png)，导航图和合成几何图确实可见，测试同时检查canvas像素阈值。
- 全套68项UI通过；收尾取消生成修复后5项阅片UI复测通过（包含1项新增，不声称69项全套重跑），lint及类型复测通过。新增真实服务E2E包含T28上传→T29导入→T30评价/发布→T31实际浏览器渲染与逐资源撤销；Playwright共发现44项真实E2E；发现列表不算执行。
- 新增后端测试涵盖真实PNG/坐标/坏输入、权限与缓存撤销、幂等并发清单、读写审计失败及预算；源码AST/作用域扫描不是编译替代。

## 未验证边界

本地Maven离线verify在模型解析前失败：Spring Boot4.1.1引入的Zipkin3.5.3、Brave6.3.1等BOM未缓存，完整后端编译/测试与真实服务E2E未运行。未重试既有拒绝下载或Actions查询；完整CI交父会话读取。没有真实WSI/厂商格式、扫描仪、临床图像质量、物理比例尺或T32测量验证，不宣称可合并/临床可用/投产。开发合成开关默认关闭，无真实数据、外发、CA或部署。
