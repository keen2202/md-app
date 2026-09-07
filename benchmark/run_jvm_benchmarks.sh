#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :app:testDebugUnitTest \
  --tests '*ParserPerformanceTest' \
  --tests '*HighlighterTest' \
  --tests '*ParserFuzzTest' \
  --tests '*CommonMarkSpecReportTest'
echo "JVM 基准完成，报告见 app/build/reports/"
