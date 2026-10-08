#!/usr/bin/env bash
# Builds the debug APK and, if the build succeeds, commits all source changes and pushes them to GitHub.
# Usage: ./build-apk.sh "short description of what changed"
set -e
cd "$(dirname "$0")"
./gradlew :app:assembleDebug
MESSAGE="${1:-APK build $(date '+%Y-%m-%d %H:%M')}"
git add -A
if git diff --cached --quiet; then
  echo "No source changes since the last commit - nothing to push."
else
  git commit -m "$MESSAGE"
fi
git push origin HEAD
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
