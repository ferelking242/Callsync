#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# CallSync Client — Release build script
# Usage: ./build_release.sh [keystore.jks] [store_pass] [key_alias] [key_pass]
# If no keystore args provided, builds unsigned release APK.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

echo "╔══════════════════════════════════════╗"
echo "║  CallSync Client — Release Builder   ║"
echo "╚══════════════════════════════════════╝"

# Check Flutter is installed
if ! command -v flutter &>/dev/null; then
  echo "❌  Flutter SDK not found. Install from https://flutter.dev"
  exit 1
fi

flutter --version

# Get dependencies
echo ""
echo "📦  Getting dependencies…"
flutter pub get

# Run analysis (non-fatal)
echo ""
echo "🔍  Running analysis…"
flutter analyze --no-fatal-infos || true

# Build
echo ""
if [ "${1:-}" != "" ]; then
  echo "🔑  Building SIGNED release APK…"
  export KEYSTORE_PATH="${1}"
  export STORE_PASSWORD="${2:-}"
  export KEY_ALIAS="${3:-upload}"
  export KEY_PASSWORD="${4:-}"
fi

flutter build apk --release \
  --obfuscate \
  --split-debug-info=build/debug-info \
  --tree-shake-icons

APK="build/app/outputs/flutter-apk/app-release.apk"

echo ""
echo "✅  Build complete!"
echo "   APK : $APK"
echo "   Size: $(du -sh "$APK" | cut -f1)"
echo ""
echo "Install on connected device:"
echo "   adb install -r $APK"
