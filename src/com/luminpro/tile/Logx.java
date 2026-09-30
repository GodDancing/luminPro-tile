package com.luminpro.tile;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 极简日志：内存环形缓冲 + logcat 镜像。
 * 界面通过 {@link #setListener} 订阅，daemon 侧进程被杀后日志自然清空。
 */
final class Logx {

    private static final String TAG = "LuminProTile";
    private static final int CAP = 300;

    interface Listener {
        void onLine(String line);
    }

    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static Listener listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Logx() {
    }

    static void i(String msg) {
        add("I", msg);
    }

    static void w(String msg) {
        add("W", msg);
    }

    static void e(String msg) {
        add("E", msg);
    }

    private static void add(String level, String msg) {
        // SimpleDateFormat 不是线程安全的，而日志会被多条线程同时写入
        final String line;
        synchronized (FMT) {
            line = FMT.format(new Date()) + " [" + level + "] " + msg;
        }
        android.util.Log.println(level.equals("E") ? 6 : level.equals("W") ? 5 : 4, TAG, msg);

        final Listener l;
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > CAP) {
                LINES.removeFirst();
            }
            l = listener;
        }
        if (l != null) {
            MAIN.post(() -> l.onLine(line));
        }
    }

    static void setListener(Listener l) {
        synchronized (LINES) {
            listener = l;
        }
    }

    static List<String> snapshot() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }
}
