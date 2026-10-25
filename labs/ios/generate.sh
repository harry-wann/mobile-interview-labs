#!/usr/bin/env bash
# 用 XcodeGen 依 project.yml 產生 MobileLabs.xcodeproj。
# 平常不用跑：新增演練檔案只要放進 Practice／Solution 的資料夾，Xcode 會自動看到。
# 只有改了 project.yml（例如加 Info.plist 權限說明）才需要重新產生。
set -euo pipefail
cd "$(dirname "$0")"
command -v xcodegen >/dev/null || { echo "需要 XcodeGen：brew install xcodegen"; exit 1; }
xcodegen generate --spec project.yml --quiet
echo "已產生 MobileLabs.xcodeproj"
