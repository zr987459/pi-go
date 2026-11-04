# r3/calm 第三方来源

| 文件 | 来源 | 许可 | 说明 |
|---|---|---|---|
| `core/ui/src/main/kotlin/.../core/ui/calm/PinyinTable.kt` | 用 [pypinyin](https://github.com/mozillazg/python-pinyin) 离线生成的「汉字 → 拼音首字母」表（GB2312 一级常用字 3755 个） | MIT | 只含生成的数据，运行时不依赖 pypinyin |
| `core/ui/src/main/kotlin/.../core/ui/calm/CalmIcons.kt` | 自绘线性图标（24dp 画布、2dp 圆头线），风格参照 Material Symbols Rounded，未复制其路径数据 | — | 替代 `dsh_icons.ttf` |
| `core/ui/src/main/res/font/jetbrains_mono_*.ttf` | 仓库已有，只读引用 | OFL-1.1 | 代码块与命令字体 |
