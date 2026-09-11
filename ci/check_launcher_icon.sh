#!/usr/bin/env bash
# MoRead 品牌图标「墨井」CI 检查（docs/BRAND-图标设计规范.md §5 / §7）。
# 用法：./ci/check_launcher_icon.sh [app模块路径]
#
# 断言五件事：
#   1. 几何硬指标：安全区余量、墨滴与笔画净空、pathData 回读（tools/gen_launcher_icon.py --check）
#   2. res 图标资源与设计源逐字节一致（--verify）：手改 res 或改了设计源忘了重出，都会失败
#   3. 规范 §5 交付物齐备（前景 / 背景 / 单色层 / 自适应声明 / 圆形入口）
#   4. 自适应图标声明引用三件套且带 Android 13+ 单色层
#   5. manifest 的 icon + roundIcon 指向存在的 mipmap（圆形启动器入口已接线）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODULE_DIR="${1:-app}"
RES="$MODULE_DIR/src/main/res"
MANIFEST="$MODULE_DIR/src/main/AndroidManifest.xml"
GEN="tools/gen_launcher_icon.py"

echo "== 1. 几何硬指标 + pathData 回读（必须全部通过） =="
python3 "$GEN" --check

echo "== 2. res 图标资源与设计源一致性 =="
python3 "$GEN" --verify

echo "== 3. 规范 §5 交付物存在性 =="
FILES=(
  "app/src/main/res/drawable/ic_launcher_foreground.xml"
  "app/src/main/res/drawable/ic_launcher_background.xml"
  "app/src/main/res/drawable/ic_launcher_monochrome.xml"
  "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"
  "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"
  "docs/brand/icon-512.png"
  "docs/brand/icon-masks.png"
  "docs/brand/icon-sizes.png"
  "docs/brand/icon-on-wallpaper.png"
  "docs/brand/icon-monochrome.png"
  "docs/brand/icon-variants.png"
)
for f in "${FILES[@]}"; do
  if [[ ! -s "$f" ]]; then
    echo "缺失或为空：$f" >&2
    exit 1
  fi
done
echo "交付物 ${#FILES[@]}/${#FILES[@]} 齐备（XML 资源 + 预览图）"

echo "== 4. 自适应图标声明（背景 / 前景 / 单色层） =="
for f in "$RES/mipmap-anydpi-v26/ic_launcher.xml" "$RES/mipmap-anydpi-v26/ic_launcher_round.xml"; do
  for layer in background foreground monochrome; do
    grep -q "<$layer android:drawable=\"@drawable/ic_launcher_$layer\"" "$f" \
      || { echo "$f 缺少 <$layer> 层" >&2; exit 1; }
  done
done
echo "ic_launcher.xml / ic_launcher_round.xml 均含 background + foreground + monochrome"

echo "== 5. manifest 图标入口（icon + roundIcon） =="
grep -q 'android:icon="@mipmap/ic_launcher"' "$MANIFEST" \
  || { echo "manifest 缺少 android:icon=\"@mipmap/ic_launcher\"" >&2; exit 1; }
grep -q 'android:roundIcon="@mipmap/ic_launcher_round"' "$MANIFEST" \
  || { echo "manifest 缺少 android:roundIcon=\"@mipmap/ic_launcher_round\"（圆形启动器入口未接线）" >&2; exit 1; }
[[ -s "$RES/mipmap-anydpi-v26/ic_launcher.xml" && -s "$RES/mipmap-anydpi-v26/ic_launcher_round.xml" ]]
echo "manifest: icon + roundIcon 均已声明且资源存在"

echo "== 品牌图标检查全部通过 =="
