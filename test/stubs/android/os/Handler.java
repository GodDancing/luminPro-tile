package android.os;

/**
 * 测试替身：把 post 直接在当前线程执行，让日志断言是同步的。
 * 见 {@code android/util/Log.java} 的说明。
 */
public final class Handler {

    public Handler(Looper looper) {
    }

    public boolean post(Runnable r) {
        r.run();
        return true;
    }

    public boolean postDelayed(Runnable r, long delayMillis) {
        r.run();
        return true;
    }

    public void removeCallbacksAndMessages(Object token) {
    }
}
