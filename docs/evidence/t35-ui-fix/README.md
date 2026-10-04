# T35 重试 UI 同步修复

基线：`a5f8d6944db05534d1d4395eae2f27f0ac22b26b`。父会话报告 main CI 37166793183 的 Synthetic browser UI contracts 失败；本次不改产品认证、限流、幂等实现或安全配置。

## 根因与修复

`SyntheticTasks.run()` 同步设置 busy，随后 `send()` → `task-api.post()` 等待 CSRF 请求，再发送命令。原测试把按钮 disabled 当作第二次命令已到达路由的依据，数组断言可以先于网络记录执行。

修复后的真实 Chromium UI 合约测试以可控 HTTP 路由分别冻结首个命令响应、重试 CSRF 响应及重试命令响应。明确证明 CSRF 等待期间按钮 disabled 而命令数仍是 1，之后等待路由捕获第二个命令，再断言完全相同的 key/body。合成接收夹具按 key 去重、记录一次执行，重试响应明确 replayed=true。再等待最新 RUNNING、输入清空和忙状态结束；切到 task-b 后才释放已取消的 task-a 原响应，验证 task-b 身份和 QUEUED 状态保持。

这验证浏览器请求、取消、同步及 UI 隔离；去重计数来自明确的合成 HTTP 夹具，不冒充后端数据库或真实服务 E2E 证据。没有 sleep、自动重试、可选断言、扩大超时、删除测试或门禁放宽。

CI 仅追加 `validation/t35-ui-fix` 分支触发，原有检查完整保留。T36 的 17 个未提交文件保持在 `/workspace/PIS` 的 `validation/t36`；逐文件 SHA-256 清单保存在当前环境 `/tmp/pis-t36-paused-hashes.json`，不进入此修复提交。

## 当前环境验证

工作目录 `/workspace/PIS-main-integration`（既有独立 worktree，同一执行环境）。复用同一冻结 lockfile 对应的已有 node_modules，无新安装/依赖变更。

```sh
export PATH=/tmp/pis-frontend-tools/npm/node_modules/.bin:/tmp/pis-frontend-tools/node-v22.23.3-linux-x64/bin:$PATH
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend test
npm --prefix frontend run build
PIS_UI_BROWSER_PATH=/usr/bin/chromium npm --prefix frontend run test:ui
sh /tmp/pis-t08-maven/apache-maven-3.9.16/bin/mvn -o -Dmaven.repo.local=/tmp/pis-t08-maven/repository -f backend/pom.xml -B -ntp verify
```

- typecheck、lint、177 项前端单测、生产构建：通过，原始输出见同目录文本。
- 全套 UI：88 项通过（零重试），含受控延迟/取消/幂等键回归，原始输出见 `ui.txt`。
- 后端 verify：未进入编译/测试；离线缓存缺少 Boot 4.1.1 引入的 zipkin-reporter-bom 3.5.3、brave-bom 6.3.1 等 POM，完整错误见 `backend-offline.txt`。未尝试改变认证或绕过下载拒绝。
- 真实服务 E2E：本地未运行，依赖上述完整后端；不得用 UI 路由夹具替代。
- 完整 CI：由父会话核验本次精确 SHA；在成功前不整合 main。

仅合成测试，未部署、未外发，不代表临床或生产可用。
