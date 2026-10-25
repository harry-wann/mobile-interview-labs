#!/usr/bin/env bash
# 不開 Xcode，在模擬器上建置並自動檢查。
#
#   ./check.sh                                         # 只建置
#   ./check.sh selftest Swift01ValueSemantics          # 跑解答的自動檢查：Bug 版要重現問題、修正版要正確
#   ./check.sh practice Swift01ValueSemantics          # 跑練習的骨架：確認不會當掉，並列出還沒寫完的演練
#   ./check.sh run Swift01ValueSemantics L05 solution bug   # 只跑一個版本（確認 Bug 版真的會當掉）
#   ./check.sh open Swift01ValueSemantics L03 [solution]    # 打開某個演練並截圖到 /tmp/labs-shot.png
#
# 模擬器：預設用名為 MobileLabs-Main 的模擬器（只開這一台，多個檢查同時跑時會自動排隊）。
# Release 建置：LABS_CONFIG=Release ./check.sh selftest …（量時間的演練，Debug 與 Release 差很多時用）
set -euo pipefail
cd "$(dirname "$0")"

sim_name="${LABS_SIM:-MobileLabs-Main}"
# 建置暫存不能放在 Desktop 底下（iCloud 留下的檔案屬性會讓簽章失敗），預設放 Xcode 的 DerivedData
dd="${LABS_DD:-$HOME/Library/Developer/Xcode/DerivedData/MobileLabs-check}"
bundle=tw.harry.MobileLabs
config="${LABS_CONFIG:-Debug}"

[ -d MobileLabs.xcodeproj ] || ./generate.sh

sim=$(xcrun simctl list devices available -j | /usr/bin/python3 -c "
import json, sys
name = sys.argv[1]
for runtime, devs in json.load(sys.stdin)['devices'].items():
    for d in devs:
        if d['name'] == name or d['udid'] == name:
            print(d['udid']); sys.exit()
" "$sim_name")
if [ -z "$sim" ]; then
  echo "找不到模擬器「$sim_name」，先建立一個：xcrun simctl create $sim_name com.apple.CoreSimulator.SimDeviceType.iPhone-17 <iOS runtime>"
  exit 1
fi
xcrun simctl boot "$sim" 2>/dev/null || true

echo "▸ 建置"
mkdir -p "$dd"
if ! xcodebuild build -project MobileLabs.xcodeproj -scheme MobileLabs -configuration "$config" \
  -destination "platform=iOS Simulator,id=$sim" -derivedDataPath "$dd" -quiet > "$dd/build.log" 2>&1; then
  grep -E "error:" "$dd/build.log" | sort -u | head -30
  echo "建置失敗，完整 log：$dd/build.log"
  exit 1
fi
# Practice 因為挖空會有「never used」之類的警告，這裡只列 Solution、LabKit、App 的警告
grep -E "warning:" "$dd/build.log" | grep -E "/(Solution|LabKit|App)/" | sort -u | head -30 || true
app="$dd/Build/Products/$config-iphonesimulator/MobileLabs.app"

# 模擬器只有一台、大家共用：安裝與執行要排隊（建置可以同時進行，各自用自己的 LABS_DD）
lock="/tmp/mobilelabs-sim-$sim.lock"
until mkdir "$lock" 2>/dev/null; do
  holder=$(cat "$lock/pid" 2>/dev/null || true)
  if [ -n "$holder" ] && ! kill -0 "$holder" 2>/dev/null; then rm -rf "$lock"; continue; fi   # 持有者已結束：清掉
  echo "▸ 模擬器正在被別的檢查使用，等待中…"
  sleep 5
done
echo $$ > "$lock/pid"
trap 'rm -rf "$lock"' EXIT
xcrun simctl install "$sim" "$app"
xcrun simctl terminate "$sim" "$bundle" 2>/dev/null || true

mode="${1:-build}"
case "$mode" in
  build) echo "建置完成" ;;
  selftest)
    xcrun simctl launch --console-pty "$sim" "$bundle" -selftest ${2:-} 2>&1 | grep -E "^\[SELFTEST\]" || true ;;
  practice)
    xcrun simctl launch --console-pty "$sim" "$bundle" -selftest-practice ${2:-} 2>&1 | grep -E "^\[SELFTEST\]" || true ;;
  run)
    [ $# -ge 5 ] || { echo "用法：./check.sh run <代號> <L0x> <practice|solution> <bug|fixed>"; exit 1; }
    out=$(xcrun simctl launch --console-pty "$sim" "$bundle" -run "$2" "$3" "$4" "$5" 2>&1 | grep -vE "Requested font|^$bundle" || true)
    echo "$out"
    # 正常結束會印「跑完，沒有當掉」；沒有這行代表 App 在途中當掉（或卡住超過 simctl 的等待時間）
    if ! grep -qE "跑完，沒有當掉|找不到" <<<"$out"; then echo "[SELFTEST] $2 $3 $4 $5 沒有跑完：App 當掉了（或卡住）"; fi ;;
  open)
    xcrun simctl launch "$sim" "$bundle" -open "$2" ${3:-} -source "${4:-practice}" >/dev/null
    sleep 3
    xcrun simctl io "$sim" screenshot "${LABS_SHOT:-/tmp/labs-shot.png}" >/dev/null 2>&1
    echo "截圖：${LABS_SHOT:-/tmp/labs-shot.png}" ;;
  *) echo "不認得的模式：$mode"; exit 1 ;;
esac
