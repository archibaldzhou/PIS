# T42 首页过时开发说明修复

在精确基线 `5be3b823a68dbddb2d8962b53492afe618f82f82` 的实际 dist 截图 QA 中发现 App 页脚仍写“尚未实现病理业务或临床 AI”。这条早期说明已不准确：仓库有合成病理工作流，但仍无临床 AI、不能诊疗或生产使用。

仅修改 `frontend/src/App.tsx` 的一行文案为“仅供合成数据开发演练；无临床 AI，不可用于诊疗或生产”。在 `frontend/dist-tests/contract.spec.ts` 加入实际构建页面新提示可见和旧提示不存在的确定断言。没有修改认证、权限、临床 executionAllowed、默认合成开关或任何业务动作。

[原截图](../evidence/t42-notice-fix/before.png)、[修复后实际dist截图](../evidence/t42-notice-fix/after.png)。前后均是真实Chromium的构建产物页面，身份API明确mock，不称为真实服务验收。

本地208单测、lint、typecheck/build、2项dist浏览器通过；原始输出分别为 [unit](../evidence/t42-notice-fix/unit.txt)、[lint](../evidence/t42-notice-fix/lint.txt)、[build](../evidence/t42-notice-fix/build.txt)、[dist](../evidence/t42-notice-fix/dist-ui.txt)。大bundle提示未消除、未放宽阈值。完整后端/真实服务本地仍缺Maven BOM，新修复完整CI待父会话核验，不能套用基线绿灯。

T42验收摘要检查明确登记这两个文件的旧/新SHA256，其余运行时及测试保持原基线。此修复独立提交，不混入验收文档主体或CI配置。
