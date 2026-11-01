#!/usr/bin/env bash
# 建 release APK 並上傳到 Firebase App Distribution，發給「測試者」群組（testers）。
#
# 發新版前先把 app/build.gradle.kts 的 versionCode 加 1（否則測試者無法覆蓋安裝），
# release 簽章與金鑰都從 local.properties 讀，這台電腦要有 keystore 與 firebase login。
#
# 用法（在 repo 根目錄、Git Bash）：
#   scripts/distribute_release.sh "這版的更新說明"
#   scripts/distribute_release.sh -f notes.txt        # 更新說明放在檔案裡（多行比較方便）
#   scripts/distribute_release.sh --skip-build "..."  # 已經建好 APK，只上傳
#
# 測試者名單在 Firebase Console → App Distribution → 測試人員與群組 管理，
# 或：firebase appdistribution:testers:add --group-alias testers a@x.com b@y.com
set -euo pipefail

PROJECT="project-720a680b-3ad1-40d1-b07"
APP_ID="1:621019870351:android:ff499fa440af2d2edf2cfa"
GROUP="testers"
APK="app/build/outputs/apk/release/app-release.apk"

cd "$(dirname "$0")/.."

build=1
if [[ "${1:-}" == "--skip-build" ]]; then build=0; shift; fi

notes_args=()
if [[ "${1:-}" == "-f" ]]; then
  [[ -f "${2:-}" ]] || { echo "找不到更新說明檔：${2:-}" >&2; exit 1; }
  notes_args=(--release-notes-file "$2")
elif [[ -n "${1:-}" ]]; then
  notes_args=(--release-notes "$1")
else
  echo "請附上更新說明，例如：scripts/distribute_release.sh \"修正打卡閃退\"" >&2
  exit 1
fi

version=$(grep -oE 'versionCode = [0-9]+|versionName = "[^"]+"' app/build.gradle.kts | tr '\n' ' ')
echo "▶ 版本：$version"

if [[ $build == 1 ]]; then
  ./gradlew :app:assembleRelease --console=plain -q
fi
[[ -f "$APK" ]] || { echo "找不到 $APK，release 建置失敗？" >&2; exit 1; }

firebase appdistribution:distribute "$APK" \
  --app "$APP_ID" --project "$PROJECT" \
  --groups "$GROUP" \
  "${notes_args[@]}"
