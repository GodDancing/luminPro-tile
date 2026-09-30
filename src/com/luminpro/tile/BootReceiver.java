package com.luminpro.tile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机后清理锁定状态。
 *
 * <p>亮度在重启后会由系统重新初始化，锁定目标已无意义；如果不清，
 * 磁贴会显示「已锁定」但实际没有生效。模块本身的守护进程也是重启后重新开始，
 * 这里保持同样的语义：不跨重启保留。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Context ctx = context.getApplicationContext();
        if (Prefs.enabled(ctx)) {
            Logx.i("检测到重启，已清除上次的锁定状态");
            Prefs.setEnabled(ctx, false);
            Prefs.clearRestoreValue(ctx);
        }
        LockService.stop(ctx);
    }
}
