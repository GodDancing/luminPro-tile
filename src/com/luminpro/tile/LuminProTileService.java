package com.luminpro.tile;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 快捷设置磁贴：点击锁定 / 解锁亮度。
 *
 * <p>点击不打开界面，直接切换；只有在拿不到 root 时才把用户引到设置页。
 * 所有涉及 root 的操作都丢到后台线程 —— 磁贴回调跑在主线程，
 * 首次 {@code su} 授权会弹窗等待，放在主线程必然 ANR。
 */
public class LuminProTileService extends TileService {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        TileState.add(this);
        syncTile();
    }

    @Override
    public void onTileRemoved() {
        TileState.remove(this);
        super.onTileRemoved();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        TileState.add(this);
        syncTile();

        // 轻量自愈：用户查看磁贴时，若亮度已被系统改写就拉回目标值。
        // 这让「写一次」的模式在不常驻服务的前提下也有自我修复能力。
        //
        // 只在 root shell 已经存在时动手：onStartListening 会在每次拉开快捷设置
        // 面板时触发，若在这里申请 su，用户一拉面板就会看到授权弹窗。
        if (Prefs.enabled(this) && Root.ready()) {
            Bg.run(() -> {
                if (Prefs.enabled(LuminProTileService.this)) {
                    BrightnessController.holdTick(LuminProTileService.this);
                    syncTile();
                }
            });
        }
    }

    @Override
    public void onStopListening() {
        TileState.remove(this);
        super.onStopListening();
    }

    @Override
    public void onClick() {
        final boolean wasLocked = Prefs.enabled(this);

        // 首次使用且明确被拒时，不要再弹一次 su，直接把用户引到应用里
        if (!wasLocked && Root.denied()) {
            openApp();
            return;
        }

        setBusyTile(wasLocked);
        Bg.run(() -> {
            final android.content.Context ctx = getApplicationContext();
            if (wasLocked) {
                BrightnessController.unlock(ctx);
            } else if (!BrightnessController.lock(ctx, Prefs.target(ctx))) {
                if (Root.denied() || !Root.ready()) {
                    openApp();
                }
            }
            syncTile();
        });
    }

    /** 把锁定状态同步到磁贴显示（可从任意线程调用）。 */
    void syncTile() {
        MAIN.post(this::syncTileOnMain);
    }

    private void syncTileOnMain() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean locked = Prefs.enabled(this);
        tile.setState(locked ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_label));
        // setSubtitle 是 API 29 才有的，低版本调用会 NoSuchMethodError
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(locked ? Prefs.target(this) + " / " + Prefs.nodeMax(this) : null);
        }
        tile.updateTile();
    }

    /** 操作进行中先给出「已激活」的即时反馈，避免点了没反应。 */
    private void setBusyTile(final boolean willUnlock) {
        MAIN.post(() -> {
            Tile tile = getQsTile();
            if (tile == null) {
                return;
            }
            tile.setState(willUnlock ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle(willUnlock ? "解除中…" : "锁定中…");
            }
            tile.updateTile();
        });
    }

    private void openApp() {
        MAIN.post(() -> {
            Intent intent = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    startActivityAndCollapse(pi);
                } else {
                    startActivityAndCollapse(intent);
                }
            } catch (Throwable t) {
                Logx.w("无法从磁贴打开界面: " + t.getMessage());
            }
        });
    }
}
