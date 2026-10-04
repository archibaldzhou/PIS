# T39 CI：存储 admission busy 与并发测试契约

基线53fbce14f0c27eeb78b48f611915ca418de51012，validation/t39，同一原环境，起始工作区干净。父会话提供 CI37188366662 / verify111395118670：508项后端测试0 failure、1 error；架构测试已通过，LocalStorageProviderTest第19行未处理STORAGE_BUSY。main及verify的30分钟预算不修改。

## 根因和证据边界

LocalStorageProvider的根目录FileLock用于防止第二个provider占用同一root；本测试只创建一个provider。其bounded方法另使用两个工作线程、SynchronousQueue零排队和AbortPolicy，提交拒绝即STORAGE_BUSY。StorageService将该状态映射429，finalize保留FINALIZING/PENDING，只有实际publish核验成功后才能转READY；没有把busy算成功。

原并发测试的两个Callable捕获所有IOException，但两者结束后的直接publish和read未处理合法的admission busy。Future完成不保证执行线程已回到SynchronousQueue接收点，尤其两个线程刚完成任务时。给出的CI单行定位不能进一步区分最后publish还是read；fresh provider的首次stage不会遇到已占满的两个槽，close也不产生此Failure代码。没有证据支持根目录锁泄漏，故不改生产执行器、锁、吞吐限额或状态机。

在本地实际provider按原结构运行200轮，未复现偶发尾部busy（before.txt，准确记录0次）；不能把这当作CI未发生。独立受控测试用两个有闸门的真实stage输入占住两个I/O线程，确认两者都已进入后再publish，稳定得到BUSY且原件不存在、暂存与目录清单不变。保持占用时，有界重试耗尽仍抛出STORAGE_BUSY；释放闸门并join后，原始bytes/hash、全量分段回读及重放完全一致，输入关闭，provider关闭后同root可重新取得并准确读取。

## 修复

- 保留原JUnit测试名称和两个真实并发publish；三方CyclicBarrier起跑，分别等待两个结果，至少一个必须实际PUBLISHED。BUSY只算拒绝；竞争中的另一个调用若看见CREATE_NEW未写完的原件，STORAGE_INTEGRITY仍按既有fail-closed契约算拒绝，不算成功也不重试。其他IOException不再被笼统吞掉。
- 两个并发结果都已完成后，对发布/读取只捕获STORAGE_BUSY进行明确有界退避：最多32次、总预算2秒、1ms起逐步增加且单次不超过100ms。等待只发生在已收到busy之后，不用sleep建立并发、不盲重试其他错误。耗尽抛出原Failure。
- 新增稳定饱和拒绝/无部分写/资源释放/重试与耗尽回归，以及非busy不重试回归。不同payload必须STORAGE_INTEGRITY，原始文件和准确回读hash/bytes均不得改变。
- 共享无第三方依赖的StorageConcurrencyContract由JUnit及独立probe共同执行。原独立probe的无屏障并发片段也改用该契约，仍保留2MiB真实并发、流式/Range/容量/符号链接/根独占/部分原件拒绝和子JVM突然halt后恢复；并未串行化整个场景。

## 本地执行

实际JDK ToolProvider编译生产provider、policy、S3接口及共享并发契约成功（沿用CompileStorageProvider入口；环境无单独javac命令，未下载工具）。

```
mkdir -p /tmp/pis-t39-storage-fix-classes
java backend/src/test/probes/CompileStorageProvider.java /tmp/pis-t39-storage-fix-classes
java -cp /tmp/pis-t39-storage-fix-classes com.pis.storage.StorageConcurrencyContract
java --class-path /tmp/pis-t39-storage-fix-classes backend/src/test/probes/StorageProviderProbe.java
```

- 100轮真实provider屏障并发 + 稳定双槽饱和/耗尽/恢复、精确hash/bytes、不同payload拒绝通过：provider-contract.txt。
- 2MiB provider全场景与独立子JVM崩溃/锁恢复通过：full-provider.txt。这不是Spring应用重启/HTTP E2E。
- Java源码解析/词法作用域/受检异常检查通过；加强后的13域源码依赖无环通过：java-source.txt、boundary.txt。
- 离线Maven verify仍被缺失BOM阻断：maven-offline.txt；完整JUnit、Spring后端、真实服务E2E和本修复完整CI未运行/未验证，交由父会话核验。
- 原场景复现脚本保留于StorageBusyReproduction.java.txt，需复制为同名.java并使用上面的类目录运行；只建立自身/tmp合成夹具。

不修改生产代码、迁移、既有安全契约、workflow、Actions或凭据；不重试已拒Actions，不合main，不改写既有历史。前端未改，本次不重复UI且不将独立provider结果冒充全链路。
