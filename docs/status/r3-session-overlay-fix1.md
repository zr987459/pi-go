# r3/session-overlay 审查第 1 轮修复（PR #23）

状态：**完成，PR #23**。基线 21ef230；代码与测试在 f7eba87（73dda8e + f7eba87）。

- I1 确认框按来源路由（两形态同一面板事件），删除确认后关闭已删服务商表单。
- I2 栈操作单次 `overlay.update`（OverlayStack 只新增 `update`，范围外最小改动）；ExtDialog / editor-text-offer 永不被替换或回退移除，始终在最上面。
- N1 tree_nav 防重入 + 按实例关树；N2 弹栈后失败压回表单（密钥不回填）；N3 侧键预拒绝 + 中途失败回滚；N4 get_tree 预填标签。
- 测试：OverlayFix1Test 15 例；CI @f7eba87：Android debug build（push 41770260049、pull_request 41770264686，含模拟器）与真 pi 桥接集成测试全部 success。
- PR 描述已补「审查第 1 轮修复」逐条证据表。
