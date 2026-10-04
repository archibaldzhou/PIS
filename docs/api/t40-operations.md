# T40 交付：受限运维与隔离合成恢复

开发授权见 [PRD](../prd/T40-operations-recovery.md)，架构见 [ADR0030](../adr/0030-restricted-operations-recovery.md)，恢复命令与边界见 [runbook](../runbooks/t40-synthetic-recovery.md)。无新增依赖、凭据、外部服务、部署或生产安全设置修改；既有合成默认关闭与临床执行禁用不变。

## 实现

`GET /api/requests/{request}/operations`：既有当前账号/auth_version和资源范围，再核验新增 V37 独立、限时、可撤销 SYN-OPS-1 资格。管理员无默认权限。5秒 REPEATABLE_READ 快照，授权锁与读取审计同事务；无写业务/导出/恢复接口。no-store。返回本申请 T39 队列待处理/失败计数、READY对象数及最近20项直接申请审计（固定元数据，不含正文、账号或文件路径）。没有全院审计权限。容量只来自实际根文件系统；不存在或故障为 NOT_CONFIGURED/UNAVAILABLE+null。当前实例恢复永为 NOT_VERIFIED，加密和异地 NOT_CONFIGURED；不借脚本演练伪造当前实例健康。

安全启动检查拒绝危险 Flyway 开关及管理端点/健康细节公开覆盖。结构化 HTTP 日志只有固定事件、服务端UUID关联ID和状态，无 URL、参数、头、请求体或异常正文。不是合规持久安全审计替代。

`python3 scripts/synthetic-recovery.py`：实际PG17 dump/restore与私有对象，完整迁移哈希、数据库/对象 SHA256、根与对象身份绑定；只接受脚本自建隔离合成源，拒绝所有外部参数和现有恢复目标。实际连接中断回滚与隔离、恢复后全表摘要/身份/幂等唯一性/审计/对象精确引用检查。全部原件保持不变，无用户目录删除。CI增加3分钟有界步骤，verify总预算仍30分钟，原有必检项、固定Actions与权限不变。

## 验证与边界

原始命令输出在 [evidence/t40](../evidence/t40)。前端208单测、lint、构建通过（现有大bundle警告仍在）。全套116项UI通过，收尾4项运维专项复测通过（包含随后新增离开页面迟到响应检查）；新增运维UI受控取消/迟到响应、拒绝、未知与失败展示；真实截图 `operations-ui.png` 已查看。实际PG17隔离恢复：37迁移、145表、2,097,177字节固定对象、10项拒绝场景与真正恢复连接中断回滚通过。原有PG17并发/回滚、provider二进制/并发及源码依赖环检查通过。Java语法/作用域/throws检查通过，但均不是完整类型编译。

本地 Maven offline verify **未通过模型解析**：缺失Zipkin/Brave/JUnit等BOM；完整后端编译、真实HTTP集成及真实服务E2E未在本地执行。真实E2E仅发现47项/28文件，扩充现有adapters场景，未新增共享账号登录；实际执行由父会话读取CI。未把mock UI或独立PG/provider作为完整服务证据。

首次UI取消夹具只延迟StrictMode首请求，被第二次初始化请求完成；已改为延迟全部未释放请求并明确取消/释放/重读，不关闭StrictMode、不加盲睡眠。恢复表比较初版依赖 UNION 返回顺序，已按表名排序比较，保留所有表内容摘要断言。脚本实际故障注入使用锁状态屏障，不靠等待时间假定成功。

仍未实现：生产在线一致备份、加密/密钥管理、异地备份、实际医院恢复/RPO/RTO、角色权限恢复后的生产验收；没有任何临床、监管或合规认证结论。T40等待独立验证分支完整CI，不进入main。
