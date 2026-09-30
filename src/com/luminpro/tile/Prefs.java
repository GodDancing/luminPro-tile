package com.luminpro.tile;

import android.content.Context;
import android.content.SharedPreferences;

/** 应用内的持久化设置。全部为纯本地偏好，不改动模块配置。 */
final class Prefs {

    private static final String FILE = "luminpro_tile";

    // 锁定状态
    static final String ENABLED = "enabled";
    static final String TARGET = "target";
    static final String RESTORE_VALUE = "restore_value";
    static final String HAS_RESTORE = "has_restore";

    // 节点信息（探测结果缓存）
    static final String MODE = "mode";
    static final String NODE = "node";
    static final String MAX_NODE = "max_node";
    static final String NODE_MAX = "node_max";
    static final String MODULE_VERSION = "module_version";

    // 行为开关
    static final String INTERVAL_MS = "interval_ms";
    static final String KEEP_ALIVE = "keep_alive";
    static final String RESTORE_ON_UNLOCK = "restore_on_unlock";
    static final String SYNC_MODULE_MAX = "sync_module_max";
    static final String ORIG_MODULE_MAX = "orig_module_max";
    static final String HAS_ORIG_MODULE_MAX = "has_orig_module_max";
    static final String LAST_ERROR = "last_error";

    static final int DEFAULT_INTERVAL_MS = 1500;
    static final int MIN_INTERVAL_MS = 500;
    static final int MAX_INTERVAL_MS = 8000;

    static final String MODE_MODULE = "module";
    static final String MODE_DIRECT = "direct";
    static final String MODE_NONE = "none";

    private Prefs() {
    }

    static SharedPreferences of(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return of(c).getBoolean(ENABLED, false);
    }

    static void setEnabled(Context c, boolean v) {
        of(c).edit().putBoolean(ENABLED, v).apply();
    }

    static int target(Context c) {
        return of(c).getInt(TARGET, 0);
    }

    static void setTarget(Context c, int v) {
        of(c).edit().putInt(TARGET, v).apply();
    }

    static String node(Context c) {
        return of(c).getString(NODE, "");
    }

    static String maxNode(Context c) {
        return of(c).getString(MAX_NODE, "");
    }

    static int nodeMax(Context c) {
        return of(c).getInt(NODE_MAX, 0);
    }

    static String mode(Context c) {
        return of(c).getString(MODE, MODE_NONE);
    }

    static String moduleVersion(Context c) {
        return of(c).getString(MODULE_VERSION, "");
    }

    static int intervalMs(Context c) {
        int v = of(c).getInt(INTERVAL_MS, DEFAULT_INTERVAL_MS);
        return Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, v));
    }

    static void setIntervalMs(Context c, int v) {
        of(c).edit().putInt(INTERVAL_MS, v).apply();
    }

    /**
     * 是否开启持续保持。默认关闭 —— 与模块的 boost 一致，写一次即可长期有效；
     * 只有开启自动亮度、系统频繁改写亮度节点时才需要常驻守护。
     */
    static boolean keepAlive(Context c) {
        return of(c).getBoolean(KEEP_ALIVE, false);
    }

    static void setKeepAlive(Context c, boolean v) {
        of(c).edit().putBoolean(KEEP_ALIVE, v).apply();
    }

    static boolean restoreOnUnlock(Context c) {
        return of(c).getBoolean(RESTORE_ON_UNLOCK, true);
    }

    static void setRestoreOnUnlock(Context c, boolean v) {
        of(c).edit().putBoolean(RESTORE_ON_UNLOCK, v).apply();
    }

    static boolean syncModuleMax(Context c) {
        return of(c).getBoolean(SYNC_MODULE_MAX, true);
    }

    static void setSyncModuleMax(Context c, boolean v) {
        of(c).edit().putBoolean(SYNC_MODULE_MAX, v).apply();
    }

    static boolean hasOrigModuleMax(Context c) {
        return of(c).getBoolean(HAS_ORIG_MODULE_MAX, false);
    }

    static int origModuleMax(Context c) {
        return of(c).getInt(ORIG_MODULE_MAX, 0);
    }

    static void setOrigModuleMax(Context c, int v) {
        of(c).edit().putInt(ORIG_MODULE_MAX, v).putBoolean(HAS_ORIG_MODULE_MAX, true).apply();
    }

    static void clearOrigModuleMax(Context c) {
        of(c).edit().remove(ORIG_MODULE_MAX).putBoolean(HAS_ORIG_MODULE_MAX, false).apply();
    }

    static void setLastError(Context c, String v) {
        of(c).edit().putString(LAST_ERROR, v == null ? "" : v).apply();
    }

    static String lastError(Context c) {
        return of(c).getString(LAST_ERROR, "");
    }

    /** 记录一次探测结果（模式 / 节点 / 上限）。 */
    static void setNodeInfo(Context c, String mode, String node, String maxNode, int nodeMax, String moduleVersion) {
        of(c).edit()
                .putString(MODE, mode)
                .putString(NODE, node == null ? "" : node)
                .putString(MAX_NODE, maxNode == null ? "" : maxNode)
                .putInt(NODE_MAX, nodeMax)
                .putString(MODULE_VERSION, moduleVersion == null ? "" : moduleVersion)
                .apply();
    }

    /** 保存解锁时要恢复的亮度。 */
    static void setRestoreValue(Context c, int v) {
        of(c).edit().putInt(RESTORE_VALUE, v).putBoolean(HAS_RESTORE, true).apply();
    }

    static boolean hasRestoreValue(Context c) {
        return of(c).getBoolean(HAS_RESTORE, false);
    }

    static int restoreValue(Context c) {
        return of(c).getInt(RESTORE_VALUE, 0);
    }

    static void clearRestoreValue(Context c) {
        of(c).edit().remove(RESTORE_VALUE).putBoolean(HAS_RESTORE, false).apply();
    }
}
