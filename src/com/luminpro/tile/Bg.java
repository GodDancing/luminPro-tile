package com.luminpro.tile;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台任务池。
 *
 * <p>单线程是有意为之：root shell 的命令必须串行执行，
 * 且锁定/解锁/保持三处逻辑操作同一份状态，串行可以省掉大量同步。
 */
final class Bg {

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "luminpro-worker");
        t.setDaemon(true);
        return t;
    });

    private Bg() {
    }

    static void run(Runnable task) {
        POOL.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                Logx.e("后台任务异常: " + t);
            }
        });
    }
}
