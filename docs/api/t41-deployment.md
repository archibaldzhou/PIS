# T41 交付与验证记录

范围见[开发PRD](../prd/T41-local-release-rehearsal.md)、[ADR0031](../adr/0031-loopback-dist-release-rehearsal.md)和[实际运行命令](../runbooks/t41-local-release.md)。T40已保留全部历史快进main：`68d3790345427cea827f2ee911d2b5abf4c93b24`。T41仅validation/t41，未整合main。

## 代码

- `scripts/deployment/release.py`：新建私有临时release，实际JAR/dist/lock/迁移digest、源SHA及明确[37,37]兼容区间；损坏、缺失、危险目录、测试fixture和未验证回滚拒绝。readiness前后再次校验，失败保持原活动目标。不是DB回滚工具。
- `local_proxy.py`：非root、仅127.0.0.1，服务真实构建dist及同源API；深链接、MIME、Cookie/CSRF/Range/幂等头、错误状态，不用Vite。逐文件hash、链接/路径/host检查，8连接/64MiB/15秒I/O及20秒绝对连接上限，不记录秘密。
- `rehearse.py`+`PgRehearsal.java`：正式JAR真实进程演练，T40恢复门禁、既有_test库中新随机schema、配置外置、cold start/readiness、停止/重启、失败候选保留原目标、回切先前已验证实例及数据库checksum/行计数不变。未创建账号、凭据或业务数据，不DROP/降级迁移。CI必检，失败只输出阶段、退出码与有界异常类名。
- 两个真实构建页面浏览器契约（身份API合成fixture）和独立真实Spring身份服务dist E2E。后者复用原一次性fixture，不打入正式JAR；原有E2E/UI不削减。

## 本地实际证据

`docs/evidence/t41`保存命令输出与实际截图：

- 208项前端单测、lint、构建通过；两次实际dist构建的文件集合/SHA256完全一致，见 `dist-manifest.json`、`dist-repeat.txt`。
- 全套117项UI通过；2项dist浏览器契约通过，`dist-login.png`、`dist-error.png`已实际查看。图片来自真正构建文件；身份/错误API明确使用合成fixture，不冒充真实后端。
- 5项Python release/真实HTTP代理契约通过：不完整或变化文件、路径/host、非root/危险目标、请求边界、饱和503/Retry-After、上游502、Cookie/CSRF及错误状态、明确迁移兼容、readiness期间变更、失败/回切。测试JAR是不可执行zip fixture，不冒充Java应用运行。
- T40真实PG17恢复通过：37迁移、145表、2,097,177字节对象、10项拒绝及实际pg_restore连接中断回滚。原有PG并发/回滚、provider和源码依赖边界检查通过。
- Java数据库helper实际源码编译通过并拒绝缺失测试库配置；Python AST与workflow YAML门禁检查通过。它们不是完整后端编译。

初测发现Python标准HTTP处理会规范化`//`路径，已在规范化前拒绝原始request-target。并发上限测试改为有界等待前请求释放槽位，再确定饱和；没有sleep或吞掉失败。保留相关拒绝与释放断言。

## 尚未验证

本地Maven离线解析缺Zipkin/Brave/JUnit等BOM，完整后端编译/测试、正式JAR启动演练、真实服务E2E未运行。dist真实服务场景仅完成测试发现；实际执行必须由父会话核验完整CI。没有降低安全断言或使用mock宣称全链路通过。

CI新增package rehearsal（9分钟）、dist服务浏览器（4分钟）、dist UI（2分钟）、代理契约（2分钟），原verify仍30分钟，Actions固定版本/权限/全部既有必检项不变。实际耗时和下一轮完整CI尚待核验。

当前应用回切演练使用同一构建的两个校验副本/实例；没有虚构已获得不同历史二进制兼容结论。TLS、域名、外部服务、互联网网关安全、生产在线流量切换、非空业务应用重启及不同历史制品升级仍未验证。只限隔离合成开发，默认业务/AI执行关闭，不是production ready或临床部署。
