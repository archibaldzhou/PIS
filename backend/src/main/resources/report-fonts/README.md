# 合成PDF固定中文字体

PISSyntheticSans.otf是本执行环境已安装Debian fonts-noto-cjk包内Noto Sans CJK SC Regular 2.004的离线子集，字体源为/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc，集合索引2。许可证为SIL Open Font License 1.1，原版权及完整许可证保存在DEBIAN-COPYRIGHT.txt；不是从拒绝访问的镜像下载。

源文件SHA256：b76b0433203017ca80401b2ee0dd69350349871c4b19d504c34dbdd80541690a。
产物SHA256：56f62f6e18eabb294a0598bd0fadaed372541189ce03ccbdce3b21cb1d3ebc5b。
大小：13123612字节。生产代码加载前核验固定摘要；缺失/变化拒绝生成，不回退系统字体或网络字体。

用当前环境fontTools 4.61.1生成，运行时不依赖Python/fontTools。保留ASCII、Latin-1、U+2000–206F、U+3000–303F、U+3400–4DBF、U+4E00–9FFF、U+FF00–FFEF内已有字形，重命名族/全名/PostScript名为PISSyntheticSans；保留原版权。未覆盖字符明确拒绝，不以方框或替代字体输出。

仅用于受限合成栅格PDF，字体不作为临床排版验收。JDK使用随包物理字体进行图像绘制；PDF不依赖阅读器安装中文字体。此文件不可因系统字体升级被就地替换；新字体需新渲染器版本及兼容验证，既有PDF仍直接返回已保存字节。

来源说明：[Noto CJK上游](https://github.com/notofonts/noto-cjk)、[JDK21物理字体接口](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/Font.html)。
