# mvp/engine 第3轮定点修复

状态：已开始（2026-11-03 13:46 CST）

计划：
- PiEngine.openSession 重新打开同一会话时保留 pendingCarry（不 cleared/不 clearPendingCarry），补单测：崩溃 → get_messages 失败 → openSession(同一文件) 后 FAILED 气泡与“n 条未发出”提示仍在。
- Nit：ChatReducer 重复短句去重夹到历史尾部（能小改则改）。
