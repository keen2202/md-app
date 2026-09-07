# 墨阅 MoRead — Markdown 阅读器

一款超轻量、纯离线、零权限的 Android Markdown 阅读器。设计文档见 `docs/`：
`PRD-产品设计文档.md`、`SPEC-技术规范文档.md`、`TASKS-任务分解文档.md`。

## 功能实现（PRD F-01 ~ F-13 全部落地）

- Markdown 渲染：标题/段落/嵌套列表/GFM 表格/引用/分割线/本地图片/行内与围栏代码/链接/粗斜体/删除线
- 代码高亮：内置 Top 20 语言静态语法表，One Light / One Dark 双色板
- 大纲：H1–H6 缩进树抽屉、点击平滑跳转 + 标题高亮闪烁、当前章节联动
- 主题：日/夜/跟随系统、200ms 颜色插值、阅读页一键切换
- 文件：SAF 打开、外部 VIEW 唤起、最近列表 50 条 LRU、失效记录标记
- 排版：字号 5 档、行距 3 档、阅读字体 3 种、强调色 5 色
- 阅读增强：进度记忆、文内搜索、>5MB 提示、>20MB 拒绝、GBK/GB18030 识别
- 导出分享：Markdown 原文 / 渲染后 HTML（FileProvider + 缓存清理）
- 隐私：零权限、零网络代码路径、零追踪；关于页明示「本应用不收集任何数据」

## 技术架构

- Kotlin + 原生 View + RecyclerView，单 Activity（首页/设置）+ ReaderActivity
- 解析：`MarkdownParser.parse()` 使用 CommonMark 参考解析器
  （commonmark-java + GFM 表格/删除线扩展）并转换为自有 AST；
  自研 `BlockParser`/`InlineParser` 保留为容错后备（`parseLegacy()`）
- 渲染：AST → `RenderPipeline` → RecyclerView 分批提交；行内样式统一 `SpanFactory`
- 存储：SharedPreferences（设置 + 最近记录 JSON），无数据库、无网络

## 依赖白名单（SPEC §1.1）

| 依赖 | 用途 | 白名单说明 |
| --- | --- | --- |
| Kotlin stdlib / coroutines | 语言与异步 | 允许 |
| AndroidX AppCompat / Core / RecyclerView / DrawerLayout / DocumentFile | 原生 UI 与 SAF | 允许 |
| org.commonmark:commonmark / ext-gfm-tables / ext-gfm-strikethrough | Markdown 参考解析器 | SPEC §1.2 备选路线，DEX 后增量约 300KB，通过体积红线 |

## 构建与运行

```bash
# 0. 准备 JDK 17、Android SDK（compileSdk 34），并写入 app/local.properties 或导出 ANDROID_HOME
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 1. 单元测试（含 CommonMark 97%+、fuzz、高亮、编码、性能）
./gradlew :app:testDebugUnitTest

# 2. Debug 构建（可安装）
./gradlew :app:assembleDebug

# 3. Release 构建（R8 + 资源压缩 + ABI 分包，arm64 约 0.9MB）
./gradlew :app:assembleRelease

# 4. 安全红线检查（uses-permission=0、网络 API=0、APK 权限审计）
./ci/check_no_network.sh
```

产物：
- `app/build/outputs/apk/release/app-arm64-v8a-release.apk`
- `app/build/outputs/apk/release/app-universal-release.apk`

> Gradle Wrapper 使用腾讯云 Gradle 镜像并配置 SHA-256 校验；如环境可直连
> services.gradle.org，可改回官方 URL（校验和一致）。

## 验证方式

1. **功能**：安装 APK → 首页空状态 → SAF 打开 `.md` → 阅读页渲染；
   点击大纲跳转、日/夜切换、字号/行距/字体/强调色设置即时生效。
2. **外部唤起**：在系统文件管理器/第三方 App 中以「墨阅」打开 `.md`。
3. **异常**：打开 GBK 文档验证中文零乱码；删除已授权文件后回首页标记「文件不存在」；
   打开 >20MB 文件提示拒绝。
4. **自动化**：见 `benchmark/README.md`；本仓库已运行全部 JVM 单测、
   `assembleDebug`、`assembleRelease` 与 `ci/check_no_network.sh`。

## 文档冲突与处理决策

见 `docs/DEV-NOTES.md`。
