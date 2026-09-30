package com.luminpro.tile;

import android.content.Context;

import java.util.List;

/**
 * 亮度锁定的状态机：探测 → 写入 → 保持 → 还原。
 *
 * <p><b>写路径优先级</b>
 * <ol>
 *   <li><b>模块模式</b>：LuminPro 模块可用时，节点路径取自模块配置，
 *       写入走模块自带的 {@code luminpro brightness set}（范围校验 + 操作锁 + 日志），
 *       保持循环则直写节点以避免反复创建进程。</li>
 *   <li><b>直写模式</b>：模块缺失或不可用时，自行扫描亮度节点并用 root 直接读写。</li>
 * </ol>
 *
 * <p><b>与模块守护进程共存</b>
 * 模块的提升条件是 {@code now >= ui_max_bri && now < max_bri}（见 policy.ShouldBoost）。
 * 若磁贴目标低于模块的 {@code max_bri}，守护进程会在我们锁定期间把亮度再拉到峰值，
 * 两边互相覆盖。因此模块模式下默认把 {@code max_bri} 同步为磁贴亮度，
 * 使 {@code now < max_bri} 恒不成立，守护进程自然让位；解锁时再还原原值。
 */
final class BrightnessController {

    /** 一次探测得到的设备/模块状态快照。 */
    static final class State {
        String mode = Prefs.MODE_NONE;
        String node = "";
        String maxNode = "";
        int nodeMax = 0;
        int current = -1;
        String moduleVersion = "";
        boolean rootReady;
        boolean rootDenied;
        String message = "";

        boolean usable() {
            return !node.isEmpty() && nodeMax > 0;
        }
    }

    private BrightnessController() {
    }

    // ── 探测 ────────────────────────────────────────────────────────────────

    /** 探测当前可用性并缓存节点信息；失败时只更新状态描述，不清空已有缓存。 */
    static State refresh(Context ctx) {
        State st = new State();

        RootShell sh = Root.get();
        st.rootReady = sh != null;
        st.rootDenied = Root.denied();
        if (sh == null) {
            st.message = Root.denied() ? "root 授权被拒绝" : "未获得 root 权限";
            Logx.w("探测失败: " + st.message);
            return st;
        }

        ModuleBridge.Info info = ModuleBridge.probe(sh);
        if (info.usable()) {
            st.mode = Prefs.MODE_MODULE;
            st.node = info.node;
            st.maxNode = info.maxNode;
            st.nodeMax = info.hwMax > 0 ? info.hwMax : readMaxFrom(sh, info.maxNode);
            st.current = info.current;
            st.moduleVersion = info.version;
            st.message = "模块模式"
                    + (info.version.isEmpty() ? "" : " · " + info.version)
                    + (info.daemonRunning ? " · 守护进程运行中" : " · 守护进程未运行");
        } else {
            List<Nodes.Node> list = discoverWithFallback(sh);
            if (list.isEmpty()) {
                st.mode = Prefs.MODE_NONE;
                st.message = info.present
                        ? ("模块不可用：" + (info.error.isEmpty() ? "二进制无法执行" : info.error))
                        : "未安装 LuminPro 模块，且未找到可用亮度节点";
                Logx.e(st.message);
                Prefs.setNodeInfo(ctx, Prefs.MODE_NONE, "", "", 0, "");
                return st;
            }
            Nodes.Node n = list.get(0);
            st.mode = Prefs.MODE_DIRECT;
            st.node = n.path;
            st.maxNode = n.maxPath;
            st.nodeMax = n.max;
            st.current = n.current;
            st.message = "root 直写模式（模块不可用）";
        }

        Prefs.setNodeInfo(ctx, st.mode, st.node, st.maxNode, st.nodeMax, st.moduleVersion);
        Prefs.setLastError(ctx, "");
        return st;
    }

    /** 模块缺失时先看模块配置里的节点路径，再退回自行扫描。 */
    private static List<Nodes.Node> discoverWithFallback(RootShell sh) {
        List<Nodes.Node> list = Nodes.discover(sh);
        if (!list.isEmpty()) {
            return list;
        }
        String[] paths = ModuleBridge.readNodePaths(sh);
        if (paths != null && Nodes.exists(sh, paths[0])) {
            int max = readMaxFrom(sh, paths[1]);
            if (max > 0) {
                return java.util.Collections.singletonList(
                        new Nodes.Node(paths[0], paths[1], max, Nodes.read(sh, paths[0])));
            }
        }
        return list;
    }

    private static int readMaxFrom(RootShell sh, String maxNode) {
        if (maxNode == null || maxNode.isEmpty()) {
            return -1;
        }
        RootShell.Result r = sh.exec("cat " + RootShell.quote(maxNode), 6000);
        try {
            return Integer.parseInt(r.output.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    // ── 锁定 / 解锁 ─────────────────────────────────────────────────────────

    /**
     * 锁定到指定亮度。已锁定时相当于修改目标值（不会覆盖解锁时要恢复的亮度）。
     *
     * @param target 目标亮度；<=0 表示取节点量程上限
     */
    static boolean lock(Context ctx, int target) {
        RootShell sh = Root.get();
        if (sh == null) {
            return fail(ctx, "无法获取 root 权限，请在应用内重新申请");
        }

        State st = resolve(ctx, sh);
        if (st == null || !st.usable()) {
            return fail(ctx, "未找到可用的亮度节点");
        }

        int value = target <= 0 ? st.nodeMax : Math.min(target, st.nodeMax);
        boolean alreadyLocked = Prefs.enabled(ctx) && !Prefs.node(ctx).isEmpty();

        // 解锁时要恢复的亮度只在首次锁定时记录，改目标值不应覆盖它
        if (!alreadyLocked) {
            int cur = Nodes.read(sh, st.node);
            if (cur >= 0) {
                Prefs.setRestoreValue(ctx, cur);
                Logx.i("已记录原亮度 " + cur + "，解锁时恢复");
            }
        }

        syncModuleMax(ctx, sh, st, value);

        boolean written = writeBrightness(sh, st, value);
        if (!written) {
            return fail(ctx, "写入亮度失败，请检查亮度节点是否可写");
        }

        Prefs.setTarget(ctx, value);
        Prefs.setEnabled(ctx, true);
        Prefs.setLastError(ctx, "");
        Logx.i("已锁定亮度 " + value + "（" + st.mode + "）");
        if (Prefs.keepAlive(ctx)) {
            LockService.start(ctx);
        }
        TileState.publish(ctx);
        return true;
    }

    /** 解除锁定：停止保持循环，恢复原亮度并还原模块峰值。 */
    static boolean unlock(Context ctx) {
        LockService.stop(ctx);
        boolean wasEnabled = Prefs.enabled(ctx);
        Prefs.setEnabled(ctx, false);
        TileState.publish(ctx);
        if (!wasEnabled) {
            return true;
        }

        RootShell sh = Root.get();
        if (sh == null) {
            Logx.w("解除锁定：无 root，亮度保持当前值");
            return true;
        }

        // 顺序关键：先降回原亮度再还原 max_bri。
        // 反过来的话亮度此刻仍在峰值，还原 max_bri 会立刻触发模块的自动提升。
        String node = Prefs.node(ctx);
        if (Prefs.restoreOnUnlock(ctx) && Prefs.hasRestoreValue(ctx) && !node.isEmpty()) {
            int v = Prefs.restoreValue(ctx);
            if (Nodes.write(sh, node, v)) {
                Logx.i("已恢复原亮度 " + v);
            }
        }
        Prefs.clearRestoreValue(ctx);

        if (Prefs.MODE_MODULE.equals(Prefs.mode(ctx)) && Prefs.hasOrigModuleMax(ctx)) {
            int orig = Prefs.origModuleMax(ctx);
            if (ModuleBridge.patchConfig(sh, "{\"max_bri\":" + orig + "}")) {
                Logx.i("已还原模块峰值亮度 " + orig);
            }
            Prefs.clearOrigModuleMax(ctx);
        }
        return true;
    }

    /** 磁贴 / 按钮的统一入口：已锁定则解锁，否则锁定。 */
    static boolean toggle(Context ctx, int target) {
        if (Prefs.enabled(ctx)) {
            unlock(ctx);
            return false;
        }
        return lock(ctx, target);
    }

    /** 一次性写入（不改变锁定状态），用于界面上的「立即应用」。 */
    static boolean applyOnce(Context ctx, int value) {
        RootShell sh = Root.get();
        if (sh == null) {
            return fail(ctx, "无法获取 root 权限");
        }
        State st = resolve(ctx, sh);
        if (st == null || !st.usable()) {
            return fail(ctx, "未找到可用的亮度节点");
        }
        int v = value <= 0 ? st.nodeMax : Math.min(value, st.nodeMax);
        boolean ok = writeBrightness(sh, st, v);
        if (ok) {
            Logx.i("已写入亮度 " + v);
            Prefs.setTarget(ctx, v);
        }
        return ok;
    }

    // ── 保持循环 ────────────────────────────────────────────────────────────

    /**
     * 保持循环的一次 tick：节点值被系统或其他程序改动时拉回目标值。
     *
     * <p>只读不写的时候开销极小（一次 cat），因此可以高频调用。
     */
    static void holdTick(Context ctx) {
        if (!Prefs.enabled(ctx)) {
            return;
        }
        RootShell sh = Root.get();
        if (sh == null) {
            Logx.w("保持循环：root shell 不可用");
            return;
        }

        String node = Prefs.node(ctx);
        if (node.isEmpty()) {
            Logx.w("保持循环：节点路径丢失，重新探测");
            refresh(ctx);
            return;
        }

        int target = Prefs.target(ctx);
        int cur = Nodes.read(sh, node);
        if (cur < 0) {
            Logx.w("保持循环：读取节点失败，重新探测");
            refresh(ctx);
            return;
        }
        if (cur != target && Nodes.write(sh, node, target)) {
            Logx.i("保持亮度 " + cur + " → " + target);
        }
    }

    // ── 内部工具 ────────────────────────────────────────────────────────────

    /** 取已缓存的节点信息，缓存不可用时重新探测。 */
    private static State resolve(Context ctx, RootShell sh) {
        State st = new State();
        String node = Prefs.node(ctx);
        int nodeMax = Prefs.nodeMax(ctx);
        if (!node.isEmpty() && nodeMax > 0 && Nodes.exists(sh, node)) {
            st.mode = Prefs.mode(ctx);
            st.node = node;
            st.maxNode = Prefs.maxNode(ctx);
            st.nodeMax = nodeMax;
            st.moduleVersion = Prefs.moduleVersion(ctx);
            return st;
        }
        st = refresh(ctx);
        return st.usable() ? st : null;
    }

    /**
     * 模块模式下把 max_bri 拉低到磁贴亮度。
     *
     * <p>只在模块当前峰值高于目标时改动，并记住原值供解锁还原；
     * 未校准（max_bri 为 0）的模块保持原样 —— 它的守护进程本就不会动作。
     */
    private static void syncModuleMax(Context ctx, RootShell sh, State st, int value) {
        if (!Prefs.MODE_MODULE.equals(st.mode)) {
            return;
        }
        if (!Prefs.syncModuleMax(ctx)) {
            Logx.w("已关闭「同步模块峰值」：若模块守护进程正在工作，可能与锁定目标互相覆盖");
            return;
        }
        int moduleMax = ModuleBridge.readIntConfig(sh, "max_bri");
        if (moduleMax <= value) {
            return;
        }
        if (!Prefs.hasOrigModuleMax(ctx)) {
            Prefs.setOrigModuleMax(ctx, moduleMax);
        }
        if (ModuleBridge.patchConfig(sh, "{\"max_bri\":" + value + "}")) {
            Logx.i("已把模块峰值 " + moduleMax + " 同步为磁贴亮度 " + value);
        }
    }

    /** 优先走模块（带范围校验与操作锁），失败时退回直接写节点。 */
    private static boolean writeBrightness(RootShell sh, State st, int value) {
        if (Prefs.MODE_MODULE.equals(st.mode) && ModuleBridge.setViaModule(sh, value)) {
            return true;
        }
        return Nodes.write(sh, st.node, value);
    }

    private static boolean fail(Context ctx, String msg) {
        Logx.e(msg);
        Prefs.setLastError(ctx, msg);
        return false;
    }
}
