# T39 本地合成医院接口合同 PRD v1

用户授权开发；非医院接口批准。已读取AGENTS与T21出入箱、身份和幂等契约，实际查看授权UI-032接口消息与重放PNG。采用来源/关联/摘要、明确状态、错误、重试及人工确认，不复制原型虚构成功数量。

仅自定义SYN-HOSPITAL-1本地synthetic adapter，不声称HL7/FHIR/厂商兼容。HIS病例关联记录、收费请求登记（不是支付）、设备观察登记（不是QC/设备动作）使用严格DTO和持久化本地消息/出入箱/合成接收台账；不自动登记或改变临床申请/报告状态。EMR固定报告复用T21真实report_delivery/outbox/inbox和不可变artifact契约，通过适配路由调用同一service，不复制报告发送机制。实际HIS/EMR/收费/设备/HL7/FHIR均NOT_CONFIGURED，无URL、密钥、外呼或标准解析。

来源限定当前workflow_scope.sourceId，人工登记的消息精确绑定当前医院、申请、病例和患者；DEVICE另核对材料同病例。独立SYN-ADAPTER-1资格grant和workflow范围，管理员无隐式权限。字段白名单、8KiB传输上限/4KiB规范消息上限；外部关联key和code仅SYN标识，不收路径/URL/任意JSON。note/reason有界合成文本，不进日志。

新消息与outbox/事件/审计同事务；source+adapter+externalId唯一，同输入返回同消息，异参冲突。按病例/adapter序号1–1000验顺序，仅收件持久化成功才形成合成台账（唯一message）。RECEIVE不等于ACK；ACK精确匹配消息digest/source/attempt，HTTP200不表示临床送达/支付成功。ACKED后独立RECONCILE检查入箱和台账。

CLAIM30秒租约，T21 DeliveryPolicy限制3次及5/20秒退避。TIMEOUT/FAIL有限重试，POISON/耗尽DEAD；错顺序NACK→REJECTED。人工REPAIR仅当前前序到齐且未耗尽时允许重试，不能改payload、重置计数、伪造ACK。取消不撤销已记录本地事实，旧代次/迟到ACK不能复活。ACK丢失可在新租约重放RECEIVE，唯一入箱/台账不重复。持久状态跨重建服务保持，可由TIMEOUT恢复过期尝试；完整应用重启如不能运行明确列为未验证。

UI在授权申请下展示三类队列、NOT_CONFIGURED、attempt/错误/租约、合成已记录与ACK/对账区别；原键重试、显式确认、取消等待/脏表单、切申请和迟到响应清理。EMR使用原本地投递面板/适配别名，不外发。后端/PG/HTTP/UI/E2E验证身份、乱序、重复、超时、回滚、撤权/取消；不以mock替代真实服务。
