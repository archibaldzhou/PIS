# T36 重显生命周期与阶段预算修复

## 真实 CI 证据

父会话核验 46fe9232b4797c5ae9b518fd9d5b889e8e366a2c / CI 37173320165：生成 POST 200、version=0 的确切结果回执及首次真实像素断言通过；失败在隐藏后重显，整个场景耗尽 30000ms。此前生成原因已不再作为这次失败解释。该真实运行没有最后一次重显响应，仍须新 CI 区分重显服务失败与累计预算。

## 确认的产品行为与修复

ResultOverlay 的 shown 变化原先总是重新运行加载 effect，包括 shown=false。因此隐藏仍会请求 metadata/PNG、生成 Blob URL 并保持定时/OSD 事件核验。重显之前还保留 view，存在重新渲染时复用上一次 view 的窗口。

现在显隐操作同步取消请求、清空 view 和 Blob URL；隐藏不建立加载 effect、计时器或 OSD 监听。重显重新请求当前 metadata 与 PNG，在确切版本、授权、哈希核验前不显示旧内容。未改变后端权限、QC/模型门禁、临床 executionAllowed=false 或默认关闭配置。

`before-fix.txt` / `before-error-context.md` 记录以本次回归运行已发布旧实现的真实失败：隐藏后仍为“合成强度叠加已核验”，而非已隐藏清空。旧实现仅临时用于本地对照，随后恢复本次修改；没有改写提交。

新增受控 metadata/PNG 延迟测试：重显等待时不出现旧层；PNG 未返回时再次隐藏并释放迟到响应仍无图；再次重显使用新 Blob URL 并精确解码 RGBA。另覆盖 401、409、500 重显失败，拒绝恢复已交付像素并允许取消清理。已有旋转、翻转、裁剪、撤销、损坏及切病例回归保留。

## 真实 E2E 检查

仅 results.spec.ts 的长场景设 60 秒总预算，依据上次已成功到达实际像素却在 30 秒边界终止的证据；不更改全局、fixture、重试配置。保留 metadata/PNG 各 10 秒、图层/像素各 5 秒、撤销 7 秒边界。无 sleep 或可选断言。

新增 `T36_RESULT_PHASE`（阶段名、累计/相邻耗时）和 `T36_RESULT_TOGGLE_DIAGNOSTIC`（metadata/PNG HTTP 状态、层数、白名单 UI 错误），不记录 URL、凭据或患者内容。重显再次校验 metadata 全内容、PNG SHA-256 和实际解码颜色；保留模型撤销后的 409、跨角色 404，并明确补充匿名 metadata/PNG 的 401。

## 本地验证

```sh
export PATH=/tmp/pis-frontend-tools/npm/node_modules/.bin:/tmp/pis-frontend-tools/node-v22.23.3-linux-x64/bin:$PATH
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run build
PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui -- synthetic-result.spec.ts
PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui
PIS_TEST_DB_PASSWORD=Synthetic-discovery-only npm --prefix frontend run test:e2e -- --list
```

186 项单测、typecheck/lint/build、9 项叠加针对性 UI 通过。完整 UI 98 项通过，见 ui-full.txt。`toggle-reverified-pixels.png` 已目视检查，底图与重新核验的彩色叠加可见，自动解码两个像素精确为 `[0,255,64,255]` 和 `[240,15,64,255]`。UI 为真实 Chromium/OpenSeadragon 加合成 HTTP 拦截，不是实际后端；45 项真实 E2E 仅列举，未本地执行。

Maven 离线 verify 因 Boot 4.1.1 BOM 缓存缺失（zipkin-reporter-bom 3.5.3、brave-bom 6.3.1 等）失败；本地完整后端及真实服务 E2E 未运行。完整修复 CI 待父会话核验，通过前 main 不动；不声称临床可用。
