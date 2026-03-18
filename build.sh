#!/usr/bin/env bash
set -euo pipefail

# ============================================================
#  V380 Camera Phone APK Builder — macOS/Linux
#  Builds without Android Studio using command-line tools only.
#
#  Prerequisites:
#    - JDK 17          (brew install openjdk@17  or equivalent)
#    - Android SDK command-line tools installed at SDK_ROOT
#      containing: build-tools/35.0.0  platforms/android-35  platform-tools
#
#  Usage:
#    chmod +x build.sh
#    ./build.sh
# ============================================================

# ── Edit these two lines to match your environment ──────────
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || echo "/usr/lib/jvm/java-17-openjdk")
export SDK_ROOT="${SDK_ROOT:-/usr/local/share/android-commandlinetools}"
# ────────────────────────────────────────────────────────────

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_TOOLS="$SDK_ROOT/build-tools/35.0.0"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
ADB="$SDK_ROOT/platform-tools/adb"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

APP_SRC="$BASE_DIR/app/src/main"
OUT_DIR="$BASE_DIR/build"
APK_NAME="V380CamPhone"
KEYSTORE="$BASE_DIR/debug.keystore"
PKG="com/example/v380cam"

echo ""
echo "============================================================"
echo "  V380 Camera Phone APK Builder"
echo "============================================================"
echo ""

# [1/7] CHECK JAVA
echo "[1/7] Checking Java..."
for tool in java javac keytool; do
    if ! command -v "$tool" &>/dev/null; then
        echo "ERROR: $tool not found. Install JDK 17 and ensure it is on PATH."
        exit 1
    fi
done
java -version 2>&1 | head -1
echo "       OK"

# [2/7] VERIFY SDK TOOLS
echo ""
echo "[2/7] Verifying SDK tools..."
for f in "$AAPT2" "$ANDROID_JAR" "$D8" "$ZIPALIGN" "$APKSIGNER"; do
    if [ ! -e "$f" ]; then
        echo "ERROR: not found: $f"
        echo "       Make sure Android SDK command-line tools are installed at: $SDK_ROOT"
        exit 1
    fi
done
echo "       OK"

# [3/7] CLEAN BUILD DIRS
echo ""
echo "[3/7] Cleaning build directory..."
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/obj/$PKG"
mkdir -p "$OUT_DIR/dex"
mkdir -p "$OUT_DIR/res"
mkdir -p "$OUT_DIR/apk"
echo "       OK"

# [4/7] COMPILE RESOURCES
echo ""
echo "[4/7] Compiling resources with aapt2..."
"$AAPT2" compile --dir "$APP_SRC/res" -o "$OUT_DIR/res"

FLAT_LIST="$OUT_DIR/flat_files.txt"
rm -f "$FLAT_LIST"
find "$OUT_DIR/res" -name "*.flat" > "$FLAT_LIST"

"$AAPT2" link \
    -o "$OUT_DIR/apk/resources.ap_" \
    -I "$ANDROID_JAR" \
    --manifest "$APP_SRC/AndroidManifest.xml" \
    --java "$OUT_DIR/obj" \
    --output-text-symbols "$OUT_DIR/R.txt" \
    @"$FLAT_LIST"
echo "       OK"

# [5/7] COMPILE JAVA
echo ""
echo "[5/7] Compiling Java sources..."
SOURCES_FILE="$OUT_DIR/sources.txt"
find "$APP_SRC/java" -name "*.java"  > "$SOURCES_FILE"
find "$OUT_DIR/obj"  -name "*.java" >> "$SOURCES_FILE"

javac \
    -source 8 -target 8 \
    -classpath "$ANDROID_JAR" \
    -d "$OUT_DIR/obj" \
    @"$SOURCES_FILE"
echo "       OK"

# [6/7] DEX
echo ""
echo "[6/7] Converting to DEX with d8..."
mapfile -t CLASS_FILES < <(find "$OUT_DIR/obj" -name "*.class")
"$D8" --output "$OUT_DIR/dex" --lib "$ANDROID_JAR" "${CLASS_FILES[@]}"
echo "       OK"

# [7/7] PACKAGE + SIGN
echo ""
echo "[7/7] Packaging and signing APK..."

cp "$OUT_DIR/dex/classes.dex" "$OUT_DIR/apk/classes.dex"

UNSIGNED_APK="$OUT_DIR/apk/${APK_NAME}-unsigned.apk"
ALIGNED_APK="$OUT_DIR/apk/${APK_NAME}-aligned.apk"
SIGNED_APK="$OUT_DIR/apk/${APK_NAME}-signed.apk"

cp "$OUT_DIR/apk/resources.ap_" "$UNSIGNED_APK"
cd "$OUT_DIR/apk"
zip -j "$UNSIGNED_APK" classes.dex
cd "$BASE_DIR"

if ! "$ZIPALIGN" -f 4 "$UNSIGNED_APK" "$ALIGNED_APK" 2>/dev/null; then
    echo "       WARNING: zipalign failed, using unaligned APK"
    cp "$UNSIGNED_APK" "$ALIGNED_APK"
fi

if [ ! -f "$KEYSTORE" ]; then
    echo "       Generating debug keystore..."
    keytool -genkeypair \
        -keystore "$KEYSTORE" -alias androiddebugkey \
        -keypass android -storepass android \
        -dname "CN=Android Debug,O=Android,C=US" \
        -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12
fi

"$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey \
    --out "$SIGNED_APK" \
    "$ALIGNED_APK"

"$APKSIGNER" verify --verbose "$SIGNED_APK" 2>&1 | grep -i "verified" || true
echo "       Signing OK"

echo ""
echo "============================================================"
echo "  BUILD SUCCESSFUL"
echo "============================================================"
echo ""
echo "  Signed APK: $SIGNED_APK"
echo ""

# ── Optional: ADB install ────────────────────────────────────
if "$ADB" devices 2>/dev/null | grep -q "device$"; then
    DEVICE_ID=$("$ADB" devices | awk '/device$/{print $1}' | head -1)
    echo "Device found: $DEVICE_ID"
    echo "Installing APK..."
    "$ADB" -s "$DEVICE_ID" install -r "$SIGNED_APK"
    echo "Launching app..."
    "$ADB" -s "$DEVICE_ID" shell am start -n "com.example.v380cam/com.example.v380cam.MainActivity"
    echo ""
    echo "============================================================"
    echo "  APK installed and launched!"
    echo "============================================================"
else
    echo "No ADB device connected — skipping install."
    echo "To install manually:  adb install $SIGNED_APK"
fi
echo ""
