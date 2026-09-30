package com.luminpro.tile;

import java.util.ArrayList;
import java.util.List;

/**
 * 亮度节点读写与自动发现。
 *
 * <p>模块可用时节点路径一律取自模块配置，保证两边操作同一个文件；
 * 模块不可用（未安装 / 被停用 / 二进制不可用）时才走这里的发现逻辑。
 */
final class Nodes {

    /** 一个候选亮度节点。 */
    static final class Node {
        final String path;
        final String maxPath;
        final int max;
        final int current;

        Node(String path, String maxPath, int max, int current) {
            this.path = path;
            this.maxPath = maxPath;
            this.max = max;
            this.current = current;
        }
    }

    /**
     * 扫描常见亮度节点，按「优先 panel0、其次最大量程」排序返回。
     *
     * <p>输出格式：{@code LPNODE|路径|最大路径|最大值|当前值}
     */
    private static final String SCAN =
            "for d in /sys/class/backlight/*; do "
                    + "b=\"$d/brightness\"; m=\"$d/max_brightness\"; "
                    + "[ -f \"$b\" ] || continue; "
                    + "mx=\"\"; [ -f \"$m\" ] && mx=$(cat \"$m\" 2>/dev/null); "
                    + "cur=$(cat \"$b\" 2>/dev/null); "
                    + "echo \"LPNODE|$b|$m|$mx|$cur\"; "
                    + "done; "
                    + "b=/sys/class/leds/lcd-backlight/brightness; "
                    + "m=/sys/class/leds/lcd-backlight/max_brightness; "
                    + "if [ -f \"$b\" ]; then mx=\"\"; [ -f \"$m\" ] && mx=$(cat \"$m\" 2>/dev/null); "
                    + "cur=$(cat \"$b\" 2>/dev/null); echo \"LPNODE|$b|$m|$mx|$cur\"; fi";

    private Nodes() {
    }

    /** 通过 root shell 扫描设备上的亮度节点。 */
    static List<Node> discover(RootShell sh) {
        List<Node> found = new ArrayList<>();
        if (sh == null || !sh.isAlive()) {
            return found;
        }
        RootShell.Result r = sh.exec(SCAN, 12000);
        for (String line : r.output.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("LPNODE|")) {
                continue;
            }
            String[] parts = t.split("\\|", -1);
            if (parts.length < 5) {
                continue;
            }
            String path = parts[1];
            String maxPath = parts[2];
            int max = parseInt(parts[3], -1);
            int current = parseInt(parts[4], -1);
            if (path.isEmpty() || max <= 0) {
                continue;
            }
            found.add(new Node(path, maxPath, max, current));
        }
        if (!found.isEmpty()) {
            // panel0 通常是主屏面板；其余按量程从大到小
            found.sort((a, b) -> {
                boolean pa = a.path.contains("panel0");
                boolean pb = b.path.contains("panel0");
                if (pa != pb) {
                    return pa ? -1 : 1;
                }
                return Integer.compare(b.max, a.max);
            });
        }
        return found;
    }

    /** 读取节点的当前值；失败返回 -1。 */
    static int read(RootShell sh, String node) {
        if (sh == null || node == null || node.isEmpty()) {
            return -1;
        }
        RootShell.Result r = sh.exec("cat " + RootShell.quote(node), 6000);
        return parseInt(r.output.trim(), -1);
    }

    /**
     * 直接写亮度节点。
     *
     * <p>用 {@code echo -n} 而非 {@code echo}：模块侧同样是「不追加换行」写入，
     * 部分内核会把多余的换行当成非法输入。
     */
    static boolean write(RootShell sh, String node, int value) {
        if (sh == null || node == null || node.isEmpty()) {
            return false;
        }
        RootShell.Result r = sh.exec(
                "echo -n " + value + " > " + RootShell.quote(node), 6000);
        if (r.ok) {
            return true;
        }
        Logx.w("写亮度节点失败: " + node
                + (r.output.isEmpty() ? "" : " (" + r.output.trim() + ")"));
        return false;
    }

    /** 判断节点是否可读写。 */
    static boolean exists(RootShell sh, String node) {
        if (sh == null || node == null || node.isEmpty()) {
            return false;
        }
        return sh.exec("[ -f " + RootShell.quote(node) + " ]", 6000).ok;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
