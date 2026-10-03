# T29 扫描导入：接口与交付记录

用户授权的合成开发规格见[PRD](../prd/development-scan-import-v1.md)，格式及租约设计见[ADR0019](../adr/0019-synthetic-scan-import-leases.md)。未配置真实设备或厂商适配，未实现数字QC发布。

## 接口

`/api/requests/{requestId}/scans`，会话/CSRF沿用现有安全策略，不接受URL或文件路径。

- GET `?page=1`：分页20、最多50页，返回当前病例/患者、物理玻片/条码/源QC、导入状态及失效原因、真实能力矩阵。先授权，随后有界查询；列表无逐项N+1读取。
- POST：Idempotency-Key与items数组1–10项，每项confirmedCaseId、patientId、slideId、objectId、expectedHead、previousId、人工barcode、SYN-来源/设备编号及reason。逐项短事务，返回每项objectId/status/scanId/code，201仅登记，QUARANTINED也不是有效数据。首版expectedHead=-1/previousId=null；重扫须最新前任务和新原件。
- GET `/{id}`：读取精确任务；人工声明身份与权威归属分别保存，不把错配悄悄改正。
- GET `/{id}/events`：最多50项追加历史，包含当时状态、错误、尝试代次、人工原因；同资源授权及访问审计。
- POST `/{id}/{CLAIM|PROCESS|RECOVER|RETRY|CANCEL}`：Idempotency-Key、expectedVersion、leaseId、reason。CLAIM只领QUEUED；PROCESS仅同演员当前未过期代次，内部读取T28确切对象并解析；RECOVER只处理过期租约；RETRY有次数、退避及源状态限制；CANCEL追加取消历史，不删除对象或产生新扫描。

业务幂等键每用户/医院/操作域隔离，同键异参拒绝。批次使用原键加索引，重复整个批次保留已成功项，失败项再次按原输入校验；不得排序或改变原批次后冒充原请求。每项业务、事件和审计同事务，文件I/O位于事务外。重放完成回执不激活已取消任务。

固定合成夹具可通过测试中的ScanFormat.fixture生成，使用T28接口保存；前端仅引用已保存原件UUID，不读取真实患者文件。真实E2E创建直接制片及材料QC，再生成256字节带身份合成头并经T28暂存/完成后导入。没有ZIP导入、远程URL或任意解析器路径。

## 本地证据及缺口

- 真实PG17 `python3 backend/src/test/probes/scan-postgres.py`：V1–V27、精确源约束、不可变对象/事件、完成回滚、取消后旧CAS和并发版本头通过。首次发现V27表达式括号问题，修复后通过。
- `java backend/src/test/probes/ScanProbe.java`：实际编译格式/租约策略及条码器；合成身份、TIFF仅未配置、ZIP/路径/输入上限拒绝通过。未使用真实厂商样本。
- Java AST语法/局部作用域/HTTP helper受检异常扫描通过；不等同完整后端类型编译。
- 前端145项单测通过；58项全套UI通过，随后4项T29收尾回归通过（含1项新增，因此未声称59项全套重跑）。lint、类型检查及构建通过。UI先发现虚拟选项不可操作与队列脏状态错误，修复实现后原断言复测，不跳过测试。
- 已补真实Spring/PG测试：精确绑定、双方身份错配、取消迟到结果、重扫、审计回滚、并发领取、QC失效、逐项部分失败、权限撤销、冻结时钟三次耗尽及HTTP CSRF/白名单。
- 离线Maven verify在解析Boot4.1.1模型时失败，缺少Zipkin3.5.3、Brave6.3.1等BOM；未再尝试曾拒绝的下载。完整后端编译/测试、真实E2E运行及应用重启故障注入仍未本地验证。
- Playwright已发现42项真实E2E场景（含新增扫描来源场景），发现不是执行。完整CI交由父会话按提交SHA核验，不能凭部分本地检查合main或投产。

本次仅增加validation/t29的现有CI push触发，不减少作业、断言或范围。main保留T28已验证树7eb4975e9062e6d2ecc0a5fe4f2277e5a2845aed。T29独立中文提交到验证分支，未绿前不合main。
