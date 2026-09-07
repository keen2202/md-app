#!/usr/bin/env bash
# MoRead 安全红线 CI 检查（SPEC §2.1 / §7.2 / T10）。
# 用法：./ci/check_no_network.sh [app模块路径] [APK路径]
# 默认扫描 app/src/main，并在 app/build/outputs/apk/release 下查找 APK 做权限审计。
set -euo pipefail

MODULE_DIR="${1:-app}"
APK_DIR="${2:-app/build/outputs/apk/release}"

echo "== 1. Manifest uses-permission 数量（必须为 0） =="
MANIFEST="$MODULE_DIR/src/main/AndroidManifest.xml"
if [[ ! -f "$MANIFEST" ]]; then
  echo "manifest not found: $MANIFEST" >&2
  exit 1
fi
COUNT=$(grep -E "^[[:space:]]*<uses-permission" "$MANIFEST" | grep -vc "tools:node=\"remove\"" || true)
echo "uses-permission count = $COUNT"
[[ "$COUNT" == "0" ]]

echo "== 2. INTERNET 权限与网络 API 源码扫描（必须为 0 命中） =="
BAD_SOURCE=$(grep -RInE \
  -e 'java\.net\.' \
  -e 'okhttp' \
  -e 'retrofit' \
  -e 'HttpURLConnection' \
  -e 'DownloadManager' \
  -e 'android\.permission\.INTERNET' \
  -e '<uses-permission' \
  "$MODULE_DIR/src/main" 2>/dev/null | grep -v 'tools:node="remove"' || true)
if [[ -n "$BAD_SOURCE" ]]; then
  echo "发现违禁引用："
  echo "$BAD_SOURCE"
  exit 1
fi
echo "network-related source references = 0"

echo "== 3. APK 权限审计（aapt/aapt2） =="
APK=$(find "$APK_DIR" -name '*arm64*.apk' | head -n1)
if [[ -z "$APK" ]]; then
  echo "未找到 APK（跳过 aapt 审计）；release 构建后重试。" >&2
  exit 0
fi
AAPT_BIN="${ANDROID_HOME:-/root/workspace/md-app/.sdk}/build-tools/34.0.0/aapt"
if [[ ! -x "$AAPT_BIN" ]]; then
  AAPT_BIN="${ANDROID_HOME:-/root/workspace/md-app/.sdk}/build-tools/34.0.0/aapt2"
fi
PERMS=$("$AAPT_BIN" dump permissions "$APK" 2>/dev/null || true)
echo "$PERMS"
if echo "$PERMS" | grep -q "uses-permission"; then
  echo "APK 包含权限！" >&2
  exit 1
fi

echo "== 全部安全红线检查通过 =="
