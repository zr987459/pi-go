- 本分支（W0 feat/modules）无移植代码：新契约与 Fake 均为原创。

## 新增 Gradle 依赖（W5 时并入 THIRD_PARTY_NOTICES.md §3）

格式同 THIRD_PARTY_NOTICES.md §3 的表格。版本见 `gradle/libs.versions.toml`；传递依赖来自各自 2.7.0 / 1.8.0 / 1.3.7 的 Maven Central POM。

| 组件 | 版本 | 许可证 | 用途 | 状态 |
|---|---|---|---|---|
| Coil（coil-compose，含传递依赖 coil-compose-base、coil、coil-base；以及 coil-base 引入的 Okio 3.9.0、accompanist-drawablepainter 0.32.0，均为 Apache-2.0；OkHttp 4.12.0 已在 §3） | 2.7.0 | Apache-2.0 | 图片加载：模型/服务商图标、文件页与输入框的图片预览 | 已声明（feature/files、feature/models 使用） |
| AndroidX Browser（Custom Tabs） | 1.8.0 | Apache-2.0 | OAuth 登录在 Custom Tab 中打开授权页（M3） | 已声明（core/catalog、feature/models 使用） |
| AndroidX ExifInterface | 1.3.7 | Apache-2.0 | 读取图片 EXIF 方向，压缩前纠正旋转（C1 图片输入） | 已声明（core/workspace、feature/files 使用） |
