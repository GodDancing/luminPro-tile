#!/usr/bin/env bash
#
# RootShell 的 JVM 单元测试。
#
# RootShell 依赖 Logx，而 Logx 依赖 android.util.Log 与 android.os.Handler。
# 这里用 test/stubs 下的同名类在运行时顶替 Android 平台类：编译期用 android.jar
# 校验签名，运行期把 stub 放在 classpath 最前面。
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

EXE=""
SEP=":"
case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*)
        EXE=".exe"
        # Windows 的 java 用分号分隔 classpath；用冒号会被盘符里的 "C:" 带偏
        SEP=";"
        ;;
esac

winpath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi
}

SDK=""
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
                 "${LOCALAPPDATA:-}/Android/Sdk" "$HOME/Android/Sdk" "/c/Android/Sdk"; do
    if [ -n "$candidate" ] && [ -d "$candidate/platforms" ]; then
        SDK="$candidate"
        break
    fi
done
[ -n "$SDK" ] || { echo "错误: 找不到 Android SDK" >&2; exit 1; }

PLATFORM_DIR="$(find "$SDK/platforms" -mindepth 1 -maxdepth 1 -type d -name 'android-*' | sort -V | tail -n1)"
ANDROID_JAR="$PLATFORM_DIR/android.jar"

JH="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java\.home = //p' | head -n1)"
command -v cygpath >/dev/null 2>&1 && JH="$(cygpath -u "$JH" 2>/dev/null || echo "$JH")"
JAVAC="$JH/bin/javac$EXE"

OUT="$HERE/build-test"
rm -rf "$OUT"
mkdir -p "$OUT/product" "$OUT/stubs"

echo "[1/3] 编译被测代码（RootShell + Logx）…"
"$JAVAC" -nowarn --release 17 -classpath "$ANDROID_JAR" -d "$OUT/product" \
    src/com/luminpro/tile/RootShell.java \
    src/com/luminpro/tile/Logx.java

echo "[2/3] 编译测试替身与测试用例…"
find test/stubs -name '*.java' | while IFS= read -r f; do winpath "$f"; done > "$OUT/stubs.txt"
"$JAVAC" -nowarn --release 17 -d "$OUT/stubs" @"$(winpath "$OUT/stubs.txt")"

# 测试用例本身要能看见包内成员，因此和被测代码同包编译
"$JAVAC" -nowarn --release 17 -classpath "$(winpath "$OUT/product")" -d "$OUT/product" \
    test/com/luminpro/tile/RootShellTest.java

echo "[3/3] 运行…"
echo
# stub 必须排在 product 之前，才能顶替 android.jar 的平台实现
java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
    -cp "$(winpath "$OUT/stubs")${SEP}$(winpath "$OUT/product")" \
    com.luminpro.tile.RootShellTest
