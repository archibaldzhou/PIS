# T41 dist 套件端口/生命周期修复

基线 bbbbca0d1fc6525eac0bbf899918f7af08438e9a。父会话确认CI37194901349已通过正式JAR演练、真实E2E与真实dist服务，仅随后synthetic dist套件绑定5175失败。main不修改。

## 根因证据

原版实际子进程：读取一次页面后SIGTERM并wait，退出码-15；`ss`显示仅TIME-WAIT，第二个同端口代理立即退出1/EADDRINUSE。原HTTPServer `allow_reuse_address=False`，且默认SIGTERM不执行Python finally。证据 `docs/evidence/t41-port-fix/before.txt`。这是无残留监听进程也能复现的确定原因；CI未提供进程表，不据此虚构CI残留PID。

## 修复

- SO_REUSEADDR允许回收TIME_WAIT；不启用SO_REUSEPORT，不接管活跃listener；实际双进程冲突测试保证原进程继续服务。
- 代理显式处理SIGTERM/SIGINT，通过finally关闭自己的listener，终止其在途客户端/上游连接，等待自己的非daemon请求线程。现有8并发、15秒I/O及20秒绝对连接限额不变。
- Playwright代理命令使用exec避免多余shell父进程，显式向其拥有的进程组发SIGTERM并等待退出（25秒收尾预算，不扩大测试超时）。真实dist5175，合成dist5176；两个reuseExistingServer始终false。既有真实后端仍由原Playwright所属进程组管理，未杀占端口者或发现的用户进程。
- CI原所有steps/断言不变，现有Python发现门禁自动包括三个新增生命周期测试。

## 执行证据

8项release/真实HTTP/实际子进程测试通过：同端口连续三次启动/退出、失败启动、活跃冲突不干扰原进程、真实上游连接屏障下SIGINT取消不完整请求并再次启动。测试仅通过所创建Popen句柄停止并等待自己的进程。

真实dist synthetic UI套件连续两次运行，各2项通过，结束后5175/5176无监听。lint/typecheck、208项前端单测通过。原始输出在 `docs/evidence/t41-port-fix`。

本地Maven仍缺Zipkin/Brave等BOM；完整后端、真正real-service dist套件→synthetic dist套件链及正式JAR重跑未本地执行，待父会话完整CI复验。未将两次mock身份UI当作真实服务验证。父会话上次JAR通过仅证明sameBuiltArtifactTwoInstances=true；TLS=NOT_CONFIGURED、productionReady=false、合成隔离/不回退DB/不覆盖用户数据边界不变。
