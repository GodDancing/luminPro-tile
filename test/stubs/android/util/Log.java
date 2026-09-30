package android.util;

/**
 * 测试替身：仅用于在电脑上跑 {@code RootShell} / {@code Logx} 的单元测试。
 * 构建 APK 时不会被打包 —— build.sh 只编译 src/ 与生成的 R.java。
 */
public final class Log {

    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;

    public static int println(int priority, String tag, String msg) {
        System.out.println("    [" + tag + "] " + msg);
        return 0;
    }

    private Log() {
    }
}
