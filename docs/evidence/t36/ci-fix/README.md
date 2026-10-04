# T36 阅片 E2E 分段与传输同步修复

## 已知证据与边界

父会话报告 ea538022617b0d4111c8b7bad0060c56341a0a6e 的 CI 37170392946 在单个 viewer 场景 30 秒总预算耗尽；此前同一场景的结果 metadata、PNG 哈希、任务和版本断言已执行，返回阅片后等待叠加层失败。该用例把 T28–T36 的准备、交互、像素和两种撤销串在同一预算内，未独立等待返回后的底图与叠加传输。

现有 CI 摘要没有最后一次浏览器 metadata/PNG 响应或 UI 错误内容，故不能证明没有产品错误，也不能认定只要增加超时即可修复。此次不修改产品资格、缓存、限流、鉴权或渲染代码。完整真实服务结论须由新 SHA 的 CI 验证。

## 变更

- 两个独立真实服务用例，各自新建病例、存储/扫描版本和浏览器上下文：基础 RGB/ROI/QC 撤销；模型/任务/结果实际像素及模型撤销。没有共享前一测试输出或串行依赖。
- 公共准备通过真实 API 和 UI 完成，独立 fixture 上限 30 秒；测试体仍为默认 30 秒。未增加全局超时、重试、sleep 或放宽断言。
- 返回阅片明确等待 manifest HTTP 200、OSD 加载状态与真实底图像素；叠加读取分别有 10 秒请求边界，核对 metadata 全内容及 PNG 哈希，再检查成功状态、实际图层与解码像素。
- 原有 RGB、ROI、并发瓦片、幂等、executionAllowed=false、撤销和跨角色 404 断言保留在对应场景。
- CI 附件记录最近 40 个浏览器资源类别、HTTP 状态与相对时间，不记录 URL、查询串、凭据或响应正文。
- 新增受控延迟 metadata / PNG 两阶段测试，逐阶段确认没有图层、显示核验中；解除两个屏障后必须实际解码为指定 RGBA。已有迟到响应切换病例、损坏、取消和撤销回归保留。

## 本地复现

使用原环境工具 PATH：

```sh
export PATH=/tmp/pis-frontend-tools/npm/node_modules/.bin:/tmp/pis-frontend-tools/node-v22.23.3-linux-x64/bin:$PATH
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run build
PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui
PIS_TEST_DB_PASSWORD=Synthetic-discovery-only npm --prefix frontend run test:e2e -- --list
```

181 项单测及 typecheck/lint/build 通过；结果叠加针对性 UI 5 项通过。全套 UI 93 项通过，见 ui-full.txt。E2E 仅列举 45 项 / 25 文件，不表示执行通过。

`delayed-result-verified-pixels.png` 为本次实际 Chromium/OpenSeadragon 交互截图，已目视检查底图、彩色叠加与非诊断说明；解码两个像素分别精确为 `[0,255,64,255]` 与 `[240,15,64,255]`。UI HTTP 是合成契约拦截，不能替代真实后端。

Maven 离线 verify 在解析 Spring Boot 4.1.1 BOM 时失败（zipkin-reporter-bom 3.5.3、brave-bom 6.3.1 等未缓存），见 maven.txt；本地没有运行完整后端或真实服务 E2E。未访问已拒绝 Actions API，交父会话验证本次完整 CI，通过前不合 main。仅合成、无临床效力。
