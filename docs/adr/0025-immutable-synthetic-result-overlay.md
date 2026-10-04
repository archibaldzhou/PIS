# ADR0025：不可变合成结果包与逐版本阅片叠加

状态：用户授权 T36 合成开发，非医院/临床批准。延续 ADR0024；查看已授权 UI-045 实际 PNG 后编写开发 PRD。

只消费当前仍有效的 T35 `SYNTHETIC_SUCCEEDED` 技术产物。新 result UUID 固定任务版本/代次、源技术产物 ID/hash、医院/物理玻片/身份版本、扫描/对象/manifest/QC发布版本、模型 digest、预处理/配置摘要、运行时及生成器版本。task+generator 唯一，不覆盖已有结果；不存在模型执行、诊断概率或自动报告采纳。

`SYN-OVERLAY-1` 从合成技术产物摘要取16个字节，形成4×4强度格和固定中央矩形。JDK ImageIO 实际生成64×64 PNG；严格 `SYN-RESULT-1` JSON包连同这些确切PNG字节、几何和版本进入T28私有不可变存储。PG只保存绑定/摘要/状态和对象引用，不保存图像字节。上限64KiB包、24KiB PNG、1瓦片、4矩形、有限输入尺寸及坐标。无外部URL、HTML或图像格式回退。

数据库与文件系统无法共同提交：PREPARE 与幂等回执/审计原子，固定期望摘要；T28稳定键完成暂存/finalize，最后在当前资格锁、CAS和原子审计下把BUILDING改READY。失败保留不可消费BUILDING；用原命令键或确切result ID恢复同一产物。已冻结生成器/运行时不同或字节不一致不能重新生成并伪称旧版。READY包只读，撤销不删除历史。

每次metadata/PNG读取重新检查T35发起人及资格、病例/医院/资源、当前QC/扫描/模型/身份，并验证T28包及PNG摘要。通用storage列表仅展示原件；通用HTTP详情、上传、完成、对账、字节读取均不能绕过结果域访问受保护产物。缓存private/no-store，PNG由同源有界服务读取；前端只持当前组件的有界Blob，清理时revokeObjectURL。沿用T28每人每分钟60次/32MiB读取门槛；过限失败清空，不调高限额。

真实OSD上的SVG image/ROI使用规范图像像素，通过当前视口矩阵投影；处理旋转、屏幕水平翻转及裁剪。实际查阅固定OSD 6.1.1源码确认 `imageToViewerElementCoordinates` 不包含viewport flip，因此显式补充屏幕X镜像，并与ROI共享该数学函数。数值往返使用1e-10像素开发测试容差；不表示物理/临床准确性。

变换、显示设置或对象改变先隐藏图层，当前资格验证完成后才显示。已交付像素无法服务端收回，浏览器每5秒复核、请求上限10秒，失效/超时清空；基图QC保持原有2秒复核。前端不把这一有限刷新窗口宣称实时撤权。所有迟到响应检查AbortSignal，卸载清理请求、事件与URL。混合normal/multiply、透明度、显隐仅作用合成强度，非疾病风险。

无新增依赖或许可：JDK ImageIO沿用现有JDK（GPLv2+Classpath Exception），OpenSeadragon沿用固定6.1.1（BSD-3-Clause）；React/AntD及Jackson沿用既有锁定版本。不下载权重、厂商样本、远程字体或外部模型。

真实AI诊断验证、真实WSI与显示器色彩校准、医院监管/临床批准、T37人工采纳均未实现或未获批准。默认合成模式关闭，临床executionAllowed=false不变。
