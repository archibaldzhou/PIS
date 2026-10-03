# T28 私有合成原件：接口及交付证据

2026-10-03。开发范围、默认关闭及生产阻塞见[PRD](../prd/development-storage-v1.md)与[ADR0018](../adr/0018-private-immutable-original-storage.md)。未验证内容不合main，不用于临床。

## 协议

共同前缀 `/api/requests/{requestId}/storage`。所有接口都重新校验合成主体、启用组织READ、独立存储资格、病例范围、有效期和身份隔离。未授权对象统一404；未认证401，写操作无/无效CSRF为403。所有读写、恢复及dry run审计；文件不入关系表。

- `GET ?page=1`：每页20，最多50页，保留确切版本/root ID。不同根的旧元数据可追溯，不能映射到新根同名文件。
- `POST`：Idempotency-Key，assetId可空；expectedHead=-1表示新原件；非空asset需确切头序号。confirmedCaseId、byteSize、sha256、purpose=SYNTHETIC_ORIGINAL、mediaType=application/octet-stream均明确。201回执含独立原件version ID；同键异参409，重放重新授权。
- `GET /{id}`：当前确切元数据状态、序号、CAS版本、哈希及大小。
- `PUT /{id}/bytes`：CSRF、Content-Type=application/octet-stream、确切Content-Length及X-Storage-Version。不接受chunked未知长度。首次RESERVED以CAS进UPLOADING；输入完成校验后STAGED。失败留FAILED/UPLOADING；重复PUT不能覆盖暂存，先查状态或恢复。
- `POST /{id}/finish`：Idempotency-Key及confirmedCaseId/expectedVersion。FINALIZING任务与命令原子入库，事务外创建原件，READY及审计原子提交。未知结果沿用原键和原始expectedVersion。
- `POST /{id}/reconcile`：人工核对UPLOADING完整暂存并推进STAGED/FAILED，或核对FINALIZING/READY完整原件。并发变化409；部分原件不重写。不是外部存储探测或临床就绪操作。
- `GET /{id}/bytes`：X-Storage-Purpose=PREVIEW或DOWNLOAD；单Range `bytes=start-end`最多1MiB。不含Range仅允许总长≤1MiB。全件SHA再次验证，读取前预留预算/审计尝试，读取后重新授权/审计。206含确切Content-Range；416拒绝不支持范围；429拒绝超预算。no-store/nosniff及受控UUID附件名，无输入路径、URL、查询串秘密。
- `GET /capacity`：独立容量资格。仅返回当前配置root所在卷total/usable、DB预留、开发限额及测量时间。未配置为null，不伪造零容量。
- `POST /cleanup-dry-run`：写及容量资格；最多100个本人/本病例/当前根/FAILED暂存候选，明确dryRun=true/NO_FILES_DELETED。无删除端点。

V26有8个无种子元数据表；不可变版本/资产身份、追加事件、唯一asset+ordinal、明确完成状态及同病例组织外键。最新迁移契约显式26、序列1–26；原checksum/重复执行/种子断言保留。

UI只生成64KiB或2MiB确定性夹具，按当前病例、原件头及原始幂等键保存意图。取消仅停止等待；明确确认后可停止本地跟踪，但不撤销服务器记录或释放预算。旧版本独立预览，>1MiB的按钮明确下载前1MiB片段。没有患者文件选择、S3配置、WSI解析或扫描仪操作。

## 本地执行与边界

- 前端 lint、TypeScript、构建及140项单测通过。构建保留既有大chunk提醒，未提高阈值。
- 全套54项UI通过；新增停止跟踪确认后，T28定向4项复测通过（其中3项为复测、1项为新增）。
- `python3 backend/src/test/probes/storage-postgres.py`：真实PG17 V1–V26、无bytea、不可变版本/历史、完成事务回滚、两连接配额竞争、版本头CAS通过。不是Flyway/Spring整套测试替代。
- `java backend/src/test/probes/CompileStorageProvider.java /tmp/pis-t28-classes` 后运行 `java --class-path /tmp/pis-t28-classes backend/src/test/probes/StorageProviderProbe.java`：实际provider编译及2MiB流、SHA/Range、容量、关闭重开恢复、子JVM突然halt后锁恢复与部分原件拒绝覆盖、符号链接拒绝、独占root、并发publish通过。临时类目录须先自行建立。
- Java AST语法、局部作用域、HTTP helper受检异常检查通过；仅源码检查，不是完整类型编译。
- 离线Maven verify尝试在模型解析阶段失败：本地缺少Boot4.1.1导入的Zipkin3.5.3、Brave6.3.1、JUnit6.0.3等BOM。尊重此前下载拒绝，不重试下载；完整后端编译、JUnit及Spring/真实HTTP集成尚未本地运行。
- 真实E2E新增实际登录、提交接收、2MiB上传、幂等完成、全件分段hash、真实UI预览及另一账号404。发现41项真实E2E场景；只完成源码/发现检查，后端无法启动，未声称E2E执行通过。
- 未做应用进程故障注入、断电、真实S3、生产慢连接/负载或真实WSI链路验收。已运行的是provider关闭重开、子JVM突然halt及文件/数据库独立故障探针；不是完整Spring应用重启验收。

仅增加validation/t28的现有CI push触发，不改变作业、测试范围或安全门禁。由父会话读取确切SHA CI；本地通过不是合并或生产批准。main保持已验证T27树510856a484e616e76abba5334d49ab31f946dbe3。
