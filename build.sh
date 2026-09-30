#!/usr/bin/env bash
#
# LuminPro 磁贴 —— 无 Gradle 构建脚本
#
# 只用 Android SDK 自带的 aapt2 / d8 / zipalign / apksigner，加上 JDK 的 javac 和 keytool。
# 这样不依赖网络（无需下载 Gradle 发行版与 AGP 依赖），也不会被 JDK 版本与 AGP 的
# 兼容矩阵卡住 —— 应用本身不使用 AndroidX，纯平台 API 就够。
#
# 用法:
#   ./build.sh          构建并签名，产物在 build/LuminProTile.apk
#   ./build.sh clean    清理构建目录
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

APP_NAME="LuminProTile"
PKG="com.luminpro.tile"
MIN_SDK=26
TARGET_SDK=36
OUT="$HERE/build"

cyan()  { printf '\033[36m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
die()   { printf '\033[31m错误: %s\033[0m\n' "$*" >&2; exit 1; }

# 转成 Windows 程序能读懂的路径。
# MSYS/Git Bash 的 find 会吐出 /d/tools/... 这种 POSIX 路径；直接作为命令行参数时
# MSYS 会自动转换，但写进 @argfile 的内容不会 —— javac 会把它解析成 \d\tools\...。
# 用 -m（混合式 D:/tools/...）而不是 -w：反斜杠在 javac 的 argfile 里是转义字符。
winpath() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s\n' "$1"
    fi
}

if [ "${1:-}" = "clean" ]; then
    rm -rf "$OUT"
    green "已清理 $OUT"
    exit 0
fi

# ── 平台差异 ────────────────────────────────────────────────────────────────
EXE=""
case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) EXE=".exe" ;;
esac

# ── 定位 Android SDK ────────────────────────────────────────────────────────
SDK=""
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
                 "${LOCALAPPDATA:-}/Android/Sdk" "$HOME/Android/Sdk" \
                 "/c/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [ -n "$candidate" ] && [ -d "$candidate/platforms" ]; then
        SDK="$candidate"
        break
    fi
done
[ -n "$SDK" ] || die "找不到 Android SDK。请设置 ANDROID_HOME，或把 SDK 放在默认位置。"

BT_DIR="$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n1)"
[ -n "$BT_DIR" ] || die "找不到 build-tools，请在 SDK Manager 中安装。"

PLATFORM_DIR="$(find "$SDK/platforms" -mindepth 1 -maxdepth 1 -type d -name 'android-*' 2>/dev/null | sort -V | tail -n1)"
[ -n "$PLATFORM_DIR" ] || die "找不到 platforms/android-*，请在 SDK Manager 中安装。"

ANDROID_JAR="$PLATFORM_DIR/android.jar"
[ -f "$ANDROID_JAR" ] || die "缺少 $ANDROID_JAR"

AAPT2="$BT_DIR/aapt2$EXE"
ZIPALIGN="$BT_DIR/zipalign$EXE"
APKSIGNER="$BT_DIR/apksigner$EXE"
[ -f "$BT_DIR/apksigner.bat" ] && APKSIGNER="$BT_DIR/apksigner.bat"
D8_JAR="$BT_DIR/lib/d8.jar"

for tool in "$AAPT2" "$ZIPALIGN" "$APKSIGNER" "$D8_JAR"; do
    [ -f "$tool" ] || die "缺少构建工具: $tool"
done

# ── 定位 JDK ────────────────────────────────────────────────────────────────
JH="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java\.home = //p' | head -n1)"
[ -n "$JH" ] || die "找不到 java，请确认 JDK 已安装并在 PATH 中。"
if command -v cygpath >/dev/null 2>&1; then
    JH="$(cygpath -u "$JH" 2>/dev/null || echo "$JH")"
fi
JAVAC="$JH/bin/javac$EXE"
KEYTOOL="$JH/bin/keytool$EXE"
[ -f "$JAVAC" ] || die "找不到 javac（已推导 JAVA_HOME=$JH）"

cyan "SDK        : $SDK"
cyan "build-tools: $(basename "$BT_DIR")"
cyan "platform   : $(basename "$PLATFORM_DIR")"
cyan "JDK        : $JH"

# ── 0. 布局静态检查 ─────────────────────────────────────────────────────────
# 视图缺少 layout_width / layout_height 时，Activity 会在 inflate 阶段直接抛异常
# 崩溃，而 aapt2 链接阶段完全不报错 —— 必须在打包前单独拦一次。
PY=""
for c in python3 python py; do
    # 本机的 python3 可能是 Microsoft Store 的占位程序：能 command -v 找到，
    # 但一执行就退出且没有任何输出。所以必须真的试跑一次再决定用哪个。
    if command -v "$c" >/dev/null 2>&1 &&
        "$c" -c 'import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)' >/dev/null 2>&1; then
        PY="$c"
        break
    fi
done
if [ -n "$PY" ]; then
    cyan "[0/7] 布局静态检查…"
    "$PY" tools/lint_layout.py || die "布局检查未通过，已中止构建"
else
    printf '\033[33m跳过布局检查（未找到 python）\033[0m\n'
fi

# ── 准备目录 ────────────────────────────────────────────────────────────────
rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex" "$OUT/gen"

# ── 1. 编译资源 ─────────────────────────────────────────────────────────────
cyan "[1/7] 编译资源…"
"$AAPT2" compile --dir res -o "$OUT/compiled/res.zip"

# ── 2. 链接资源，生成 R.java ────────────────────────────────────────────────
cyan "[2/7] 链接资源…"
"$AAPT2" link \
    -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    "$OUT/compiled/res.zip"

# ── 3. 编译 Java ────────────────────────────────────────────────────────────
cyan "[3/7] 编译 Java 源码…"
find src "$OUT/gen" -name '*.java' | while IFS= read -r f; do winpath "$f"; done > "$OUT/sources.txt"
# 用 --release 而不是 -source/-target：既锁定语言级别，也避免误用到 JDK 新增 API。
# android.jar 放在 classpath 上提供 android.* / org.json 的平台签名。
"$JAVAC" -nowarn --release 17 \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    @"$(winpath "$OUT/sources.txt")"

# ── 4. 转 dex ───────────────────────────────────────────────────────────────
cyan "[4/7] 转换 DEX…"
find "$OUT/classes" -name '*.class' | while IFS= read -r f; do winpath "$f"; done > "$OUT/classes.txt"
# shellcheck disable=SC2046
java -cp "$D8_JAR" com.android.tools.r8.D8 \
    --release \
    --min-api "$MIN_SDK" \
    --lib "$ANDROID_JAR" \
    --output "$OUT/dex" \
    $(cat "$OUT/classes.txt")

[ -f "$OUT/dex/classes.dex" ] || die "D8 未产出 classes.dex"

# ── 5. 把 dex 并入 APK ──────────────────────────────────────────────────────
cyan "[5/7] 打包 APK…"
java "$HERE/tools/ZipAdd.java" "$OUT/base.apk" "$OUT/unsigned.apk" "$OUT/dex/classes.dex"

# ── 6. 对齐 ─────────────────────────────────────────────────────────────────
cyan "[6/7] 4 字节对齐…"
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# ── 7. 签名 ─────────────────────────────────────────────────────────────────
cyan "[7/7] 签名…"
KS_DIR="$HERE/keystore"
KS="$KS_DIR/debug.keystore"
if [ ! -f "$KS" ]; then
    mkdir -p "$KS_DIR"
    "$KEYTOOL" -genkeypair -v \
        -keystore "$KS" \
        -alias luminprotile \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass android -keypass android \
        -dname "CN=LuminPro Tile, OU=Dev, O=LuminPro, C=CN" >/dev/null 2>&1
    green "  已生成调试签名 $KS"
    printf '  \033[33m注意: 这是自签名调试密钥，仅供自用安装；发布请替换为自己的密钥。\033[0m\n'
fi

JAVA_HOME="$JH" "$APKSIGNER" sign \
    --ks "$KS" \
    --ks-key-alias luminprotile \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$OUT/$APP_NAME.apk" \
    "$OUT/aligned.apk"

JAVA_HOME="$JH" "$APKSIGNER" verify --print-certs "$OUT/$APP_NAME.apk" | head -n 3

echo
green "构建完成"
echo "  包名    : $PKG"
echo "  产物    : $OUT/$APP_NAME.apk"
echo
echo "安装:  adb install -r \"$OUT/$APP_NAME.apk\""
