#!/usr/bin/env bash
# Build a signed, R8-optimised Archon release and publish it where the deck serves /app.apk
# (PIN-gated, tailnet only). Pattern: ~/personal/tasks/android/release.sh.
# Usage: android/release.sh        (from anywhere)
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/opt/jdk-21}" ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export GRADLE_OPTS="${GRADLE_OPTS:--Djava.net.preferIPv4Stack=true}"
export PATH="$JAVA_HOME/bin:$PATH"            # apksigner runs on java
cfg="$HOME/.config/archon-android"
[ -f "$cfg/keystore.properties" ] || { echo "no release key config in $cfg" >&2; exit 1; }
[ -z "$(git status --porcelain -- .)" ] || echo "warning: android/ has uncommitted changes; versionCode comes from the commit count" >&2
./gradlew :app:assembleRelease --console=plain -q
apk=app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME"/build-tools/*/apksigner verify "$apk" >/dev/null || { echo "release APK is not signed" >&2; exit 1; }
out="$HOME/.local/share/phone-deck/android"; mkdir -p "$out"
cp "$apk" "$out/archon.apk.tmp" && mv "$out/archon.apk.tmp" "$out/archon.apk"
ver=$("$ANDROID_HOME"/build-tools/*/aapt2 dump badging "$apk" 2>/dev/null | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
printf '{"version": "%s", "built": "%s"}\n' "$ver" "$(date -Iseconds)" > "$out/archon.apk.json"
url=$(sed -n 's/^deckUrl=//p' "$cfg/archon.properties" 2>/dev/null)
echo "published $ver ($(du -h "$apk" | cut -f1)) → ${url:-<deck>}/app.apk"
