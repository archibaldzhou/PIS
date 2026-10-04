# T36 main 复验：显示字节验证替代 CDP 响应体读取

## 基线与隔离

父会话核验 main a1a51a7466ac5848818312687e1942e501c94cfb 的 CI 37178757731：metadata=200、PNG=200、quota units各1、checks为7/14、layerCount=1、elapsed=21221ms。失败是 Playwright `response.body()` 的 `Network.getResponseBody: No data found for resource with given identifier`，不是429或图层缺失。

T37开发已暂停（在运行的T37全套UI检查被主动中止，不能记为通过）。30个文件按SHA-256记录在原环境 `/tmp/pis-t37-paused-hashes.json`，并逐文件读取stash内容复核一致；stash为 `cdac462843af0aa47dc4e9ed0270377e72e3bf16`。本修复从上述main基线创建validation/t36-main-fix，不含T37功能或迁移。

## 调查与改动

结果组件每5秒核验资格，并在显隐/变换时取消旧fetch及撤销旧Blob URL。同一metadata/PNG地址可能多次发起，Playwright Response对象仍存在不意味着CDP还能取出它的网络缓冲体。此次已知异常来自测试读取CDP缓冲，不是应用消费PNG的异常；具体Chromium回收触发点未有trace证明，不把取消这一可能诱因说成已证实的唯一原因。

- 新请求观察器只接受安装观察器之后发起的请求，避免上一轮迟到响应匹配本轮等待。
- 保留真实加载请求200、当前epoch/hash响应头、每HTTP一次配额的严格断言。
- 不再对浏览器结果metadata/PNG Response调用body/json。完整metadata从已显示图层对应的UI状态读取，与先前精确服务器版本比较。
- 对SVG实际引用的blob: URL读取本地Blob，校验PNG类型、33–24576字节、SHA-256、magic、64×64尺寸，并通过createImageBitmap解码首尾RGBA。和元数据hash/合成强度逐项比较。该读取不请求服务器，不会把额外HTTP下载当作原请求证据；原有独立API下载检查仍明确是API契约检查。
- Blob不存在、过期、读取/解码失败、hash/像素不符一律失败，没有catch忽略、盲重试、sleep或预算增长。Bitmap在finally关闭。
- 连续两次相同URL显隐，各次人工控制PNG响应延迟；加载前图层必须不存在，完成后新Blob URL与真实字节/像素必须吻合，并断言辅助验证不增加服务器PNG请求。既有取消迟到、401/409/500拒绝、撤销清空及初始/重显像素断言保留。
- 不改产品限额、授权、事务、默认关闭或executionAllowed=false。CI仅增加独立修复分支触发，覆盖不减。

## 本地验证

原环境固定Node22.23.3/npm11.21.0和系统Chromium：

```
npm --prefix frontend test
npm --prefix frontend run lint
npm --prefix frontend run build
npm --prefix frontend run typecheck
PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui -- synthetic-result.spec.ts
PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui -- viewer.spec.ts viewer-validation.spec.ts roi.spec.ts
PIS_TEST_DB_PASSWORD=Synthetic-discovery-only npm --prefix frontend run test:e2e -- --list
sh /tmp/pis-t08-maven/apache-maven-3.9.16/bin/mvn -o -Dmaven.repo.local=/tmp/pis-t08-maven/repository -f backend/pom.xml -B -ntp verify
```

187前端单测、lint、构建及类型检查通过。合成结果10项、阅片/ROI13项浏览器UI通过（共23项）；这是真实Chromium/OSD和合成HTTP夹具，不替代真实Spring服务。新增截图 `displayed-blob-repeated-toggle.png` 已实际查看：底图圆点、合成强度渐变格、中央白色ROI可见；`cancelled-toggle-reverified.png` 记录取消后重新核验。测试同时做了字节hash及实际像素断言，不仅DOM断言。

Maven离线仍缺Boot4.1.1导入BOM（zipkin/brave等），未进入后端编译/测试。真实服务E2E本地未运行；--list仅发现45项。完整CI由父会话核验，不宣称可合并、临床可用或投产。main不变，T37保持暂停，待修复CI通过后再恢复。
