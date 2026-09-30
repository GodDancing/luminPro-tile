package com.luminpro.tile;

import android.content.ComponentName;
import android.content.Context;
import android.service.quicksettings.TileService;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 磁贴状态广播。
 *
 * <p>磁贴可能同时存在多份实例（不同 SystemUI 进程、磁贴编辑页），
 * 这里维护活动实例列表做即时刷新，同时用 {@code requestListeningState}
 * 兜底唤醒没有实例在跑的磁贴。
 */
final class TileState {

    private static final CopyOnWriteArrayList<LuminProTileService> LIVE =
            new CopyOnWriteArrayList<>();

    private TileState() {
    }

    static void add(LuminProTileService service) {
        if (!LIVE.contains(service)) {
            LIVE.add(service);
        }
    }

    static void remove(LuminProTileService service) {
        LIVE.remove(service);
    }

    /** 把当前锁定状态推给所有磁贴实例。 */
    static void publish(Context ctx) {
        for (LuminProTileService s : LIVE) {
            s.syncTile();
        }
        try {
            TileService.requestListeningState(
                    ctx, new ComponentName(ctx, LuminProTileService.class));
        } catch (Throwable ignored) {
            // 磁贴未添加或系统不支持时忽略
        }
    }
}
