package android.os;

/** 测试替身，见 {@code android/util/Log.java} 的说明。 */
public final class Looper {

    public static Looper getMainLooper() {
        return new Looper();
    }

    private Looper() {
    }
}
