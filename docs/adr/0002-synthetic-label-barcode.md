# ADR 0002：合成容器标签使用受限 Code39 预览

状态：T10 开发范围采用，2026-10-02。

不新增生产依赖，不升级Maven/npm栈。条码使用受限大写内容和Mod43校验，浏览器SVG生成黑白条带；不加载外部条码服务，不传患者资料。编码/解码算法保持小范围纯函数，测试固定向量、静区、校验位、无效输入和身份匹配。设备扫码可靠性不是单元测试可以证明的，打印参数及临床模板另行验收。

Code39 字符宽窄表及Mod43规则参考 ZXing 的 Apache-2.0 开源实现（[官方源码](https://github.com/zxing/zxing/blob/zxing-3.5.3/core/src/main/java/com/google/zxing/oned/Code39Reader.java)）。仅使用标准符号映射常数，自行实现受限编码；不引入其运行时依赖。若后续扩展二维码/多制式或真实驱动，应重新评估维护、许可及固定版本依赖，不扩展当前自写函数为通用条码库。

标准映射来源署名和 Apache-2.0 全文保留在 `third-party/NOTICE.md` 与 `third-party/Apache-2.0.txt`。
