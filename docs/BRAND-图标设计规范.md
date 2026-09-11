# 墨阅 MoRead 品牌图标规范 ——「墨井」

> 版本 v1.0 ｜ 适用范围：启动图标（adaptive icon）、商店主视觉、后续所有对外物料
> 资产可复现生成：`python3 tools/gen_launcher_icon.py`

---

## 1. 设计概念

**Markdown 的标题标记 `#`，在中文排版里就叫「井字号」——即汉字「井」。**

「墨阅」二字取「墨」与「阅」；把 `#` 写成书法四笔的「井」，井心悬一滴未散的墨，
得到的正是 **「墨井」**：一口以墨注满的井。它同时说清了三件事——

| 图形元素 | 说的是 |
| --- | --- |
| 井（`#`） | 这是一款 **Markdown** 阅读器（标题标记是 Markdown 最有辨识度的符号） |
| 四笔书法笔锋 | **墨**：手写的、纸上的、非字体的 |
| 井心一滴墨 | **阅**：内容被汲取的那一滴（也是全图唯一的彩色） |

## 2. 记忆点：为什么它记得住

一个图标只该有一个被记住的东西。这枚图标把「记忆点」分了三层，逐层加深：

1. **剪影层（0.3 秒）**：井字四笔——在应用抽屉里一眼可辨，且和所有「文档 + 折角」的
   Markdown 应用图标彻底区隔开。
2. **手感层（1 秒）**：四笔不是字体描出来的。左右竖各带 2°–4° 侧势、两条横右行微提
   （约 2.7°）、起收笔有粗细变化——像刚写完、墨还没干。
3. **焦点层（细看）**：井心那滴强调蓝的墨。全图只有它是彩色，视线最终一定落在它上面；
   它是「墨」的具象，也是应用强调色 `#7FA8D9` 的锚点。

> 一句话描述：**白井黑底，井心一滴蓝墨。**

## 3. 几何规范

画布为 Android 自适应图标的 108×108dp，四周 18dp 可能被遮罩裁掉。

| 项 | 数值 | 说明 |
| --- | --- | --- |
| 画布 | 108 × 108 dp | adaptive icon 标准 |
| 安全区 | 半径 33（66dp 圆） | 圆形遮罩下保证可见 |
| 内容包围盒 | x 28.7–79.0，y 27.9–79.7 | 50.3 × 51.8，近似正方形 |
| 最大半径 | **30.39** | 距圆心最远点，安全余量 **+2.61** |
| 笔画宽度 | 7.4–8.2 | 48dp 下约 3.3–3.6dp，起收笔有粗细差 |
| 井心净空 | 约 18.3 × 18.3 | 墨滴左右净空 ≈ 5，上下净空 ≈ 3.1 |
| 墨滴 | 宽 9.0 / 高 12.1，尖端朝上 | 48dp 下约 4.0 × 5.4dp |

四笔的两端都是圆头（笔锋），笔画之间留出井心的空腔——墨滴不与任何笔画相接
（最小净空 3.06，48dp 下约 1.4dp）。

## 4. 配色

沿用 PRD §6.3 色板，不引入新色。

| 用途 | 色值 | 来源 | 对比度 |
| --- | --- | --- | --- |
| 背景（渐变起点） | `#1E222B` | 夜间卡片色提亮 | — |
| 背景（渐变终点） | `#12141A` | 夜间背景色压深 | — |
| 井字四笔 | `#FAFAF7` | 日间背景色（纸白） | 15.2–17.6 : 1 |
| 井心墨滴 | `#7FA8D9` | 夜间强调色 | 6.5–7.5 : 1 |

深色墨底 + 纸白笔画是「阴文印章」的读法：底色是墨，字形留白。
笔画与墨滴的对比度均在 6:1 以上，远超 WCAG AA 的 4.5:1。

## 5. 交付物

| 文件 | 用途 |
| --- | --- |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | 前景：井字四笔 + 井心墨滴 |
| `app/src/main/res/drawable/ic_launcher_background.xml` | 背景：墨色对角渐变 |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | 单色层（Android 13+ 主题图标） |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | 自适应图标声明 |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | 圆形启动器入口 |
| `app/src/main/AndroidManifest.xml` | 入口接线：`android:icon` + `android:roundIcon` |
| `docs/brand/icon-512.png` | 512×512 主图（商店 / 物料） |
| `docs/brand/icon-masks.png` | 圆形 / 方圆 / 圆角三种遮罩观感 |
| `docs/brand/icon-sizes.png` | 48 / 32 / 24 / 16 dp 尺寸阶梯 |
| `docs/brand/icon-on-wallpaper.png` | 浅色与深色壁纸下的观感 |
| `docs/brand/icon-monochrome.png` | 主题图标（单色层）示意 |
| `docs/brand/icon-variants.png` | 墨底（默认）与纸白（备选）配色对比 |
| `tools/gen_launcher_icon.py` | 几何唯一来源，生成上述全部产物 |
| `ci/check_launcher_icon.sh` | CI 守门：几何硬指标 + res 与设计源一致 + manifest 入口 |

## 6. 适配说明

- **自适应图标**：minSdk 26，所有目标设备均使用 108dp 自适应图标，无需位图回退。
- **入口接线**：manifest 同时声明 `android:icon="@mipmap/ic_launcher"` 与
  `android:roundIcon="@mipmap/ic_launcher_round"`——请求圆形图标的启动器走 `roundIcon`，
  否则回落到 `icon`；两者指向同一套自适应图标，观感一致。
- **遮罩**：圆形、方圆（squircle）、圆角方三种主流遮罩下均无裁切（余量 +2.61）。
- **Android 13+ 主题图标**：已提供 `<monochrome>` 单色层，井心墨滴保留为剪影，
  系统着色后仍能看出「井中一点」。
- **小尺寸**：48dp 下井与墨滴清晰可辨；32/24dp 下井字结构完整；16dp 下仍可读出
  「两横两竖夹一点」。**低于 16dp 请勿使用本图标。**
- **换图标后的缓存**：部分启动器会缓存旧图标，安装新版本后若未刷新，
  可重启启动器或重装应用。

## 7. 可复现与自检

图标几何只存在于 `tools/gen_launcher_icon.py` 一处，改一处即可重出全部资源与预览：

```bash
python3 tools/gen_launcher_icon.py            # 生成 XML 资源 + 预览图
python3 tools/gen_launcher_icon.py --preview  # 只重出预览图
python3 tools/gen_launcher_icon.py --check    # 几何体检 + pathData 回读校验
python3 tools/gen_launcher_icon.py --verify   # 校验 res 未被手改（只读，CI 用）
python3 tools/gen_launcher_icon.py --ascii    # 终端 ASCII 校对（无需看图）

./ci/check_launcher_icon.sh                   # 上面两步 + 交付物/manifest 入口检查
```

`--check` 会核对四项硬指标并断言 `pathData` 回读一致性（不通过即退出码 1）：

```
最大半径 30.39 / 安全半径 33.0（余量 +2.61）
内容包围盒 x 28.7–79.0（宽 50.3）  y 27.9–79.7（高 51.8）
墨滴与笔画最小净空 3.06（>0 即不相接）
笔画宽度 7.4–8.2（48dp 下约 3.3–3.6dp）
pathData 回读校验（井字四笔）：最大偏差 0.0046 → OK
pathData 回读校验（墨滴）：最大偏差 0.0017 → OK
```

> 回读校验会按 SVG 语义重新解析生成的 `pathData` 并与几何比对。
> 圆弧的 `largeArc` 标志一旦写错（例如墨滴底部是 287° 的优弧却标成 `0`），
> 偏差会立刻超标——这类错误在静态预览里看不出来，只有真机渲染才暴露。

`--verify` 管的是另一件事：**磁盘上的 res 是否仍是设计源的输出**。手改 res、或改了
`tools/gen_launcher_icon.py` 里的几何却忘了重出资源，都会以退出码 1 失败。
CI（`.github/workflows/release.yml`）在单测之前跑 `ci/check_launcher_icon.sh`，
把「几何硬指标 / 资源一致 / 交付物齐备 / 自适应三件套 / manifest 入口」一起作为发布前置条件。

> 预览图 `docs/brand/*.png` 由 Pillow 栅格化，图形本身逐像素可复现，但
> `icon-masks|sizes|variants.png` 上的文字标签依赖本机字体，字节会随环境变化；
> 因此一致性校验只覆盖 XML 资源，不比对预览图字节。

## 8. 备选配色：纸白版

若希望图标与日间「纸感阅读」更贴近，可切换为纸白底 / 墨字（预览见
`docs/brand/icon-variants.png` 右图）：

| 用途 | 色值 |
| --- | --- |
| 背景 | `#FFFFFF` → `#F2F0E9` 渐变 |
| 井字四笔 | `#2B2B2B`（正文色） |
| 井心墨滴 | `#3A6EA5`（日间强调色，对比度 5.1:1） |

正式资源默认采用**墨底版**：深色底在浅色与深色壁纸上都稳定成立，
且「墨」的语义更直接。

## 9. 使用红线

- ✗ 不要旋转、拉伸、倾斜整枚图标；不要给笔画加描边、投影、发光。
- ✗ 不要改墨滴的位置与大小——它必须落在井心、且不与任何笔画相接。
- ✗ 不要替换背景色或把渐变改成纯色以外的样式；不要给墨滴换色。
- ✗ 不要单独取出井心墨滴、或去掉墨滴后单独使用井字。
- ✗ 不要在小于 16dp 的场景使用；不要与文字并排时强行对齐基线。
- ✓ 需要单色场景时使用 `<monochrome>` 单色层，由系统着色，不要自行染成灰色。
