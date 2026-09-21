package com.perapp.dpi;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

/**
 * 用「使用情况访问」事件流判断前台应用。
 *
 * <p>为什么走这条路：<code>dumpsys activity</code> 轮询每秒要起一个 shell 进程，太重；
 * <code>am monitor</code> 只报告「全新启动」的活动，普通的应用切换收不到（实测）。
 * <code>UsageStatsManager.queryEvents</code> 是进程内的 binder 调用，几百毫秒一次几乎无成本，
 * 而且它需要的 GET_USAGE_STATS 权限可以由本应用经 Shizuku 用 shell 身份给自己授予 —— 用户零操作。
 */
public final class UsagePoll implements Runnable {

    /** ACTIVITY_RESUMED == MOVE_TO_FOREGROUND == 1 */
    private static final int EV_RESUMED = UsageEvents.Event.ACTIVITY_RESUMED;

    private final Context app;
    private final UsageStatsManager usm;
    private final Handler h = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private volatile int intervalMs = 500;
    private long lastTs;
    private String lastPkg;
    private boolean errLogged;

    /** 直接回调（优先于 FgBus）：供悬浮窗等独立组件使用，避免和 WatchService 抢唯一 Sink */
    public interface ForegroundListener {
        void onForeground(String pkg);
    }

    private volatile ForegroundListener listener;

    public void setListener(ForegroundListener l) {
        this.listener = l;
    }

    public UsagePoll(Context c) {
        this.app = c.getApplicationContext();
        this.usm = (UsageStatsManager) app.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    /** 当前是否已拿到「使用情况访问」权限 */
    public static boolean allowed(Context c) {
        try {
            AppOpsManager am = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (am == null) return false;
            int mode = am.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 经 Shizuku 以 shell 身份给自己授予 GET_USAGE_STATS（省掉用户去设置里点一遍） */
    public static boolean grantViaShizuku(Context c) {
        if (allowed(c)) return true;
        if (!ShizukuShell.isReady()) return false;
        ShizukuShell.exec(c, "appops set " + c.getPackageName() + " GET_USAGE_STATS allow");
        boolean ok = allowed(c);
        AppLog.i("FG", "自动授予 GET_USAGE_STATS -> " + (ok ? "OK" : "失败，需手动开启"));
        return ok;
    }

    public void setInterval(int ms) {
        this.intervalMs = Math.max(200, ms);
    }

    public void start() {
        if (running) return;
        running = true;
        h.removeCallbacks(this);
        h.post(this);
        AppLog.i("FG", "使用情况轮询启动，间隔 " + intervalMs + "ms");
    }

    public void stop() {
        if (!running) return;
        running = false;
        h.removeCallbacks(this);
        AppLog.i("FG", "使用情况轮询停止");
    }

    /** 重置基线，下一次轮询会重新判断当前前台应用 */
    public void reset() {
        lastTs = 0;
        lastPkg = null;
    }

    @Override
    public void run() {
        if (!running) return;
        try {
            tick();
        } catch (Throwable t) {
            if (!errLogged) {
                errLogged = true;
                AppLog.i("FG", "queryEvents 失败（多半没有使用情况访问权限）: " + t);
            }
        }
        if (running) h.postDelayed(this, intervalMs);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long begin = now - 20000;
        if (lastTs > begin) begin = lastTs;

        UsageEvents ev = usm.queryEvents(begin, now);
        if (ev == null) return;

        UsageEvents.Event e = new UsageEvents.Event();
        String found = null;
        long ts = 0;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() != EV_RESUMED) continue;
            String p = e.getPackageName();
            if (p == null || p.isEmpty()) continue;
            if (e.getTimeStamp() >= ts) {
                ts = e.getTimeStamp();
                found = p;
            }
        }
        if (found == null || ts <= lastTs) return;

        lastTs = ts;
        if (found.equals(lastPkg)) return;
        lastPkg = found;
        ForegroundListener l = listener;
        if (l != null) l.onForeground(found);
        else FgBus.dispatch(found);
    }

    /** 供 UI 展示：最近一次识别到的时间戳（uptime 无关，仅调试用） */
    public static long nowUptime() {
        return SystemClock.uptimeMillis();
    }
}
