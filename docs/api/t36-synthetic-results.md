# T36 合成结果与叠加交付

依据：[开发PRD](../prd/development-synthetic-result-v1.md)、[ADR0025](../adr/0025-immutable-synthetic-result-overlay.md)。仅人工显式创建非诊断技术视觉夹具；无真实模型、报告采纳或签署。

## 协议

基路径 `/api/requests/{request}/synthetic-results`。

- `POST /`：`{taskId,expectedTaskVersion,reason}` + CSRF/Idempotency-Key。仅已接受且当前仍有效的任务；T07回执body `receipt`/`replayed`。回执version=0表示不可变准备记录，接口仅在完成READY后成功返回；READY元数据version=1。相同键重试复用ID/字节，不同键重复创建409 `AI_RESULT_EXISTS`。
- `POST /{id}/resume`：CSRF、当前资源授权；以确切result ID恢复文件/数据库分阶段操作，返回Result。重复完成不重复READY审计，不允许覆盖。
- `GET /{id}`：当前授权/资格+包哈希/schema校验，返回result不可变追溯、64×64 PNG摘要、4×4强度、规范像素矩形、epoch；`executionAllowed=false`。
- `GET /{id}/tiles/0?epoch=...`：每次重新检查当前版本/授权，校验PNG摘要；private/no-store、image/png、nosniff、X-Content-SHA256、X-Result-Epoch。无任意URL或路径。epoch不是授权凭据。

BUILDING不可显示；缺失资源/当前权限404，失效/未就绪/摘要或版本冲突409，CSRF缺失403，匿名有效CSRF401，边界/未知字段400，读取配额429。失败不会显示空成功或阴性。原件、worker、result三种用途不能经通用storage字节端点互相读取。

UI在合成任务页面填写原因并生成视觉结果，展示确切ID；在对应扫描阅片器输入该ID并显式加载。切换图像销毁叠加，不沿用其他病例ID。透明度、混合、显隐均需当前资格；加载/失效/失败明确显示。图例为合成强度0–255，不表示疾病风险。原型中的报告采纳按钮未实现。

## 验证与边界

原环境恢复记录：T36暂停前17文件SHA-256全部一致；吸收已绿T35修复时只合并CI触发分支列表，其余16文件逐字节一致。T35 main为`67dd0fa86e0d3b44f24a7a1ec9b3c6870ff772d4`，未混入T36；用户父会话负责main CI。

原始输出、实际截图见[证据目录](../evidence/t36/)。

- 已执行：PG17 V1–V33真实迁移/SQL约束、结果来源/用途/版本、唯一ID、并发CAS、回滚、不可变历史；这是独立数据库验证，非Spring全链路。
- 新增后端回归：幂等/同键异参、精确输入/模型绑定、实际PNG颜色、非有限/超界几何、结果ID错配、原子审计失败与恢复、模型/QC/资格撤销、损坏实际私有产物、取消/迟到工作阻断、并发准备、HTTP认证/CSRF。完整运行待CI。
- 新增真实服务E2E：沿用真实申请→扫描/QC→模型契约→T35任务链，生成并读取真实结果，核验PNG/hash/像素、OSD叠加、显隐、模型撤销清空与跨范围404。本地仅完成发现，不冒充执行。
- 新增浏览器UI：有界PNG真实像素、截图前后差异、透明度/混合、旋转翻转裁剪、取消、损坏、撤销、A→B迟到响应。显式合成HTTP夹具，与真实服务E2E分列。
- 初轮新UI控件问题：Slider须用ariaLabelForHandle，双选项Select关闭虚拟化保证真实选项可访问；补充具体失效错误映射后修复。新矩阵双镜像深度精确相等遇IEEE754约1e-13舍入差异，改为明确1e-10像素数值断言；旧断言/门槛未降低，初次失败输出保留。
- 全套UI首次发现QC撤销后历史面板重复：新结果组件与既有ROI历史组件使用相同manifest React key。已给结果组件独立key命名空间，并新增QC撤销后只保留一个历史面板的回归；保留初次失败日志，重新执行全套。
- 最终本地前端检查数量及状态见证据目录；完整后端Maven仍因离线BOM缺失未到编译/测试，详见backend-offline.txt。完整CI由父会话核验最终SHA，通过前不合main。

截图为实际Chromium渲染：`synthetic-overlay.png`可见强度格与中央矩形叠于原图；`synthetic-overlay-rotated-flipped-cropped.png`可见旋转/镜像后的中央裁剪，均已视觉检查。仅合成视觉夹具，不证明诊断、真实WSI、颜色保真或临床测量有效。
