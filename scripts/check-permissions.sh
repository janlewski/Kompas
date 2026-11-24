#!/usr/bin/env bash
# Fails when the release APK permissions differ from scripts/allowed-permissions.txt.
set -euo pipefail

APK="${1:?path to release apk}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ALLOW="$ROOT/scripts/allowed-permissions.txt"

if [[ -n "${ANDROID_HOME:-}" ]]; then
  SDK="$ANDROID_HOME"
elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  SDK="$ANDROID_SDK_ROOT"
else
  SDK="$HOME/Library/Android/sdk"
fi

AAPT="$(find "$SDK/build-tools" -name aapt -type f | sort | tail -1)"
if [[ -z "$AAPT" ]]; then
  echo "aapt not found under $SDK/build-tools" >&2
  exit 2
fi

found="$(mktemp)"
allowed="$(mktemp)"
trap 'rm -f "$found" "$allowed"' EXIT

"$AAPT" dump permissions "$APK" | sed -n "s/.*name='\([^']*\)'.*/\1/p" | sort -u >"$found"
grep -v '^#' "$ALLOW" | grep -v '^[[:space:]]*$' | sort -u >"$allowed"

if ! diff -u "$allowed" "$found"; then
  echo "Release permissions do not match the allow-list." >&2
  exit 1
fi

echo "Permissions match the allow-list."
