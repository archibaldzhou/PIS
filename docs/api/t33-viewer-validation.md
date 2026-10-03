# T33 阅片验证与边界

2026-10-03，合成开发验证。没有新增临床功能、厂商解析器、数据库迁移或依赖。T32基线按父会话完整CI确认后快进main，远端为a75d11f5757803c034acc662ea87544f62f6d7bb；T33仅验证分支，完整CI待父会话核验。

## 兼容性矩阵

| 输入／路径 | 固定版本和实际覆盖 | 结果／边界 |
| --- | --- | --- |
| PISRGB1原件→PNG金字塔 | SYN-RGB-PYRAMID-1；512×384、RGB8、变体0/1；24瓦片/变体，0–9级 | 本地PASS；每变体262145个解码像素逐点比对独立公式，精确层级坐标与边缘尺寸；hash见provider-run-1.txt |
| PNG→真实浏览器 | OpenSeadragon6.1.1、Chromium151.0.7922.173、Canvas2D；1280×900与1024×768 | 实际PNG渲染、固定RGB三色、六次A/B切换、双视图、关闭清理；HTTP使用合成夹具，非真实服务E2E |
| ROI几何／测量 | T32规范像素坐标，旋转/翻转/缩放/平移/裁剪往返；新增三角形X=.25、Y=2、100轮往返 | 本地数值PASS；面积24、周长19+sqrt(265)，反向顶点顺序一致；合成校准不是临床准确性 |
| PISSCN1无像素头 | 独立实际提供者拒绝检查及既有后端用例 | UNSUPPORTED供阅片；本地提供者拒绝PASS，完整后端未运行，不把头解析算图像支持 |
| TIFF / SVS / NDPI / MRXS | 无当前已配置解码器、无代表性真实样本 | NOT_CONFIGURED；厂商格式兼容UNTESTED |
| OpenSlide | 本环境openslide-show-properties不存在 | NOT_CONFIGURED；未下载额外工具或样本 |
| 归档ZIP、外部URL | 当前无阅片提供者 | UNSUPPORTED；没有回退到任意解析/远程读取 |
| ICC／色彩管理 | 原件只接受本地RGB8合成格式；JDK输出无iCCP，验证所有PNG chunk和RGB通道 | 不支持输入ICC转换；Canvas/操作系统色彩管理、显示器校准和临床色彩保真UNTESTED |
| GPU、真实大尺寸WSI、校准显示器 | 本轮Canvas2D、最大512维合成图 | UNTESTED；不外推真实WSI内存/吞吐/诊断能力 |

## 可复现命令和原始证据

在仓库根运行，使用锁定的既有工具链；见`docs/evidence/t33/environment.txt`。原环境Linux amd64，cgroup内存16GiB、CPU配额4核；Java探针另限128MiB堆、2工作线程。所有阈值仅开发回归门槛，不是医院SLA或生产容量。

- `bash backend/src/test/probes/viewer-validation.sh docs/evidence/t33`：两次独立JVM，每次32个任务，60秒批次上限、10秒线程收尾；每项仍受提供者5秒限时，取消后无遗留工作线程。保存两次原始输出和摘要。`provider-restart.txt`是本次输出；各内存池峰值之和不是同一时刻RSS，也不证明无堆泄漏。
- `npm --prefix frontend test`、`npm --prefix frontend run lint`、`npm --prefix frontend run build`：完整前端门禁。unit/lint/build/typecheck.txt保留结果。
- `PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui`：真实浏览器、合成HTTP；全套结果ui-all.txt。新增viewer-validation.spec.ts输出原始像素计数、请求数、画布数和截图；不将DOM稳定称为堆无泄漏。浏览器heap未可靠测量。
- `python3 backend/src/test/probes/viewer-postgres.py`、`python3 backend/src/test/probes/roi-postgres.py`：隔离PG17，仅合成关系约束/并发/回滚；viewer脚本读取全部V*.sql至V30，其既有末行仍写V1-V29，不代表仅跑29。不是Spring授权/HTTP验证。
- `java backend/src/test/probes/RoiProbe.java`：实际Java几何编译执行。Java源码语法/作用域/受检异常检查见java-source-checks.txt，仅源码检查，不是完整编译。
- `sh /tmp/pis-t08-maven/apache-maven-3.9.16/bin/mvn -o -Dmaven.repo.local=/tmp/pis-t08-maven/repository -f backend/pom.xml -B -ntp verify`：失败，backend-offline.txt记录缺失BOM。没有重试此前拒绝的下载。
- `PIS_TEST_DB_PASSWORD=Synthetic-discovery-only npm --prefix frontend run test:e2e -- --list`：仅发现44项；不是执行通过。本轮强化既有viewer真实E2E的PNG解码固定RGB与4并发私有响应，待CI。

## 缺陷、缓存与故障边界

已复现客户端先`arrayBuffer()`完整缓冲再检查长度：伪造声明尺寸的6.5MiB响应被耗尽，回归的取消断言失败，见stream-before.txt。修复为最多声明长度（最大100000字节）的缓冲，超限首块取消、分段正常读取、短体拒绝、AbortSignal中止待读。stream-after.txt为修复后7项通过。独立中文fix提交保留失败证据，未放宽magic/hash、授权或尺寸门禁。

并发计数从URL集合改为真实请求对象后，两个窗口均暴露双视图10个并发（见concurrency-before.txt）。根因是固定OSD6.1.1的导航图独立创建加载器，构造路径不传递主视图imageLoaderLimit。现以每个ImageView共享TileQueue约束主图与导航图合计4个执行、最多40个等待；双视图上限仍为8，没有提高回归阈值。排队取消立即移除，中止运行请求仍沿用AbortSignal；错误释放名额，队列不缓存授权。2项队列单测和3项真实浏览器定向回归通过。修复后两个窗口峰值均8，停止后待请求和画布均0，原始结果t33-browser-*.json。此修复独立中文提交，不改变服务端限流。

新增后端集成用例使用9个独立医院/病例/身份，验证LRU仅8项、16MiB上限、淘汰后重新生成相同字节、新服务实例空缓存读取持久manifest后精确重建、跨范围拒绝，以及两实例暖缓存QC撤销拒绝。它尚未本地执行，等待CI。已有逐瓦片/缩略图/清单权限、审计失败不返回缓存、当前版本、重扫和QC撤销用例全部保留。

浏览器故障覆盖缺失瓦片、坏PNG magic、显式重试、取消超限流、迟到A不得覆盖B、ROI迟到保存、ROI脏状态/软删除/校准版本、双视图不同清单不同步和QC撤销销毁画布；授权缓存不是永久许可。已合法交付像素不能远程收回，客户端以既有2秒刷新或后续交互检测失效，服务器每请求重新鉴权；未声称零延迟撤回。

提供者独立JVM重启摘要相同已验证。完整Spring应用重启、进程崩溃中的真实HTTP恢复、生产存储故障和GPU资源泄漏未验证，不能由探针或新服务实例替代。完整后端编译/测试及真实E2E因Maven缺失依赖未本地执行；T33 CI待核验。开发模式仍默认关闭，无真实患者/外发/部署/临床用途。

## 本次截图检查

人工实际查看`t33-dual-1280.png`、`t33-dual-1024.png`、`synthetic-roi.png`：两侧真实几何图及导航图可见，较窄窗口控件正常换行；旋转/翻转后的矩形、多边形、点叠加清晰存在，合成与未知校准提示保留。截图是实际Playwright运行产物，非绘制模拟截图。截图不能证明真实病理、显示器色彩或测量精度。

新增探针首次编写未知magic夹具时硬编码了错误偏移，实际破坏传输前缀，导致预期UNSUPPORTED与实际CORRUPT不符；原始失败`probe-fixture-before.txt`保留。已改为从显式传输标记长度定位magic，分别验证两种错误的精确边界；两次最终提供者运行通过。没有修改产品拒绝规则或接受任意异常。

## 最终本地结果

- 168项前端单测、77项全套UI回归通过；3项新增定向UI与2项队列单测通过，lint/类型/构建通过。Vite保留既有大chunk提示，未降低阈值。npm全部依赖审计0漏洞。
- 两次128MiB/2线程/32任务提供者、精确RGB/坏格式/非整幂边缘/取消检查及独立JVM摘要一致性通过。PG17瓦片与ROI探针、JavaROI策略及源码检查通过；原始结果未冒充后端全套。
- 完整后端编译/测试失败于离线依赖解析，缺Zipkin3.5.3、Brave6.3.1等BOM；真实E2E仅发现44项未执行，完整应用重启未执行。T33完整CI待父会话读取。
- 两项产品修复分别为3906923c49d4c817ba3f17975bb5f33d2292e4e1（有界读取）及1d336964373478c94427ffbf23c21616627375fd（共享并发队列）。验证与证据单独提交，开发模式默认关闭，无迁移/依赖/权限放宽。
- 原始日志、固定数据摘要、截图及逐文件SHA256在`docs/evidence/t33/`；最终代码未合main，不声明可投产或临床可用。
