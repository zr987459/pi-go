# r3/core 状态

状态：已开始

## 范围
DESIGN-r3.md §8.3 第 2 步（r3/core）：engine（SessionHost 真实实现）、pigo-bridge 桥接扩展、@ 文件搜索、workspace 收缩、
删除 GitParse（按 §8.2）、§5.7 三项实测。测试按 §7/§8.5。

## 计划
1. 读 DESIGN-r3.md / pi-features.md，定位现有 engine/bridge/workspace 代码
2. SessionHost 实现 + 测试
3. bridge 扩展 + @ 搜索 + workspace 收缩 + 删 GitParse
4. §5.7 实测记录；开 PR、CI 绿

## 已完成
- 从 r3/core-base 建分支

## 下一步
- 阅读设计文档与代码
