package com.luminpro.tile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Random;

/**
 * 持久化 root shell：只申请一次 su，之后所有命令复用同一个 shell 进程。
 *
 * <p>反复 {@code Runtime.exec("su -c ...")} 每次都要走一遍 su 授权与进程创建，
 * 在 1.5 秒一次的保持循环里开销不可忽略；同时部分管理器会对高频请求限流。
 *
 * <p>命令结束用随机哨兵行标记，避免与命令自身输出混淆：
 * <pre>
 * ( &lt;cmd&gt; ) 2>&1 ; echo "__LPMARK&lt;随机&gt;__$?"
 * </pre>
 */
final class RootShell {

    /** 一次命令的执行结果。 */
    static final class Result {
        final boolean ok;
        final boolean dead;
        final boolean timeout;
        final int exit;
        final String output;

        Result(boolean ok, boolean dead, boolean timeout, int exit, String output) {
            this.ok = ok;
            this.dead = dead;
            this.timeout = timeout;
            this.exit = exit;
            this.output = output;
        }

        static Result dead() {
            return new Result(false, true, false, -1, "");
        }

        static Result timeout() {
            return new Result(false, false, true, -1, "");
        }
    }

    private static final int START_OK = 0;
    private static final int START_DENIED = 1;
    private static final int START_RETRY = 2;

    /** 各管理器对 su 的长驻 shell 参数不完全一致，逐个尝试。 */
    private static final String[][] SU_CANDIDATES = {
            {"su"},
            {"su", "-c", "sh"},
            {"su", "--shell", "/system/bin/sh"},
    };

    private Process proc;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private volatile boolean alive;
    private final Object lock = new Object();
    private final Random rnd = new Random();

    private RootShell() {
    }

    /**
     * 申请（并常驻）一个 root shell。
     *
     * @return 可用的实例；用户拒绝授权或设备无 root 时返回 null
     */
    static RootShell open(long probeTimeoutMs) {
        for (String[] cmd : SU_CANDIDATES) {
            RootShell s = new RootShell();
            int r = s.tryStart(cmd, probeTimeoutMs);
            if (r == START_OK) {
                Logx.i("root shell 已就绪: " + String.join(" ", cmd));
                return s;
            }
            s.destroy();
            if (r == START_DENIED) {
                // 用户已明确拒绝：换参数只会再弹一次窗，直接放弃
                Logx.w("su 授权被拒绝");
                return null;
            }
        }
        Logx.e("无法获得 root 权限（未安装 su 或全部参数组合失败）");
        return null;
    }

    private int tryStart(String[] cmd, long timeoutMs) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            proc = pb.start();
        } catch (Exception e) {
            return START_RETRY;
        }
        stdin = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
        stdout = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        alive = true;

        Result r = exec("id -u", timeoutMs);
        if (r.dead || r.timeout) {
            return START_DENIED;
        }
        if (r.ok && "0".equals(r.output.trim())) {
            return START_OK;
        }
        return START_RETRY;
    }

    boolean isAlive() {
        if (!alive || proc == null) {
            return false;
        }
        try {
            proc.exitValue();
            alive = false;
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    /**
     * 在当前 root shell 里执行一条命令（可含换行与引号）。
     *
     * @param timeoutMs 超时后强杀 shell —— 说明它已经不可信，下次调用会重新申请
     */
    Result exec(String command, long timeoutMs) {
        synchronized (lock) {
            if (!isAlive()) {
                alive = false;
                return Result.dead();
            }

            final String marker = "__LPMARK" + Long.toHexString(rnd.nextLong()) + "__";
            final boolean[] finished = {false};
            final boolean[] timedOut = {false};

            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(timeoutMs);
                } catch (InterruptedException e) {
                    return;
                }
                if (!finished[0]) {
                    timedOut[0] = true;
                    try {
                        proc.destroyForcibly();
                    } catch (Throwable ignored) {
                        // 进程可能已自行退出
                    }
                }
            });
            watchdog.setDaemon(true);
            watchdog.start();

            StringBuilder out = new StringBuilder();
            try {
                stdin.write("( " + command + " ) 2>&1 ; echo \"" + marker + "$?\"\n");
                stdin.flush();

                String line;
                while ((line = stdout.readLine()) != null) {
                    int idx = line.indexOf(marker);
                    if (idx >= 0) {
                        // 命令输出若不以换行结尾（例如 cat 一个无换行的节点），
                        // 它的最后一段会和哨兵挤在同一行 —— 这段仍然是有效输出。
                        if (idx > 0) {
                            out.append(line, 0, idx);
                        }
                        String tail = line.substring(idx + marker.length()).trim();
                        int exit = -1;
                        try {
                            exit = Integer.parseInt(tail);
                        } catch (NumberFormatException ignored) {
                            // 退出码缺失时按失败处理
                        }
                        return new Result(exit == 0, false, false, exit, out.toString());
                    }
                    out.append(line).append('\n');
                }
                // stdout 关闭 => shell 进程已退出
                alive = false;
                return timedOut[0] ? Result.timeout() : Result.dead();
            } catch (Exception e) {
                alive = false;
                return timedOut[0] ? Result.timeout() : Result.dead();
            } finally {
                finished[0] = true;
                watchdog.interrupt();
            }
        }
    }

    void destroy() {
        alive = false;
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (Exception ignored) {
            // 关闭流失败不影响后续强杀
        }
        try {
            if (proc != null) {
                proc.destroyForcibly();
            }
        } catch (Exception ignored) {
            // 进程可能已退出
        }
        proc = null;
        stdin = null;
        stdout = null;
    }

    /** 对任意字符串做 POSIX 单引号转义，避免把未校验输入拼进命令。 */
    static String quote(String raw) {
        if (raw == null) {
            return "''";
        }
        return "'" + raw.replace("'", "'\\''") + "'";
    }
}
