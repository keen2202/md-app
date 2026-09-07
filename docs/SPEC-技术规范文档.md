# 「墨阅」SPEC — 技术规范文档

| 项目 | 内容 |
| --- | --- |
| 关联文档 | `docs/PRD-产品设计文档.md`（需求源）、`docs/TASKS-任务分解文档.md`（执行视图） |
| 版本 | v1.0 |
| 平台基线 | Android 8.0（API 26）+，Kotlin，原生 View 体系 |
| 文档状态 | 可指导开发 |
| 最后更新 | 2026-09-07 |

**编号规则**：任务 ID = `T01`–`T19`（详见 TASKS 文档）；功能 ID = `F-01`–`F-13`（源自 PRD §4.1）；规范章节 = 本文档 `§1`–`§7`。三方双向可追溯。

---

## §0 需求追溯总表（PRD ↔ SPEC ↔ TASK）

| PRD 需求 | PRD 优先级 | 关联 SPEC 章节 | 关联任务 | 里程碑 |
| --- | --- | --- | --- | --- |
| F-01 Markdown 渲染 | P0 | §1.2、§1.3、§3.2、§5 | T02、T03 | v1.0 |
| F-02 代码语法高亮 | P0 | §1.4、§4.2、§5 | T04 | v1.0 |
| F-03 大纲目录与跳转 | P0 | §1.5、§3.3、§5 | T05 | v1.0 |
| F-04 主题切换（手动/跟随系统） | P0 | §1.6、§3.3、§5 | T06、T09 | v1.0 |
| F-05 文件打开（SAF/外部唤起/最近列表） | P0 | §1.7、§2.2、§5 | T07、T08 | v1.0 |
| F-06 排版调节（字号/行距） | P1 | §1.6、§5 | T13 | v1.1 |
| F-07 阅读进度记忆 | P1 | §1.7、§2.2、§5 | T14 | v1.1 |
| F-08 文内搜索 | P1 | §1.3、§5 | T15 | v1.2 |
| F-09 大文档优化 | P1 | §1.3、§4.2、§4.3、§5 | T16 | v1.2 |
| F-10 编码识别 | P1 | §1.7、§5 | T12 | v1.1 |
| F-11 导出/分享 | P2 | §1.3、§2.2、§5 | T17 | v1.3 |
| F-12 自定义强调色 | P2 | §1.6、§5 | T18 | v1.3 |
| F-13 字体选择 | P2 | §1.6、§5 | T19 | v1.3 |
| 红线：零权限/纯离线/零追踪 | — | §2.1、§2.3、§7.2 | T01、T10 | v1.0 验收门 |
| 性能指标（PRD §7.1） | — | §4.1、§7.3 | T01、T11、T16 | v1.0 验收门 |
| 容错与稳定性（PRD §8） | — | §1.2、§2.3、§7.4 | T02、T10 | 全周期 |
| 工程基座（构建/分包/混淆） | — | §1.1、§4.2、§5 | T01 | v1.0 |

---

## §1 代码建议

### 1.1 代码组织（模块结构）

单 Module App（避免多模块构建开销与体积冗余），按**包（package）**分层隔离：

```
com.moread.app/
├── App.kt                    # Application：懒初始化，禁止在 onCreate 做重活
├── MainActivity.kt           # 单 Activity 架构，承载首页/设置（Fragment）
├── reader/                   # 阅读核心（与 UI 解耦，PRD §8 可维护性要求）
│   ├── parser/               # Markdown 解析引擎：Lexer → AST
│   │   ├── MarkdownParser.kt
│   │   ├── BlockParser.kt / InlineParser.kt
│   │   └── ast/Node.kt       # AST 节点定义（密封类）
│   ├── render/               # 渲染管线：AST → RecyclerView ViewHolder
│   │   ├── RenderPipeline.kt
│   │   ├── block/            # Heading/Paragraph/List/Table/Quote/Code/Image 等 VH
│   │   └── span/SpanFactory.kt
│   ├── highlight/            # 语法高亮：静态语法表 + Tokenizer
│   │   ├── Highlighter.kt
│   │   └── lang/             # 每语言一个语法定义文件（Top 20）
│   └── outline/OutlineExtractor.kt
├── ui/
│   ├── home/                 # 首页：最近文件列表 + 空状态
│   ├── reader/               # 阅读页 Activity/Fragment + 大纲抽屉 + 工具条
│   └── settings/             # 设置页
├── theme/ThemeEngine.kt      # 主题系统：色板、模式、切换动画
├── file/                     # SAF 接入、Intent 接收、编码检测、最近记录
│   ├── DocumentLoader.kt / EncodingDetector.kt / RecentStore.kt
└── prefs/Prefs.kt            # SharedPreferences 封装（唯一本地存储）
```

**组织原则**：
- `reader/` 包**零 Android UI 依赖**（仅 android.text 级别），可单测、可替换（对应 PRD §8 可维护性）；
- 禁止引入网络相关 import；在 CI 中用静态检查脚本拦截 `java.net` / `okhttp` / `retrofit` 字样（见 §2.3）；
- 第三方依赖白名单制：仅允许 RecyclerView、AppCompat（或完全不用）、kotlin-stdlib，新增依赖需评审并回归体积。

### 1.2 解析引擎（F-01，T02）

- **路线**：自研轻量两阶段解析（Block 级 → Inline 级），输出 AST 而非 HTML，直接供原生渲染消费；放弃 WebView + JS 方案（体积与内存均不达标，见 §4.3）；
- **容错优先**：任何畸形输入不抛异常——所有解析分支必须有 fallback（无法识别的块降级为段落）；行扫描使用索引游标，禁止无限循环（每轮游标必须前进）；
- **范围**：标题/段落/有序无序嵌套列表/表格（GFM）/引用/分割线/行内与围栏代码块/本地图片/链接/粗斜体/删除线；不实现 HTML 内联块（v1 按纯文本转义显示，规避注入面，见 §2.3）；
- **增量接口**：解析器暴露 `parseChunk()` 分段解析能力，为 T16 大文档分段渲染预留；
- **测试基线**：CommonMark spec.txt 用例通过 ≥ 90%（PRD §10 既定验收线），未过用例登记豁免清单。

### 1.3 渲染管线（F-01/F-08/F-09/F-11，T03/T15/T16/T17）

- **AST → 块视图列表**：每个块级 Node 映射一个 RecyclerView ViewHolder 类型；行内样式统一由 `SpanFactory` 生成（Bold/Italic/Code/Link/Strikethrough Span）；
- **链接交互**：LinkSpan 点击 → 底部弹层显示 URL + 「复制链接」（PRD §6.4 既定交互），弹层文案「已复制，可在浏览器中打开」；
- **图片**：仅解析本地相对路径（以文档所在目录为 baseUri 经 SAF 解析）；缺失显示占位符；点击全屏查看 + 双指缩放（PhotoView 自绘轻量实现，不引第三方大图库）；
- **表格**：块内横向 ScrollView 包裹，列宽按内容自适应，超宽可滑动；
- **分段渲染**：`RenderPipeline` 支持按块区间分批提交 adapter（首屏优先），T16 在此基础上做 >1MB 文档虚拟化；
- **搜索支持**：渲染时为每个块记录原始文本偏移区间，T15 文内搜索据此定位并高亮；
- **导出**：`HtmlExporter`（T17）独立遍历 AST 生成内联样式 HTML 字符串，写缓存文件后经 FileProvider 调起系统分享。

### 1.4 语法高亮引擎（F-02，T04）

- **路线**：自研正则规则表驱动的 Tokenizer（关键字/字符串/注释/数字/类型/函数 6 类 token），每语言一个 `LanguageDef`（关键字集 + 规则列表）；
- **语言清单（Top 20）**：js、ts、python、java、go、rust、c、cpp、shell、json、yaml、html、css、sql、kotlin、swift、php、ruby、markdown、plaintext（兜底）；
- **体积控制**：语法表编译期内置（Kotlin object），无运行时下载；单语言定义 ≤ 3 KB 源码，总计 ≤ 60 KB；
- **配色**：两套 token 色板（One Light / One Dark，色值见 PRD §6.3），由 ThemeEngine 联动切换；
- **降级**：未知语言按 plaintext 渲染；规则按序短路匹配，单代码块高亮耗时应 < 16 ms（§4.1）。

### 1.5 大纲目录模块（F-03，T05）

- `OutlineExtractor` 在 AST 上收集 H1–H6 节点，产出 `(level, title, blockIndex)` 列表；
- **抽屉 UI**：右侧 DrawerLayout，宽 78% 屏宽；每级缩进 16dp；当前可视章节随滚动联动高亮；
- **跳转**：点击 → adapter 定位 blockIndex → `LinearLayoutManager.smoothScrollToPosition`（300ms）→ 目标标题背景高亮闪烁 1 次（800ms，ValueAnimator 控制背景 alpha）；
- **空大纲**（全文无标题）：抽屉内显示「本文档无章节标题」占位。

### 1.6 主题与排版系统（F-04/F-06/F-12/F-13，T06/T09/T13/T18/T19）

- `ThemeEngine` 单例持有：模式（DAY / NIGHT / FOLLOW_SYSTEM）、色板（PRD §6.3 全部色值定义为 `ColorPalette` data class）、排版参数（字号 5 档 14/15.5/17/19/21sp、行距 3 档 1.5/1.7/1.9、字体 3 种、强调色 3–5 色）；
- **持久化**：全部写 SharedPreferences（`Prefs.kt`），启动时同步读取（<1ms，不占启动预算）；
- **切换实现**：不 recreate Activity——遍历当前色板差值做 200ms ValueAnimator 颜色插值，逐 View 刷新（阅读页 RecyclerView 通过 `notifyItemRangeChanged` + payload 换色板，避免重绑闪烁；严禁整页闪白，PRD §6.4）；
- **跟随系统**：注册 `Configuration.uiMode` 变化监听（`onConfigurationChanged`），仅 FOLLOW_SYSTEM 模式下生效；
- **阅读页快捷切换**：底部工具条日/夜图标一键切 DAY↔NIGHT（此操作同时把模式从 FOLLOW_SYSTEM 改为手动，符合用户预期）。

### 1.7 文件接入与本地存储（F-05/F-07/F-10，T07/T08/T12/T14）

- **打开**：`ActivityResultContracts.OpenDocument`（SAF），mimeType `text/*`；manifest 注册 `VIEW` intent-filter（`.md/.markdown/.txt`，scheme content/file）接收「打开方式/分享」；
- **读取**：ContentResolver 流式读取；编码检测：BOM 优先 → UTF-8 严格解码 → 失败则用启发式（GBK 双字节分布统计，T12 实现）；兜底 UTF-8 宽容模式 + Toast「编码可能异常」（PRD §5.3）；
- **授权持久化**：`takePersistableUriPermission` 保存最近文件授权；授权失效时最近列表标记「文件不存在」（PRD §5.3）；
- **最近记录 / 进度记忆**：`RecentStore`（uri、名称、最后打开时间、滚动位置 blockIndex+offset）序列化为 JSON 存 SharedPreferences；上限 50 条，LRU 淘汰；
- **隐私边界**：全部数据仅存应用私有目录与默认 SharedPreferences，无任何外发通道（§2）。

---

## §2 安全设计

### 2.1 零权限模型（红线，T01/T10）

| 措施 | 实现 |
| --- | --- |
| manifest 零权限 | `AndroidManifest.xml` 不含任何 `<uses-permission>`（含 INTERNET）；CI 脚本断言该标签数为 0 |
| 零网络代码路径 | 依赖白名单制（§1.1）；CI 静态扫描拦截 `java.net.*`/`okhttp`/`retrofit`/`HttpURLConnection`/`DownloadManager` import |
| 零追踪 | 不集成任何统计/广告/崩溃上报 SDK；关于页明示「本应用不收集任何数据」 |
| 文件访问 | 仅 SAF 用户显式授权的单文档 URI；不申请存储权限，不扫描设备文件 |

### 2.2 数据安全

- **数据分类**：仅两类本地数据——用户主动打开的文档内容（运行时内存 + 不落盘，分享导出时临时缓存文件用后即删）与设置/最近记录（SharedPreferences，私有目录）；
- **临时文件**：导出 HTML 经 `cacheDir` + FileProvider 授权，分享完成后删除；
- **备份策略**：`android:allowBackup="false"`，防止文档记录经系统备份外泄；
- **截图防护**：不默认开启 FLAG_SECURE（阅读场景需允许截图），但在关于页说明文档内容仅本地可见。

### 2.3 潜在风险防范

| 风险 | 防范 |
| --- | --- |
| 畸形/恶意 md 输入导致崩溃或死循环 | 解析器容错设计（§1.2）+ 游标前进断言 + fuzz 测试（§7.4） |
| 超大文件 OOM | 流式读取 + 文件大小上限提示（>5MB 进度条，PRD §5.3）+ 分段渲染（§4.3）；单文件硬上限 20MB 拒绝并提示 |
| 路径遍历（本地图片相对路径） | 图片路径仅允许文档同级及子目录，拒绝 `..` 越级解析到文档目录之外 |
| 链接欺诈 | 链接不自动跳转，仅弹层显示完整 URL 由用户复制（天然防钓鱼跳转） |
| Intent 攻击（外部唤起传入异常 URI） | 校验 scheme 仅 content/file；读取异常一律捕获并回退错误提示页 |
| 供应链风险 | 依赖白名单 + 版本锁定 + 开源代码可审计（PRD §8 开源策略） |

---

## §3 架构方案

### 3.1 技术选型

| 决策点 | 选择 | 理由 |
| --- | --- | --- |
| 语言/框架 | Kotlin + 原生 View（RecyclerView） | 无跨平台框架体积开销（Flutter/RN 增加 5–15MB，突破体积红线） |
| 渲染路线 | AST → 原生 View，**不用 WebView** | WebView 内存基线 ~80MB+，无法达成阅读内存 ≤120MB；且 JS 桥复杂 |
| 解析器 | 自研轻量解析（§1.2） | 引入 commonmark-java 约 200KB 亦可备选，二选一以体积/通过率为评审点（T02 决策） |
| 高亮 | 自研规则表 Tokenizer（§1.4） | highlight.js 类库全量 >500KB，超限 |
| 存储 | SharedPreferences（JSON 序列化） | 数据量小，无需 Room（省 ~1MB 依赖体积） |
| 异步 | Kotlin Coroutines（Dispatchers.IO/Default） | 解析/高亮下主线程 |
| minSdk / targetSdk | 26 / 34 | PRD §8 兼容性要求 |

### 3.2 分层架构

```
┌─────────────────────────────────────────────┐
│ UI 层     Home / Reader(抽屉+工具条) / Settings │
├─────────────────────────────────────────────┤
│ 应用服务层  ThemeEngine / RecentStore / Prefs    │
├─────────────────────────────────────────────┤
│ 阅读核心层  Parser → AST → RenderPipeline         │
│  (零UI依赖)  Highlighter / OutlineExtractor       │
├─────────────────────────────────────────────┤
│ 数据接入层  DocumentLoader / EncodingDetector / SAF│
└─────────────────────────────────────────────┘
```

### 3.3 模块交互关系

| 交互 | 流向 | 说明 |
| --- | --- | --- |
| 打开文档 | UI → DocumentLoader → Parser → RenderPipeline → Reader UI | 主闭环（PRD 流程 1/2） |
| 大纲 | Parser AST → OutlineExtractor → 抽屉 UI → RenderPipeline 定位 | F-03 |
| 主题 | Settings/工具条 → ThemeEngine → 全局色板广播 → 各页增量刷新 | F-04，无 recreate |
| 高亮 | RenderPipeline 遇代码块 → Highlighter → Span | F-02 |
| 进度 | Reader UI 滚动位置 → RecentStore → 下次打开恢复 | F-07 |

### 3.4 关键数据流（打开文档）

```
SAF/Intent URI → 流式读取(协程IO) → 编码检测 → 全文 String
  → Parser.parse (Dispatchers.Default，>500KB 分段)
  → AST → OutlineExtractor（并行）
  → RenderPipeline 首屏块优先提交 → RecyclerView
  → 剩余块后台分批提交（每批 ≤16ms 预算）
```

---

## §4 性能方案

### 4.1 性能目标（承接 PRD §7.1，验收见 §7.3）

| 指标 | 目标 | 红线 | 归属任务 |
| --- | --- | --- | --- |
| APK 体积（arm64 分包） | ≤6MB | ≤8MB | T01 |
| 冷启动 | ≤400ms | ≤500ms | T01/T11 |
| 热启动 | ≤150ms | ≤200ms | T11 |
| 空闲内存 | ≤60MB | ≤80MB | T11 |
| 阅读内存（1MB 文档） | ≤120MB | ≤150MB | T11/T16 |
| 首屏渲染（500KB） | ≤300ms | ≤500ms | T02/T03 |
| 大文档滚动（2MB） | ≥55fps | ≥45fps | T16 |
| 单代码块高亮 | <16ms | <33ms | T04 |

### 4.2 优化策略

- **启动**：App.kt 零重活；ThemeEngine/Prefs 同步轻读；无 Splash 页（冷启动直接进首页）；延迟初始化全部非首屏组件；Baseline Profile 随包发布；
- **体积**：ABI 分包（arm64-v8a 优先，另出 universal）；R8 full mode + 资源 shrink；全部图标 VectorDrawable；无字体文件内置（代码字体用系统等宽，T19 字体选择仅切换系统字体族）；依赖白名单；
- **渲染**：首屏块优先 + 后台分批提交（§3.4）；Span 对象复用池；RecyclerView 回收池按块类型分桶；图片按屏宽采样解码（inSampleSize）；
- **内存**：大图解码上限 2048px 长边；AST 在渲染完成后对 >2MB 文档可释放原文 String（仅留 AST 与块文本）；
- **高亮**：代码块懒高亮（进入可视区才 tokenize），结果缓存。

### 4.3 瓶颈应对预案

| 预判瓶颈 | 应对 |
| --- | --- |
| 原生 View 渲染 2MB+ 文档 item 数过多（>5000 块） | 相邻短段落合并为一个 TextView item；必要时引入「页式分块」：每 200 块一个容器 item 内自绘 |
| RecyclerView 主题切换全量刷新卡顿 | payload 局部刷新 + 只刷新可视区 ±1 屏，其余滚动时惰性换色 |
| 解析耗时超预算 | 分段解析边解析边渲染；解析进度可中断（ensureActive） |
| 表格超宽测量慢 | 表格块异步预测量，先占位后替换 |
| 冷启动仍超 400ms | 裁剪 AppCompat（评估完全裸主题）；启动 trace 逐段定位，砍首帧前全部非必要调用 |

---

## §5 涉及的文件列表

> 全部为**新建**文件（项目从零启动）。路径相对 `app/src/main/`。

| # | 文件 | 用途与内容 | 关联任务 |
| --- | --- | --- | --- |
| 1 | `AndroidManifest.xml` | 零权限声明；MainActivity + VIEW intent-filter（.md/.markdown/.txt）；`allowBackup=false`；`configChanges=uiMode` | T01/T07 |
| 2 | `build.gradle.kts`（app） | minSdk26/target34；ABI splits；R8 fullMode；shrinkResources；依赖白名单；Baseline Profile | T01 |
| 3 | `java/com/moread/app/App.kt` | Application 壳，懒初始化 | T01 |
| 4 | `.../MainActivity.kt` | 单 Activity，托管 Home/Settings Fragment | T01/T08/T09 |
| 5 | `reader/parser/MarkdownParser.kt` | 解析入口，块级调度 + 分段解析 API | T02 |
| 6 | `reader/parser/BlockParser.kt` | 标题/列表/表格/引用/代码块/分割线块解析 | T02 |
| 7 | `reader/parser/InlineParser.kt` | 粗斜体/删除线/行内代码/链接/图片 | T02 |
| 8 | `reader/parser/ast/Node.kt` | AST 密封类定义（含 blockIndex、文本偏移） | T02 |
| 9 | `reader/render/RenderPipeline.kt` | AST→块列表→adapter 分批提交；块偏移索引 | T03 |
| 10 | `reader/render/block/*ViewHolder.kt`（约 8 个） | Heading/Paragraph/List/Table/Quote/Code/Image/Hr VH | T03 |
| 11 | `reader/render/span/SpanFactory.kt` | 行内 Span 生成与复用；LinkSpan 点击弹层 | T03 |
| 12 | `reader/render/LinkSheetDialog.kt` | 链接底部弹层（显示 URL + 复制） | T03 |
| 13 | `reader/render/ImageViewerDialog.kt` | 本地图片全屏查看 + 双指缩放 | T03 |
| 14 | `reader/render/HtmlExporter.kt` | AST→内联样式 HTML（导出分享用） | T17 |
| 15 | `reader/highlight/Highlighter.kt` | Tokenizer + 6 类 token + 懒高亮缓存 | T04 |
| 16 | `reader/highlight/lang/*.kt`（20 个） | Top 20 语言静态语法表 | T04 |
| 17 | `reader/outline/OutlineExtractor.kt` | H1–H6 抽取 → (level,title,blockIndex) | T05 |
| 18 | `ui/reader/ReaderActivity.kt` | 阅读页：渲染区、底部工具条（大纲/主题/字号/搜索）、沉浸模式 | T03/T06 |
| 19 | `ui/reader/OutlineDrawer.kt` | 右侧抽屉大纲列表 + 当前章节联动高亮 | T05 |
| 20 | `ui/reader/SearchBar.kt` | 文内搜索栏 + 结果导航 | T15 |
| 21 | `ui/home/HomeFragment.kt` | 最近文件列表 + 空状态引导 + 打开文件按钮 | T08 |
| 22 | `ui/settings/SettingsFragment.kt` | 主题模式/字号行距/高亮主题/清除记录/关于（隐私承诺） | T09/T13 |
| 23 | `theme/ThemeEngine.kt` + `ColorPalette.kt` | 双主题色板（PRD §6.3 色值）、200ms 插值切换、跟随系统监听、强调色/字体扩展点 | T06/T18/T19 |
| 24 | `file/DocumentLoader.kt` | SAF 流式读取、大小上限与进度回调 | T07 |
| 25 | `file/EncodingDetector.kt` | BOM/UTF-8/GBK 启发式检测与兜底 | T07/T12 |
| 26 | `file/RecentStore.kt` | 最近记录 + 阅读进度（JSON in SharedPreferences，LRU 50 条） | T08/T14 |
| 27 | `prefs/Prefs.kt` | 设置项读写封装 | T06/T09 |
| 28 | `res/values/colors.xml` `themes.xml` | 日/夜两套色板与主题定义（PRD §6.3） | T06 |
| 29 | `res/xml/file_paths.xml` | FileProvider 缓存路径（导出分享） | T17 |
| 30 | `test/reader/parser/*` `highlight/*` | CommonMark spec.txt 用例 + 高亮单测 + fuzz | T02/T04/T10 |
| 31 | `ci/check_no_network.sh` | CI 静态检查：manifest 权限数=0、网络 API 零引用 | T10 |
| 32 | `benchmark/`（macrobenchmark） | 冷启动/渲染/滚动帧率自动化测量 | T11/T16 |

**修改文件**：无（全新项目）。后续版本新增功能（T12–T19）对应在 #20/#22/#23/#25/#26 上增量修改，已在上表标注。

---

## §6 实施进度追踪表

> 状态与 TASKS 文档同步维护；负责人按模块分工（待团队到位后填名，当前以模块代称）。

| 阶段 | 里程碑 | 包含任务 | 负责模块 | 状态 | 完成情况 |
| --- | --- | --- | --- | --- | --- |
| M0 | 工程基座 | T01 | 构建/框架 | completed | 100% |
| M1 | v1.0 核心功能 | T02–T09 | 阅读核心 + UI | completed | 100% |
| M1-Gate | v1.0 验收门 | T10、T11 | 安全/性能 | completed | 自动化 100%；真机指标待执行 |
| M2 | v1.1 体验增强 | T12、T13、T14 | 文件/主题/存储 | completed | 100% |
| M3 | v1.2 长文档 | T15、T16 | 阅读核心 | completed | 100% |
| M4 | v1.3 个性化 | T17、T18、T19 | 渲染/主题 | completed | 100% |

**进度更新规则**：每个任务状态变更（pending→in_progress→completed）同步更新本表与 TASKS 文档总览表；任意时刻至少一个任务 in_progress；M1-Gate 全绿才允许进入 M2。

---

## §7 验证和测试方案

### 7.1 功能测试（按 PRD 功能点逐项验收）

| 功能 | 验证方式 | 测试用例覆盖 | 验收标准 |
| --- | --- | --- | --- |
| F-01 渲染 | 单测（AST 断言）+ 截图比对 | CommonMark spec.txt ≥90% 通过；嵌套列表/表内代码/畸形输入各 ≥5 例 | 元素渲染正确，畸形输入零崩溃 |
| F-02 高亮 | 单测（token 序列断言）+ 目检样例集 | 20 语言各 ≥3 个真实代码样例；未知语言降级；明暗双色板 | token 分类正确；单块 <16ms |
| F-03 大纲 | UI 测试（Espresso） | H1–H6 混合层级、重复标题、无标题文档、跳转后高亮闪烁 | 抽屉树正确；跳转定位误差 0 块；闪烁 1 次 800ms |
| F-04 主题 | UI 测试 + 手动 | 手动切换、跟随系统（深色模式开关）、切换动画 | 200ms 无闪白；FOLLOW_SYSTEM 响应系统变化 |
| F-05 文件打开 | UI 测试 + 手动 | SAF 选择、微信/邮件「打开方式」唤起、最近列表、URI 失效处理 | PRD 流程 1/2 全通；异常流程按 §5.3 表现 |
| F-06 排版 | UI 测试 | 字号 5 档 × 行距 3 档全组合 | 渲染即时生效且持久化 |
| F-07 进度记忆 | UI 测试 | 阅读中段退出重进、多文档各自独立、记录清除 | 定位到上次块 ±0 误差 |
| F-08 搜索 | UI 测试 | 多结果跳转、无结果、大小写、特殊字符 | 结果数正确，高亮与跳转准确 |
| F-09 大文档 | benchmark | 1MB/2MB/5MB 各 1 份真实文档 | 滚动 ≥55fps；内存达标（§4.1） |
| F-10 编码 | 单测 | UTF-8（含/无 BOM）、GBK、GB18030、乱码文件 | 中文零乱码；无法识别时兜底 + Toast |
| F-11 导出 | UI 测试 + 手动 | HTML 导出在浏览器打开样式正确；原文分享 | 系统分享面板正常调起 |
| F-12/F-13 个性化 | UI 测试 | 强调色 3–5 色、字体 3 种切换 | 全局即时生效并持久化 |

### 7.2 安全合规验证（红线，T10，v1.0 发布前置）

| 项 | 方法 | 标准 |
| --- | --- | --- |
| 权限审计 | `aapt dump permissions` + CI 断言 | uses-permission 数量 = 0 |
| 网络流量 | 全流程抓包（安装→打开→阅读→设置→分享） | 0 字节出站 |
| 代码扫描 | CI 脚本扫描网络 API import | 0 命中 |
| 依赖审计 | 依赖树核查 vs 白名单 | 无白名单外依赖 |
| 隐私声明 | 关于页文案核对 | 明示「不收集任何数据」 |

### 7.3 性能验证（T11，每里程碑回归）

| 方法 | 内容 |
| --- | --- |
| Macrobenchmark | 冷启动/热启动 30 次取中位数（骁龙 7 系中端机） |
| 包分析 | APK Analyzer 记录分包体积，CI 超限即红 |
| 内存 | Profiler 采样首页静置 1min、1MB 文档阅读峰值 |
| 帧率 | 2MB 文档快速滚动，Jank 统计（≥55fps） |

### 7.4 稳定性测试

- **fuzz**：随机/截断/畸形 UTF-8 与 Markdown 混合输入 ≥1000 例，解析器零崩溃、零 ANR；
- **Monkey**：10 万次事件零崩溃；
- **兼容性**：API 26/29/31/34 四档模拟器 + 1 台折叠屏 + 1 台平板（横屏限宽居中，PRD §8）；
- **目标**：崩溃率 < 0.1%（PRD §8）。

---

*SPEC 完。任务执行视图见 `TASKS-任务分解文档.md`，章节与任务编号一一对应。*
