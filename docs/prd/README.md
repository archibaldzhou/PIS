# 需求来源与批准登记

本目录登记业务依据及未确认项，不将原型或评审稿自动视为批准 PRD。

用户后续授权“如果缺少 PRD，你自己完成”，因此新增[开发版申请与接收 PRD](development-accession-v1.md)，独立定义合成开发规则，不再等待原 PRD 才能继续实现。下表保留原文件访问证据；医院投产批准仍不能由开发规格替代。

| 来源 | 版本/本次访问状态 | 批准状态 | 责任角色与下一步 |
| --- | --- | --- | --- |
| 用户 AGENTS(1).md | V0.1；父会话转交完整文本，T01 已适配为根 AGENTS.md | 工程约束按用户任务采纳；不是临床批准 | 工程负责人维护 ADR/门禁；具体人员待指定 |
| 病理系统_招标与PRD及全部原型_精简附件_V0.1(1).zip | 文件名标注 V0.1；本执行环境尚未取得可读文件，内部版本/页数/内容未核验 | 待确认，不把文件名或原型当作业务批准 | 产品/病理业务负责人确认有效版本及批准范围；具体人员待指定 |
| 用户直接上传 02_全部UI原型.zip | 2026-10-02 当前环境成功下载并验证；47 张页面 PNG 与 manifest 摘要全部一致，已实际查看 UI-002/003/004/005/007/008/009/010/011/012/037 及角色图例 | 静态合成演示，非临床批准；不含 PRD 正文/AC 验收条款 | 视觉消费阻塞已解除；规则仍需核对 PRD，见[原型对照记录](../api/t08-ui-review.md) |

## 继续 T08/T09 前的门槛

1. 当前消费环境经受支持流程取得 ZIP，验证可读，登记其中 PRD 的实际版本、条目和审批状态；不得从其他环境猜路径或绕过拒绝。
2. 实际查看申请登记/查询/编辑/提交，以及标本接收/核对/退回/异常对应原型图片，记录页面与需求对照。界面展示不等于业务状态机/权限已批准。
3. 结合现有核心模型和授权说明确认申请归属、来源/编号、状态转换、患者/就诊一致性、接收数量/身份核对、异常及退回原因、角色范围和合成验收数据。未知临床语义列阻断，由业务负责人确认，不自行填默认规则。
4. 新流程需有 DTO/接口契约、版本与授权原子校验、审计/幂等/并发测试；真实 PG 迁移及权限基线不得退化。只开发和验证合成数据范围，不宣称临床可用。

医院部署、临床签名、角色/资格制度、AI 用途与验证责任、容量/SLA、保留期限仍需各自负责人批准；当前没有指定人员、批准日期或批准记录，不代填。

后续开发规格：[T10 标签](development-labels-v1.md)、[T11 取材](development-grossing-v1.md)。均为合成开发依据，不替代医院批准。

[T12 技术任务与交接开发规格](development-processing-v1.md)定义合成演练边界，真实设备与T13实体未包含。

[T13 材料身份与重切开发规格](development-materials-v1.md)定义常规及明确直制的合成登记，未知临床路线保持不支持。

[T14 技术QC、隔离与返工开发规格](development-quality-v1.md)要求确切版本、追加判定和隔离消费门禁；医院审批规则未批准，异常放行持续禁用。

[T15 工作列表、批量领取、合成超期与追踪开发规格](development-worklist-v1.md)仅聚合既有流程；当前页批量领取逐项独立校验。开发阈值不是医院TAT，未来模块不进入虚构统计。完整CI待核验。

[T16 诊断分配、领取与转交开发 PRD](development-diagnosis-assignment-v1.md)依据用户本次明确范围，已查看 UI-019；独立合成人员资格与材料QC门禁。无T17报告/签署，按最新指示仅本地开发和中文提交，不等待GitHub。

[T17结构化报告草稿开发PRD](development-report-drafts-v1.md)已对照授权UI-020实际图片；仅人工合成草稿，不含T18复核/签署/发送。

[T18合成复核与模拟签署开发PRD](development-report-review-v1.md)已对照授权UI-022，明确依赖快照与职责分离；不包含T19/T20、CA、发送或临床签署。

[T19固定合成PDF与打印记录开发PRD](development-report-output-v1.md)已对照授权UI-024实际图片。仅固定合成产物及打印请求/用户自报，不含实体打印、报告发送、T20更正或临床签署；架构见[ADR 0009](../adr/0009-fixed-synthetic-report-artifacts.md)。

[T20补充、更正与版本链开发PRD](development-report-amendments-v1.md)已对照授权UI-023实际图片。新草稿重新复核/模拟签署，原冻结报告/PDF保留，下游仅待替换；不含T21送达、ACK或真实临床签署。架构见[ADR 0010](../adr/0010-append-only-report-amendment-chain.md)。

[T21本地合成投递开发PRD](development-delivery-v1.md)已查看UI-024实际原型；仅本地outbox/inbox、完整业务ACK与对账，CA无真实签署能力。见[ADR 0011](../adr/0011-local-synthetic-delivery.md)。

[T22术中冰冻开发PRD](development-frozen-v1.md)已对照 UI-016/UI-025 实际图片。独立冰冻材料与工作流、人工时间、版本化草稿、合成复核、独立回读/确认及确切常规版本差异关联；没有真实通信或临床动作。见[ADR 0012](../adr/0012-independent-frozen-workflow.md)与[本地交付记录](../api/t22-development-status.md)。

[T23细胞学制备开发PRD](development-cytology-v1.md)已查看 UI-015 实际原型；独立标本/制备台账、直接涂片/液基/可选细胞蜡块、守恒的合成份数及来源QC失效传播。不含临床分类或自动诊断，见[ADR 0013](../adr/0013-cytology-preparation-lineage.md)与[交付记录](../api/t23-development-status.md)。

[T24特殊染色/IHC开发PRD](development-staining-v1.md)已查看UI-013/017/018实际原型。单病例冻结成员、明确人工对照、版本化技术结果、新身份来源链及失效门禁；无厂商方案或临床推荐。见[ADR0014](../adr/0014-synthetic-stain-batches.md)和[本地交付记录](../api/t24-development-status.md)。

[T25院内会诊/复阅开发PRD](development-consultation-v1.md)已查看UI-021实际原型，限定病例、目的与期限，汇总需人工处理及明确确认；非签署采纳保留原草稿。见[ADR0015](../adr/0015-case-scoped-consultation.md)及[交付记录](../api/t25-development-status.md)。

[T26人工归档/借阅/盘点开发PRD](development-archive-v1.md)已查看UI-026/027实际图片，区分人工登记和未验证的物理事实，保留确切报告产物与不可变盘点历史，无销毁或外部分享。见[ADR0016](../adr/0016-manual-archive-custody.md)和[本地交付记录](../api/t26-development-status.md)。

[T27工作量/TAT/QC统计PRD](development-statistics-v1.md)已查看UI-028/031实际图片。授权优先、固定快照、自然时长、开放病例单列及来源下钻，不包含CSV或对外投递。见[ADR0017](../adr/0017-authorized-statistics-snapshots.md)及[接口与验证记录](../api/t27-statistics.md)。

[T28私有原件存储PRD](development-storage-v1.md)已查看UI-042实际图片。本地私有root、不可变版本/摘要、流式暂存、完成对账、权限与容量；S3未配置，仅合成字节，非WSI验证。见[ADR0018](../adr/0018-private-immutable-original-storage.md)及[接口与验证记录](../api/t28-storage.md)。

[T29扫描导入PRD](development-scan-import-v1.md)已查看UI-038实际图片。确切玻片/原件绑定、双向身份、合成头能力白名单及有界尝试/重扫；真实厂商适配未配置，T30前不发布。见[ADR0019](../adr/0019-synthetic-scan-import-leases.md)与[接口/验证边界](../api/t29-scan-import.md)。

- T30：[数字扫描QC开发PRD v1](development-digital-qc-v1.md)，用户授权合成开发；医院专业规则和真实WSI验收未批准，开发负责人为当前任务实现者。实际UI-039已查看；版本绑定与消费门禁见ADR0020。

- T31：[合成瓦片阅片PRD v1](development-tile-viewer-v1.md)，已查看UI-040合法实际原型，仅合成几何图；[ADR0021](../adr/0021-bounded-synthetic-tile-viewer.md)及[接口/验证边界](../api/t31-viewer.md)。

- T32：[ROI、测量及双视图PRD](development-roi-v1.md)，已查看UI-041实际原型，用户授权合成开发，未获医院校准或临床验证；[ADR0022](../adr/0022-versioned-roi-pixel-coordinates.md)与[接口/交付记录](../api/t32-roi.md)。

- T33：[阅片可测量验证PRD](development-viewer-validation-v1.md)，用户授权合成开发验证，已查看UI-041及实际阅片截图；[兼容性矩阵、可复现命令与交付边界](../api/t33-viewer-validation.md)。无临床/厂商兼容批准。

- T34：[AI注册与适用契约PRD](development-ai-registry-v1.md)，用户授权合成开发，已查看UI-043实际原型；[ADR0023](../adr/0023-synthetic-ai-registry-contract.md)、[接口和验证边界](../api/t34-ai-registry.md)。无模型安装/运行、临床或监管批准。

- T35：[持久化合成契约任务PRD](development-synthetic-worker-v1.md)，用户授权合成开发，已查看UI-044实际原型；[ADR0024](../adr/0024-persistent-synthetic-contract-worker.md)、[接口/交付与验证边界](../api/t35-synthetic-tasks.md)。默认关闭，不授予临床执行许可，无模型推理。
