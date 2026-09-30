package com.luminpro.tile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 主界面：状态展示 + 亮度滑块 + 锁定开关 + 行为设置。
 *
 * <p>所有 root 操作都在后台线程执行，界面只负责渲染与转发意图。
 */
public class MainActivity extends Activity {

    private TextView tvBadge;
    private TextView tvCurrent;
    private TextView tvCurrentMax;
    private TextView tvMode;
    private TextView tvNode;
    private TextView tvModule;
    private TextView tvError;

    private TextView tvTarget;
    private TextView tvTargetPct;
    private TextView tvTargetRange;
    private SeekBar seekTarget;

    private TextView btnToggle;
    private TextView tvLockHint;
    private TextView btnApply;
    private TextView btnMax;

    private Switch swRestore;
    private Switch swSync;
    private Switch swKeepAlive;
    private SeekBar seekInterval;
    private TextView tvInterval;

    private TextView btnRoot;
    private TextView btnProbe;
    private TextView tvLog;
    private TextView btnCopyLog;
    private TextView btnClearLog;
    private ScrollView logScroll;

    /** 程序性修改滑块时抑制回调，避免回写覆盖用户输入。 */
    private boolean binding;
    /** 当前亮度节点量程；0 表示尚未探测到。 */
    private int nodeMax;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        nodeMax = Prefs.nodeMax(this);
        loadPrefs();
        wireListeners();
        requestNotificationPermission();

        Logx.setListener(this::appendLog);
        renderLog();

        renderLockState();
        refresh();
    }

    private void bindViews() {
        tvBadge = findViewById(R.id.tvBadge);
        tvCurrent = findViewById(R.id.tvCurrent);
        tvCurrentMax = findViewById(R.id.tvCurrentMax);
        tvMode = findViewById(R.id.tvMode);
        tvNode = findViewById(R.id.tvNode);
        tvModule = findViewById(R.id.tvModule);
        tvError = findViewById(R.id.tvError);

        tvTarget = findViewById(R.id.tvTarget);
        tvTargetPct = findViewById(R.id.tvTargetPct);
        tvTargetRange = findViewById(R.id.tvTargetRange);
        seekTarget = findViewById(R.id.seekTarget);

        btnToggle = findViewById(R.id.btnToggle);
        tvLockHint = findViewById(R.id.tvLockHint);
        btnApply = findViewById(R.id.btnApply);
        btnMax = findViewById(R.id.btnMax);

        swRestore = findViewById(R.id.swRestore);
        swSync = findViewById(R.id.swSync);
        swKeepAlive = findViewById(R.id.swKeepAlive);
        seekInterval = findViewById(R.id.seekInterval);
        tvInterval = findViewById(R.id.tvInterval);

        btnRoot = findViewById(R.id.btnRoot);
        btnProbe = findViewById(R.id.btnProbe);
        tvLog = findViewById(R.id.tvLog);
        btnCopyLog = findViewById(R.id.btnCopyLog);
        btnClearLog = findViewById(R.id.btnClearLog);
        logScroll = findViewById(R.id.logScroll);
    }

    // ── 初始化 ──────────────────────────────────────────────────────────────

    private void loadPrefs() {
        binding = true;
        swRestore.setChecked(Prefs.restoreOnUnlock(this));
        swSync.setChecked(Prefs.syncModuleMax(this));
        swKeepAlive.setChecked(Prefs.keepAlive(this));
        binding = false;

        applyNodeRange();
        seekInterval.setProgress(intervalToProgress(Prefs.intervalMs(this)));
        renderInterval();
    }

    /** 按当前量程重建滑块的上下限与位置。 */
    private void applyNodeRange() {
        int max = nodeMax > 0 ? nodeMax : 100;
        binding = true;
        seekTarget.setMax(max);
        int target = Prefs.target(this);
        if (target <= 0 || target > max) {
            target = max;
        }
        seekTarget.setProgress(target);
        binding = false;
        renderTarget();
    }

    private void wireListeners() {
        seekTarget.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!binding) {
                    renderTarget();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                // 拖动过程中不写节点，避免持续冲击内核
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                int value = Math.max(1, bar.getProgress());
                Prefs.setTarget(MainActivity.this, value);
                renderTarget();
                if (Prefs.enabled(MainActivity.this)) {
                    applyTarget(value, "已更新锁定亮度");
                }
            }
        });

        seekInterval.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!binding) {
                    renderInterval();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                // 无需处理
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                Prefs.setIntervalMs(MainActivity.this, progressToInterval(bar.getProgress()));
                renderInterval();
                restartKeepAliveIfNeeded();
            }
        });

        btnMax.setOnClickListener(v -> {
            if (nodeMax <= 0) {
                Toast.makeText(this, "尚未探测到亮度节点", Toast.LENGTH_SHORT).show();
                return;
            }
            binding = true;
            seekTarget.setProgress(nodeMax);
            binding = false;
            Prefs.setTarget(this, nodeMax);
            renderTarget();
            if (Prefs.enabled(this)) {
                applyTarget(nodeMax, "已更新锁定亮度");
            }
        });

        btnToggle.setOnClickListener(v -> toggleLock());
        btnApply.setOnClickListener(v -> applyTarget(seekTarget.getProgress(), "已写入亮度"));

        swRestore.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            if (!binding) {
                Prefs.setRestoreOnUnlock(this, on);
            }
        });
        swSync.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            if (!binding) {
                Prefs.setSyncModuleMax(this, on);
            }
        });
        swKeepAlive.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            if (binding) {
                return;
            }
            Prefs.setKeepAlive(this, on);
            if (on && Prefs.enabled(this)) {
                LockService.start(this);
            } else if (!on) {
                LockService.stop(this);
            }
            renderLockState();
        });

        btnRoot.setOnClickListener(v -> {
            Root.reset();
            Logx.i("重新申请 root 权限…");
            Bg.run(() -> {
                Root.get();
                refreshOnUi();
            });
        });

        btnProbe.setOnClickListener(v -> {
            Logx.i("重新探测模块与亮度节点…");
            Bg.run(this::refreshOnUi);
        });

        btnClearLog.setOnClickListener(v -> {
            Logx.clear();
            renderLog();
        });
        btnCopyLog.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("LuminPro", String.join("\n", Logx.snapshot())));
                Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    // ── 动作 ────────────────────────────────────────────────────────────────

    private void toggleLock() {
        final int target = Math.max(1, seekTarget.getProgress());
        Prefs.setTarget(this, target);
        final Context ctx = getApplicationContext();
        final boolean wasLocked = Prefs.enabled(this);

        btnToggle.setEnabled(false);
        tvLockHint.setText(wasLocked ? "正在解除…" : "正在锁定…");

        Bg.run(() -> {
            if (wasLocked) {
                BrightnessController.unlock(ctx);
            } else {
                BrightnessController.lock(ctx, target);
            }
            runOnUiThread(() -> {
                btnToggle.setEnabled(true);
                renderLockState();
            });
            refreshOnUi();
        });
    }

    private void applyTarget(int value, String okMessage) {
        final Context ctx = getApplicationContext();
        final int v = Math.max(1, value);
        Bg.run(() -> {
            boolean ok = BrightnessController.applyOnce(ctx, v);
            runOnUiThread(() -> Toast.makeText(this,
                    ok ? okMessage : "写入失败，详见日志", Toast.LENGTH_SHORT).show());
            refreshOnUi();
        });
    }

    private void restartKeepAliveIfNeeded() {
        if (Prefs.keepAlive(this) && Prefs.enabled(this)) {
            LockService.stop(this);
            LockService.start(this);
        }
    }

    // ── 状态刷新 ────────────────────────────────────────────────────────────

    /** 后台探测后回到主线程渲染。 */
    private void refresh() {
        Bg.run(this::refreshOnUi);
    }

    private void refreshOnUi() {
        final BrightnessController.State st = BrightnessController.refresh(this);
        runOnUiThread(() -> renderState(st));
    }

    private void renderState(BrightnessController.State st) {
        if (st.nodeMax > 0) {
            nodeMax = st.nodeMax;
            applyNodeRange();
        }
        renderLockState();

        // 探测出节点之前禁用所有写入入口：此时滑块量程只是占位值，
        // 提前写入会按占位量程截断成错误的亮度。
        boolean ready = nodeMax > 0;
        btnToggle.setEnabled(ready);
        btnApply.setEnabled(ready);
        btnMax.setEnabled(ready);
        seekTarget.setEnabled(ready);
        btnToggle.setAlpha(ready ? 1f : 0.5f);
        btnApply.setAlpha(ready ? 1f : 0.5f);
        btnMax.setAlpha(ready ? 1f : 0.5f);

        switch (st.mode) {
            case Prefs.MODE_MODULE:
                tvBadge.setText("模块模式");
                tvBadge.setTextColor(getColor(R.color.ok));
                break;
            case Prefs.MODE_DIRECT:
                tvBadge.setText("Root 直写");
                tvBadge.setTextColor(getColor(R.color.warn));
                break;
            default:
                tvBadge.setText("未就绪");
                tvBadge.setTextColor(getColor(R.color.err));
                break;
        }

        tvCurrent.setText(st.current >= 0 ? String.valueOf(st.current) : "—");
        tvCurrentMax.setText("/ " + (nodeMax > 0 ? String.valueOf(nodeMax) : "—"));

        tvMode.setText(st.message.isEmpty() ? "—" : st.message);
        tvNode.setText("亮度节点：" + (st.node.isEmpty() ? "—" : st.node));
        tvModule.setText("模块：" + (st.moduleVersion.isEmpty()
                ? (Prefs.MODE_MODULE.equals(st.mode) ? "已安装" : "未检测到")
                : st.moduleVersion + (Root.ready() ? " · root 已授权" : "")));

        String err = Prefs.lastError(this);
        if (err.isEmpty()) {
            tvError.setVisibility(View.GONE);
        } else {
            tvError.setVisibility(View.VISIBLE);
            tvError.setText(err);
        }
    }

    private void renderLockState() {
        boolean locked = Prefs.enabled(this);
        btnToggle.setText(locked ? "解除锁定" : "锁定亮度");
        tvLockHint.setText(locked
                ? ("已锁定 " + Prefs.target(this) + "／" + (nodeMax > 0 ? nodeMax : "?")
                    + (Prefs.keepAlive(this) ? " · 持续保持中" : ""))
                : "点击快捷设置磁贴即可随时锁定");
    }

    private void renderTarget() {
        int value = Math.max(1, seekTarget.getProgress());
        tvTarget.setText(String.valueOf(value));
        int pct = nodeMax > 0 ? Math.round(value * 100f / nodeMax) : 0;
        tvTargetPct.setText(pct + "%");
        tvTargetRange.setText("可调范围 1 – " + (nodeMax > 0 ? nodeMax : "—"));
    }

    private void renderInterval() {
        int ms = progressToInterval(seekInterval.getProgress());
        tvInterval.setText(String.format(java.util.Locale.US, "%.1f 秒", ms / 1000f));
    }

    // 滑块 0..15 映射到 500ms..8000ms，步长 500ms
    private static int progressToInterval(int progress) {
        return 500 + Math.max(0, Math.min(15, progress)) * 500;
    }

    private static int intervalToProgress(int ms) {
        return Math.max(0, Math.min(15, Math.round((ms - 500) / 500f)));
    }

    // ── 日志 ────────────────────────────────────────────────────────────────

    private void appendLog(String line) {
        tvLog.append("\n" + line);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void renderLog() {
        java.util.List<String> lines = Logx.snapshot();
        tvLog.setText(lines.isEmpty() ? "等待操作…" : String.join("\n", lines));
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderLockState();
        refresh();
    }

    @Override
    protected void onDestroy() {
        Logx.setListener(null);
        super.onDestroy();
    }
}
