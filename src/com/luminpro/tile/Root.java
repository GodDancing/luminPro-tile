package com.luminpro.tile;

/**
 * 进程内共享的 root shell 单例。
 *
 * <p>磁贴、界面、锁定保持服务都跑在同一进程，共用一条 root shell 可以避免
 * 三方各自申请 su 造成重复授权与进程堆积。任何一次调用发现 shell 失效都会
 * 丢弃缓存，下次调用重新申请。
 */
final class Root {

    /** su 授权弹窗是异步的，探测超时要给足用户操作时间。 */
    private static final long PROBE_TIMEOUT_MS = 30000L;

    private static RootShell shell;
    private static boolean denied;

    private Root() {
    }

    /** 取当前可用的 root shell；没有则尝试申请。 */
    static synchronized RootShell get() {
        if (shell != null && shell.isAlive()) {
            return shell;
        }
        if (denied) {
            return null;
        }
        RootShell s = RootShell.open(PROBE_TIMEOUT_MS);
        if (s == null) {
            denied = true;
            return null;
        }
        shell = s;
        return shell;
    }

    /** 在给定 shell 上执行命令，失败（shell 已失效）时自动重连一次。 */
    static RootShell.Result run(String command, long timeoutMs) {
        RootShell s = get();
        if (s == null) {
            return RootShell.Result.dead();
        }
        RootShell.Result r = s.exec(command, timeoutMs);
        if (r.dead) {
            // shell 可能在 su 超时或系统回收后失效，丢弃并重连一次
            synchronized (Root.class) {
                if (shell == s) {
                    s.destroy();
                    shell = null;
                }
            }
            RootShell s2 = get();
            if (s2 != null) {
                return s2.exec(command, timeoutMs);
            }
        }
        return r;
    }

    /** 要求重新申请（用户可能在设置里补授了权限）。 */
    static synchronized void reset() {
        if (shell != null) {
            shell.destroy();
            shell = null;
        }
        denied = false;
    }

    static synchronized boolean ready() {
        return shell != null && shell.isAlive();
    }

    static synchronized boolean denied() {
        return denied;
    }
}
