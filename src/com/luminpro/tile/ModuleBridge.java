package com.luminpro.tile;

import org.json.JSONObject;

/**
 * 与 LuminPro 模块（V2.5.0 正式版）通信。
 *
 * <p>模块内 {@code bin/luminpro} 提供 JSON 子命令，本类只用其中四个：
 * <ul>
 *   <li>{@code status --no-display} —— 一次调用取回模块版本、亮度节点、当前/最大亮度</li>
 *   <li>{@code config read} —— 读取模块配置（含 max_bri / ui_max_bri）</li>
 *   <li>{@code config patch <json>} —— 浅合并写入配置</li>
 *   <li>{@code brightness set <值>} —— 由模块校验范围并持操作锁写亮度</li>
 * </ul>
 *
 * <p>所有输出都是单行 JSON，用 {@link org.json} 解析（Android 平台自带，无需第三方库）。
 */
final class ModuleBridge {

    /** 模块根目录，与 module.prop 的 id 对应。 */
    static final String MODDIR = "/data/adb/modules/LuminPro";
    static final String BIN = MODDIR + "/bin/luminpro";

    private static final String NO_MODULE = "LUMINPRO_NO_MODULE";
    private static final String DISABLED = "LUMINPRO_DISABLED";

    /** 一次探测的结果。 */
    static final class Info {
        boolean present;
        boolean disabled;
        String version = "";
        int versionCode;
        String channel = "";

        /** 当前亮度节点（模块配置里的 now_bri_file）。 */
        String node = "";
        /** 最大亮度节点（max_bri_file）。 */
        String maxNode = "";
        /** 节点当前值。 */
        int current = -1;
        /** 节点最大可写值（读自 maxNode）。 */
        int hwMax = -1;
        /** 模块配置的峰值亮度 max_bri。 */
        int maxBri = -1;
        /** 模块配置的前台触发阈值 ui_max_bri。 */
        int uiMaxBri = -1;

        boolean daemonRunning;
        String error = "";

        boolean usable() {
            return present && !disabled && !node.isEmpty();
        }
    }

    private ModuleBridge() {
    }

    /** 探测模块是否可用，并取回节点与亮度信息。 */
    static Info probe(RootShell sh) {
        Info info = new Info();
        if (sh == null) {
            return info;
        }

        String script = "if [ -d " + quote(MODDIR) + " ]; then "
                + "if [ -f " + quote(MODDIR + "/disable") + " ]; then echo " + DISABLED + "; "
                + "elif [ -x " + quote(BIN) + " ]; then " + quote(BIN) + " status --no-display; "
                + "else echo " + NO_MODULE + "; fi; "
                + "else echo " + NO_MODULE + "; fi";

        RootShell.Result r = sh.exec(script, 12000);
        String out = r.output;

        if (out.contains(DISABLED)) {
            info.present = true;
            info.disabled = true;
            info.error = "模块在管理器中已被停用";
            Logx.w("LuminPro 模块已安装但处于停用状态");
            return info;
        }
        if (out.contains(NO_MODULE)) {
            Logx.i("未检测到 LuminPro 模块，将使用 root 直写模式");
            return info;
        }

        String json = firstJsonLine(out);
        if (json == null) {
            info.error = r.dead ? "root shell 已失效" : "模块未返回可解析的状态";
            Logx.w("模块状态解析失败: " + info.error);
            return info;
        }

        try {
            JSONObject o = new JSONObject(json);
            info.present = true;

            JSONObject mod = o.optJSONObject("module");
            if (mod != null) {
                info.version = mod.optString("version", "");
                info.versionCode = mod.optInt("versionCode", 0);
                info.channel = mod.optString("channel", "");
            }

            JSONObject bri = o.optJSONObject("brightness");
            if (bri != null) {
                info.current = bri.optInt("current", -1);
                info.hwMax = bri.optInt("max", -1);
                info.node = bri.optString("node", "");
                info.maxNode = bri.optString("maxNode", "");
            }

            JSONObject cfg = o.optJSONObject("config");
            if (cfg != null) {
                info.maxBri = cfg.optInt("maxBri", -1);
                info.uiMaxBri = cfg.optInt("uiMaxBri", -1);
            }

            JSONObject d = o.optJSONObject("daemon");
            if (d != null) {
                info.daemonRunning = d.optBoolean("running", false);
            }

            Logx.i("模块就绪: " + (info.version.isEmpty() ? "未知版本" : info.version)
                    + "，节点 " + info.node + "，当前 " + info.current + "/" + info.hwMax);
        } catch (Exception e) {
            info.error = "模块状态 JSON 解析失败: " + e.getMessage();
            Logx.w(info.error);
        }
        return info;
    }

    /**
     * 兜底：模块状态读不出来时，从模块的 config.json 直接取节点路径。
     * 这条路径只在探测失败时使用，正常流程走 {@link #probe}。
     */
    static String[] readNodePaths(RootShell sh) {
        if (sh == null) {
            return null;
        }
        RootShell.Result r = sh.exec(quote(BIN) + " config read", 8000);
        String json = firstJsonLine(r.output);
        if (json == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(json);
            String node = o.optString("now_bri_file", "");
            String maxNode = o.optString("max_bri_file", "");
            if (node.isEmpty()) {
                return null;
            }
            return new String[]{node, maxNode};
        } catch (Exception e) {
            return null;
        }
    }

    /** 由模块校验范围并持操作锁写一次亮度。 */
    static boolean setViaModule(RootShell sh, int value) {
        if (sh == null) {
            return false;
        }
        RootShell.Result r = sh.exec(quote(BIN) + " brightness set " + value, 8000);
        if (r.ok && r.output.contains("\"ok\"")) {
            return true;
        }
        Logx.w("模块 brightness set 失败: "
                + (r.output.isEmpty() ? (r.dead ? "shell 失效" : "无输出") : r.output.trim()));
        return false;
    }

    /**
     * 浅合并写入模块配置。
     *
     * <p>用途单一：把 {@code max_bri} 同步为磁贴亮度。
     * 模块守护进程的提升条件是 {@code now >= ui_max_bri && now < max_bri}，
     * 只要 {@code max_bri <= 磁贴亮度}，它就不会在我们锁定期间二次提升。
     */
    static boolean patchConfig(RootShell sh, String patchJson) {
        if (sh == null) {
            return false;
        }
        RootShell.Result r = sh.exec(quote(BIN) + " config patch " + quote(patchJson), 8000);
        if (r.ok && r.output.contains("\"ok\"")) {
            return true;
        }
        Logx.w("模块 config patch 失败: "
                + (r.output.isEmpty() ? (r.dead ? "shell 失效" : "无输出") : r.output.trim()));
        return false;
    }

    /** 取模块配置里的某个整数字段；读不到返回 -1。 */
    static int readIntConfig(RootShell sh, String key) {
        if (sh == null) {
            return -1;
        }
        RootShell.Result r = sh.exec(quote(BIN) + " config get " + quote(key), 6000);
        try {
            return Integer.parseInt(r.output.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 从可能混有 shell 噪声的输出里取出第一行 JSON 对象。 */
    private static String firstJsonLine(String out) {
        if (out == null) {
            return null;
        }
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("{") && t.endsWith("}")) {
                return t;
            }
        }
        return null;
    }

    private static String quote(String raw) {
        return RootShell.quote(raw);
    }
}
