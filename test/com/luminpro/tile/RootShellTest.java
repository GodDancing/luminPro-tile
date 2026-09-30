package com.luminpro.tile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * RootShell 的 JVM 单元测试。
 *
 * <p>本机没有 root，也没法在 Android 之外跑 TileService，但 {@code RootShell}
 * 的核心是「往一条常驻 shell 写命令、按随机哨兵读回结果」这段纯 IO 逻辑，
 * 完全可以用本机的 {@code sh} 冒充 root shell 来验证 —— 这正是最容易出错、
 * 也最值得测的一段（例如命令输出不以换行结尾时，末段会和哨兵挤在同一行）。
 *
 * <p>运行: {@code bash test.sh}
 */
public final class RootShellTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"), "luminpro-testshell");
        tmp.mkdirs();

        RootShell sh = connect();
        System.out.println("已连接到冒充 root shell 的本机 sh\n");

        // 1) 普通输出
        RootShell.Result r = sh.exec("echo hello; echo world", 5000);
        check("普通输出", r.ok && r.output.trim().equals("hello\nworld".trim()),
                "ok=" + r.ok + " out=[" + r.output.trim() + "]");

        // 2) 退出码传播
        r = sh.exec("exit 3", 5000);
        check("退出码传播", !r.ok && r.exit == 3, "exit=" + r.exit);

        // 3) 关键回归：输出不以换行结尾时，末段必须和哨兵正确分离。
        //    sysfs 亮度节点被 echo -n 写入后就是这种形态。
        File noNewline = new File(tmp, "node-nonl");
        try (FileWriter w = new FileWriter(noNewline)) {
            w.write("3000");
        }
        r = sh.exec("cat '" + shPath(noNewline) + "'", 5000);
        check("无换行输出的读取", r.ok && r.output.trim().equals("3000"),
                "out=[" + r.output + "]");

        // 4) stderr 合并 + 命令不存在的退出码
        r = sh.exec("/definitely/not/a/real/binary", 5000);
        check("stderr 合并与 127", !r.ok && r.exit == 127 && !r.output.isEmpty(),
                "exit=" + r.exit + " out=[" + r.output.trim() + "]");

        // 5) 写入不带换行（模拟写亮度节点）
        File target = new File(tmp, "node-write");
        r = sh.exec("echo -n 4095 > '" + shPath(target) + "'", 5000);
        check("echo -n 写入", r.ok && target.length() == 4, "len=" + target.length());

        // 6) 含单引号与 JSON 的命令（config patch 的实际形态）
        r = sh.exec("echo '{\"max_bri\":3000}'", 5000);
        check("单引号 JSON 传参", r.ok && r.output.trim().equals("{\"max_bri\":3000}"),
                "out=[" + r.output.trim() + "]");

        // 7) 多行命令（节点发现脚本的实际形态）
        r = sh.exec("for i in 1 2 3; do echo \"LPNODE|$i\"; done", 5000);
        check("多行/循环命令", r.ok && r.output.split("\n").length == 3,
                "out=[" + r.output.trim().replace("\n", ",") + "]");

        // 8) 超时必须杀掉 shell，且后续调用报 dead
        RootShell hung = connect();
        r = hung.exec("sleep 30", 1500);
        check("超时被中断", r.timeout || r.dead, "timeout=" + r.timeout + " dead=" + r.dead);
        check("超时后 shell 判死", !hung.isAlive(), "alive=" + hung.isAlive());

        // 9) 路径转义
        check("quote 转义", "'a'\\''b'".equals(RootShell.quote("a'b")),
                RootShell.quote("a'b"));

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** 用本机 sh 冒充 root shell：绕过 RootShell.open 的 su 探测，直接注入进程与流。 */
    private static RootShell connect() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh");
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        Constructor<RootShell> ctor = RootShell.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        RootShell shell = ctor.newInstance();

        set(shell, "proc", proc);
        set(shell, "stdin", new BufferedWriter(new OutputStreamWriter(proc.getOutputStream())));
        set(shell, "stdout", new BufferedReader(new InputStreamReader(proc.getInputStream())));
        set(shell, "alive", true);
        return shell;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = RootShell.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * 把 Java 的 Windows 路径转成 MSYS sh 能读懂的 POSIX 路径。
     * 在 Linux / macOS 上原样返回。
     */
    private static String shPath(File f) {
        String p = f.getAbsolutePath().replace('\\', '/');
        if (p.length() > 2 && p.charAt(1) == ':') {
            p = "/" + Character.toLowerCase(p.charAt(0)) + p.substring(2);
        }
        return p;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name + "  —— " + detail);
        }
    }

    private RootShellTest() {
    }
}
