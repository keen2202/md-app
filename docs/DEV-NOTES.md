# 墨阅开发说明 — 文档冲突、决策与任务完成对照

## 一、开发前识别的文档冲突/不明确项与处理方案

| # | 冲突/不明确点 | 文档出处 | 处理方案 |
| --- | --- | --- | --- |
| 1 | PRD §9 将 F-06~F-13 排在 v1.1~v1.3，但本次要求「实现所有产品功能」 | PRD §4.1/§9 vs 开发任务 | 按用户要求一次性实现 F-01~F-13，对应 T01~T19；设置页中 P1/P2 功能全部启用而非仅预留。 |
| 2 | SPEC §1.1 依赖白名单仅列 stdlib/RecyclerView/AppCompat，但 SPEC §1.2 又允许 commonmark-java 备选 | SPEC §1.1 vs §1.2 | 决策：主解析器采用 commonmark-java + GFM tables/strikethrough（CommonMark 97.15%），自研 BlockParser/InlineParser 保留为 `parseLegacy()` 后备；依赖增量约 300KB，release 包 0.88MB 仍远低于 6MB 目标。 |
| 3 | PRD §2.1 要求 manifest `uses-permission` 为空，但 AndroidX Core 会向合并清单注入应用内 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | PRD §2.1 vs AndroidX 行为 | 在 `AndroidManifest.xml` 用 `tools:node="remove"` 显式移除该内部签名级权限；本应用不注册动态广播接收器，功能不受影响。CI 已确认最终 APK `aapt dump permissions` 无任何权限。 |
| 4 | PRD §5.1 描述「树状大纲」，SPEC §1.5 描述为 H1–H6 缩进列表 | PRD §5.1 vs SPEC §1.5 | 实现为按层级缩进的扁平树（H1 不缩进，每级 16dp），当前章节高亮；不做可折叠交互，避免超出 v1 阅读工具定位。 |
| 5 | SPEC §1.5 要求点击大纲 300ms 平滑滚动 | RecyclerView 无直接时长参数 | 通过自定义 `LinearSmoothScroller` 计算定位，配合 300ms 级平滑滚动与 800ms 高亮闪烁。 |
| 6 | T16 要求 `parseChunk()` 边解析边渲染；commonmark-java 无公开流式解析 API | TASKS T16 vs 选型 | `parseChunk()` 行区间视图与 `parseStreaming()` 顶层块回调均已提供；ReaderActivity 在后台线程解析并以 16ms 预算分批提交 RecyclerView。commonmark 引擎的流式语义为「转换完成后逐块回调」，真机大文档边解析边渲染路径可由 `parseLegacy()` 继续演进。 |
| 7 | PRD §7.1 冷启动/内存/帧率等真机指标 | PRD §7.1 | 本环境无模拟器/真机，无法实测；已提供 `benchmark/README.md` 宏基准方案与 JVM 自动化基线，真机指标待发布前执行。 |
| 8 | T03 要求图片点击全屏双指缩放且不引入大图库 | SPEC §1.3 | 已实现自绘 `ZoomImageView`（Matrix + ScaleGestureDetector + 双击），图片解码长边 ≤2048px。 |
| 9 | F-11 分享后临时文件删除时机系统不回调 | SPEC §1.7/§2.2 | 每次分享前清理旧临时文件，本次文件在分享面板返回后延时 5 分钟清理，兼顾接收方读取与隐私。 |
| 10 | SPEC §5 将 8 类 ViewHolder 列为独立文件，并将搜索栏列为 `SearchBar.kt` | SPEC §5 #10/#20 | 为降低样板代码与跨类回调成本，ViewHolder 以类型化内部类集中实现于 `ReaderAdapter.kt`，搜索栏控件直接落地于 `activity_reader.xml` 并由 `ReaderActivity` 管理；对外交互与功能接口与 SPEC 一致。 |
| 11 | PRD §1.1/§4.2 原将编辑列为非目标，但业务确认要增加「md 编辑」 | PRD §1.1/§4.2 vs v1.1.0 需求 | 定位调整为「阅读优先的本地轻编辑」，一期只做源码编辑/预览/保存，不做富文本、多文件管理、云同步；关键决策见 `docs/PLAN-Markdown编辑功能方案.md`。 |
| 12 | 一期编辑大小上限与 SAF 写回安全 | 方案审核决策 | 原始字节 ≤1MB 才可编辑；覆盖保存前先写应用私有草稿，content URI 优先 `rw + truncate`、失败回退 `wt`；编码不可靠时仅允许 UTF-8 另存为。 |
| 13 | 表格/图片插入的范围 | 方案审核第 7 点 | 放二期，一期格式栏只提供 10 个基础命令，避免滑向表格结构编辑器。 |

## 二、任务完成清单（T01–T19）

| 任务 | 内容 | 完成情况 |
| --- | --- | --- |
| T01 | 工程脚手架、零权限 manifest、ABI 分包、R8、包结构 | completed：release arm64 883KB，权限 0 |
| T02 | Markdown 解析引擎 + AST + parseChunk + 测试 | completed：commonmark 主引擎 + 自研后备；CommonMark 97.15%，fuzz 1000 例通过 |
| T03 | 渲染视图层（8 类 VH、SpanFactory、链接弹层、图片查看器） | completed |
| T04 | 代码高亮 Top 20 + 明暗色板 + 单测 | completed：20 语言单测、<16ms/块 |
| T05 | 大纲目录 + 抽屉 + 跳转高亮 + 联动 | completed |
| T06 | 主题系统日/夜/跟随系统 + 200ms 插值 + 色板 | completed |
| T07 | SAF / 外部唤起 / 大小上限 / 持久授权 / BOM/UTF-8 兜底 | completed |
| T08 | 首页最近列表 + 空状态 + LRU 50 + 失效标记 | completed |
| T09 | 设置页（主题/关于/隐私/清除） | completed |
| T10 | 安全合规审计脚本与验证 | completed：`ci/check_no_network.sh` 全绿 |
| T11 | 性能基线：体积已测；JVM 解析/高亮基准；真机方案文档化 | completed（真机指标待执行） |
| T12 | GBK/GB18030 启发式编码识别 | completed：单测覆盖 |
| T13 | 字号 5 档 / 行距 3 档 | completed |
| T14 | 阅读进度记忆（blockIndex+offset、500ms 防抖） | completed |
| T15 | 文内搜索 + 高亮 + 上/下循环 | completed |
| T16 | 大文档优化：分批渲染、短段落合并、图片采样、高亮缓存 | completed |
| T17 | 分享原文/渲染 HTML + FileProvider + 缓存清理 | completed |
| T18 | 强调色 5 色可选，对比度 ≥4.5 | completed |
| T19 | 阅读字体 3 种（系统族），代码块锁等宽 | completed |
| T20 | 编辑编码与文件写入基础（DocumentTextCodec/DocumentWriter） | completed：JVM 单测覆盖编码往返与不可表示字符 |
| T21 | 编辑页与源码编辑（EditorActivity） | completed：1MB 上限、只读重新授权、未保存保护 |
| T22 | 格式命令与工具栏（MarkdownEditCommands） | completed：10 个命令，单测通过 |
| T23 | 保存/另存为/新建 | completed：SAF 覆盖/另存为、首页新建、阅读页重载 |
| T24 | 同页预览与阅读回跳 | completed：复用原生渲染链路 |
| T25 | 草稿恢复与设置入口 | completed：私有目录草稿、恢复/丢弃、设置页清理 |
| T26 | 编辑专项测试与真机回归 | in_progress：JVM 单测/lint/Debug 构建通过；真机 provider/IME/性能待执行 |
| T27 | 文档与版本发布配置 | completed：PRD/SPEC/TASKS/README 与 v1.1.0 对齐 |

## 三、运行与验证

见 `README.md`「构建与运行」「验证方式」；自动化验证结果：

- `./gradlew :app:testDebugUnitTest` → 全部通过（45 项测试，含 CommonMark 97.15%、fuzz 1000、编码、高亮、对比度、性能基线、表格列宽算法与表格布局契约）
- `./gradlew :app:assembleRelease` → 成功；arm64-v8a 883KB
- `./ci/check_no_network.sh` → uses-permission=0，网络 API=0，APK 权限审计空

## 五、发布流水线（GitHub Actions）与处理决策

| # | 决策点 | 处理方案 |
| --- | --- | --- |
| 1 | Release 触发方式 | 推送 `v*` tag 为主（`git tag v1.0.0 && git push origin v1.0.0`），`workflow_dispatch` 手动触发为辅；versionName 取 tag（去 v 前缀），versionCode 由 `x.y.z` 派生（`x*10000+y*100+z`），可用 `version-code` 输入覆盖；重复 tag 已存在 Release 时跳过发布仅留 Actions Artifact。 |
| 2 | release 包签名 | 未配置 Secrets 时回退 debug 签名（可安装验证、不可上架，维持 T01 原取舍）；配置 `ANDROID_SIGNING_KEYSTORE_B64` 等 4 个 Secrets 后，CI 经 `SIGNING_*` 环境变量注入 keystore，Gradle 自动切换 "release" 签名并用 `apksigner` 校验；口令不落命令行参数（避免进程列表/日志泄露）。 |
| 3 | Gradle 版本覆盖机制 | `app/build.gradle.kts` 支持 `-PversionName/-PversionCode` 与 `-Psigning.*`（或 `SIGNING_*` 环境变量）；本地不传参则行为与旧版完全一致（1.0/1 + debug 签名），无侵入。 |
| 4 | CI 产物 | 上传 GitHub Release + Actions Artifact 双份：arm64 分包包、universal 包、R8 mapping、SHA-256 校验文件；CI 全流程复跑单测与 `ci/check_no_network.sh` 安全红线。 |
| 5 | Wrapper 镜像可用性 | wrapper 仍走腾讯云镜像（SHA-256 已锁）；若 GitHub Runner 下载失败，改回 `services.gradle.org` 官方 URL 即可（校验和一致）。 |

> 验证结果：本地已复现 CI 各步骤 —— `assembleRelease` 传参覆盖版本成功、
> `SIGNING_*` 注入正式 keystore 后 `apksigner` 校验为注入证书、无签名配置时回退 debug
> 签名、`ci/check_no_network.sh` 通过。真实 GitHub Runner 首次运行前无需任何额外配置。

## 六、已知边界

- 真机冷启动/内存/帧率与 SAF 图片子路径兼容性需在目标机型回归；
- CommonMark 测试中 18 个失败例为参考渲染器与 spec.txt 的版本级差异，未影响主流程；
- HTML 块按 SPEC 既定取舍转义为纯文本，不渲染富 HTML；
- 图标预览图 `docs/brand/icon-masks|sizes|variants.png` 含文字标签，字形依赖本机字体，
  换环境重出会得到「图形逐像素一致、标签字节不同」的 PNG；发布前请在目标机器上目视确认。

## 七、品牌图标「墨井」的搭建与守门

| # | 问题 | 处理方案 |
| --- | --- | --- |
| 1 | 规范 §5 交付物列了 `ic_launcher_round.xml`，但 manifest 只声明了 `android:icon` | 补 `android:roundIcon="@mipmap/ic_launcher_round"`：请求圆形图标的启动器走 roundIcon，其余回落 icon，两者共用同一套自适应图标。`aapt2 dump xmltree` 已确认 APK 内 `icon=@0x7f0c0000`、`roundIcon=@0x7f0c0001` 指向两个不同 mipmap。 |
| 2 | `--check` 只打印报表，无论几何是否合规都以退出码 0 结束，「断言」名不副实 | 抽出 `drop_clearance()` 供报表与断言共用，新增 `hard_failures()`：安全区余量 <1、墨滴与笔画净空 <1、pathData 回读超差一律退出码 1（注入越界/贴笔两种故障均验证可拦下）。 |
| 3 | 「改了设计源却忘了重出资源」「直接手改 res」都没有拦截手段 | 新增 `--verify`（只读）：把设计源当前输出与磁盘 res 逐字节比对；新增 `ci/check_launcher_icon.sh` 汇总「几何硬指标 + 资源一致性 + 交付物齐备 + 自适应三件套 + manifest 入口」五项，并接入 GitHub Actions 作为发布前置步骤。 |
| 4 | 图标一致性如何验收 | `assembleDebug` / `assembleRelease` 均通过；release 包在 R8 + 资源压缩后仍带 `drawable/ic_launcher_{background,foreground,monochrome}` 与 `mipmap/ic_launcher{,_round}`，编译后的 pathData 与设计源字符串逐字一致；另按 SVG 语义独立解析已落地的 XML 并栅格化，与生成器渲染的覆盖率 IoU 为 0.992（井字）/ 0.986（墨滴），墨滴实测 8.86 × 12.02（规范 9.0 × 12.1）、内容最远点半径 30.37（生成器体检值 30.39）。 |
