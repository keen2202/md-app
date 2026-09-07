# 墨阅性能与帧率基准（T11/T16）

## 已自动化（本仓库可离线运行）

| 指标 | 命令 | 验收线 |
| --- | --- | --- |
| 解析 500KB JVM 回归 | `./gradlew :app:testDebugUnitTest --tests '*ParserPerformanceTest'` | JVM 回归预算 <600ms；真机目标见 PRD §7.1（≤300ms） |
| 代码块高亮 <16ms | `./gradlew :app:testDebugUnitTest --tests '*HighlighterTest'` | <16ms/块 |
| fuzz 1000 例零崩溃 | `./gradlew :app:testDebugUnitTest --tests '*ParserFuzzTest'` | 零异常 |
| CommonMark spec.txt | `./gradlew :app:testDebugUnitTest --tests '*CommonMarkSpecReportTest'` | ≥90%（当前报告 `app/build/reports/commonmark-spec-report.txt`） |
| APK 体积与权限红线 | `./gradlew :app:assembleRelease && ./ci/check_no_network.sh` | arm64 ≤6MB；uses-permission=0；网络 API=0 |

## 真机 Macrobenchmark 方案（需连接中端机）

1. 生成 Baseline Profile：`./gradlew :app:generateBaselineProfile`（AGP 内置 Art Profile 已随 release 构建）。
2. 使用 Android Studio 创建 Macrobenchmark 模块，或执行：
   `adb shell am start -W -n com.moread.app/.MainActivity` 重复 30 次取中位数。
3. 记录项：冷启动 ≤400ms、热启动 ≤150ms、首页静置 1min 空闲内存 ≤60MB、
   1MB 文档阅读内存 ≤120MB、2MB 文档快速滚动帧率 ≥55fps。
4. APK Analyzer 分析 `app/build/outputs/apk/release/app-arm64-v8a-release.apk`。

> 本开发环境无 Android 模拟器/真机，因此 PRD §7.1 的真机冷启动、内存与帧率
> 指标需在发布前于中端机执行上述方案完成签字确认；其余自动化指标已在本仓库验证。
