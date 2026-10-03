# ADR 0021：有界合成RGB瓦片与OpenSeadragon

日期2026-10-03；仅授权开发，承接T28–T30，不表示真实WSI验收。

选择官方npm `openseadragon` 精确6.1.1，BSD-3-Clause，内置TypeScript定义；依赖与integrity冻结在package-lock.json，前端npm ci，本地打包，无CDN、远程图标或字体。[官方项目](https://openseadragon.github.io/) / [API](https://openseadragon.github.io/docs/OpenSeadragon.html)。不新增后端依赖：JDK标准ImageIO仅编码自己构造的有界BufferedImage，显式MemoryCacheImageOutputStream，无未知文件解码/脚本/URL读取。

采用显式PISRGB1合成原始RGB格式，最大512×512，保留T29身份头、UUID和条码。旧PISSCN1仅头格式不制造像素，SVS/NDPI/MRXS/TIFF真实解析仍未配置。最近邻缩小用于几何测试，不宣称临床质量；T32未知校准，不提供物理尺度或测量。

V29保存不可变确切来源及金字塔清单；实际PNG由本地生成器产生，在内存私有LRU缓存（最多8项、16MiB），不将图像塞入PG。缓存键含医院/申请/玻片/扫描版本/原件及hash/发布版本/用户及authVersion，生成器版本在清单中固定。每次缓存读取仍重新执行T30当前权限/QC/来源门禁，响应前再次检查，私有no-store。重启后重新生成并与不可变清单完全比较，结果变化即拒绝，不悄悄更新清单。替代方案外部瓦片服务或真实WSI解码器尚无代表性样本和批准配置，未采用。

生成最多2并发，无排队；每片检查5秒截止和中断，单瓦片128×128；单源小于1MiB。每用户每分钟240次/32MiB数据库原子预算；坐标仅有界整数。字节返回受当前授权与原子审计控制；准备清单是显式CSRF/幂等命令，计算在短事务之外，提交重新核验，审计失败回滚清单。

浏览器OpenSeadragon真实canvas、导航图、键盘/平移/缩放/适配。每个image job使用受控同源fetch，验证大小、PNG magic和hash；4并发、10秒超时、32瓦片内存缓存。切图/取消/卸载取消请求并销毁实例、画布与事件；任何瓦片错误停显，人工重试重新鉴权。浏览器已收到的像素不能远程撤回，界面明确2秒定期授权核验，交互也核验；新服务器请求绝不复用授权决定。该边界不等于离线阅片支持。
