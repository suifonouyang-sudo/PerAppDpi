package com.perapp.dpi;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 前台服务：常驻监听「当前前台应用是谁」，在切换时套用 / 还原显示密度。
 *
 * <p>三条关键设计：
 * <ul>
 *   <li><b>合并抖动</b>：改密度本身会让目标应用重建活动，过程中会再冒出前台事件。
 *       所有事件先进 {@code pending}，静默 400ms（改过密度后 1200ms）再统一处理最终那个包，
 *       避免「切成 A 又看到 B」造成的来回跳变。</li>
 *   <li><b>防乒乓</b>：短时间内连续多次真的改了密度，就暂停 8 秒并记日志，
 *       宁可漏一次也不要把设备拖进无限互切。</li>
 *   <li><b>熄屏省电</b>：屏幕熄灭时停掉轮询，亮屏时恢复并立刻重新判断。</li>
 * </ul>
 */
public class WatchService extends Service {

    public static final String ACT_START = "com.perapp.dpi.START";
    public static final String ACT_STOP = "com.perapp.dpi.STOP";
    public static final String ACT_TOGGLE = "com.perapp.dpi.TOGGLE";
    public static final String ACT_REFRESH = "com.perapp.dpi.REFRESH";

    /** 供 UI / 无障碍服务读取的运行状态 */
    public static volatile boolean running;
    public static volatile boolean paused;
    public static volatile String lastFore = "";
    public static volatile String lastDesc = "—";

    private static final long SETTLE_MS = 400;
    private static final long QUIET_AFTER_CHANGE_MS = 1200;

    private DensityEngine engine;
    private UsagePoll poll;
    private Handler ui;
    private ExecutorService exec;

    private volatile String pending;
    private volatile long quietUntil;
    private volatile int flipCount;
    private volatile long flipWindowStart;
    private volatile long flipPauseUntil;

    private BroadcastReceiver screenRx;

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        engine = new DensityEngine(this);
        poll = new UsagePoll(this);
        ui = new Handler(Looper.getMainLooper());
        exec = Executors.newSingleThreadExecutor();
        FgBus.setSink(pkg -> onForeground(pkg));
        AppLog.i("SVC", "服务创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? null : intent.getAction();

        if (ACT_STOP.equals(act)) {
            AppLog.i("SVC", "收到停止指令");
            stopWatch();
            return START_NOT_STICKY;
        }
        if (ACT_REFRESH.equals(act)) {
            // 已在跑就重新判断一次；没在跑就启动（启动时会自动判定当前前台应用）
            if (running && !paused) {
                forceCheck();
                updateNotif();
            } else {
                beginStart();
            }
            return START_STICKY;
        }
        if (ACT_TOGGLE.equals(act)) {
            paused = !paused;
            AppLog.i("SVC", "暂停状态 -> " + paused);
            if (paused) {
                poll.stop();
            } else {
                poll.reset();
                poll.start();
                forceCheck();
            }
            updateNotif();
            return START_STICKY;
        }

        beginStart();
        return START_STICKY;
    }

    /** 启动前台服务 + 首次初始化（幂等：已在跑则只刷新通知） */
    private void beginStart() {
        startForeground(Notif.ID, Notif.build(this, "单应用DPI 运行中", "正在启动…", paused));
        if (!running) {
            running = true;
            paused = false;
            DpiStore.setWatching(this, true);
            registerScreen();
            boot();
        } else {
            updateNotif();
        }
    }

    /** 启动时的准备工作全在后台线程：清遗留、补权限、读一次现状 */
    private void boot() {
        exec.execute(() -> {
            String clean = engine.cleanupLeftover();
            if (clean != null) AppLog.i("SVC", clean);

            boolean usageOk = UsagePoll.allowed(this) || UsagePoll.grantViaShizuku(this);

            String mode = resolveMode(usageOk);
            AppLog.i("SVC", "识别方式 = " + mode
                    + "（无障碍=" + A11yWatch.isEnabled(this) + "，使用情况权限=" + usageOk + "）");

            DensityEngine.Info info = engine.read();
            AppLog.i("SVC", "启动现状 " + info);

            ui.post(() -> {
                if (A11yWatch.isEnabled(this)) {
                    AppLog.i("SVC", "使用无障碍事件（极速模式）");
                } else if (usageOk) {
                    poll.reset();
                    poll.start();
                } else {
                    AppLog.i("SVC", "两种识别方式都不可用：请开无障碍，或手动给「使用情况访问」权限");
                }
                updateNotif();
                forceCheck();
            });
        });
    }

    private String resolveMode(boolean usageOk) {
        String m = DpiStore.mode(this);
        boolean a11y = A11yWatch.isEnabled(this);
        if (DpiStore.MODE_A11Y.equals(m)) return a11y ? DpiStore.MODE_A11Y : DpiStore.MODE_USAGE;
        if (DpiStore.MODE_USAGE.equals(m)) return DpiStore.MODE_USAGE;
        // 自动：优先无障碍（即时），否则用使用情况轮询（零操作，稍慢）
        return a11y ? DpiStore.MODE_A11Y : DpiStore.MODE_USAGE;
    }

    /** 立刻按最后一次识别到的前台应用判断一次（用于启动、暂停恢复、手动触发） */
    public void forceCheck() {
        String p = lastFore;
        if (p == null || p.isEmpty()) {
            // 还没有任何前台信息时，用使用情况统计兜底探一次
            if (!paused) {
                poll.reset();
                poll.start();
            }
            return;
        }
        submit(p);
    }

    private void onForeground(String pkg) {
        if (!running || paused) return;
        submit(pkg);
    }

    /** 事件合并：等到安静下来再处理最终的那个包 */
    private void submit(String pkg) {
        pending = pkg;
        ui.removeCallbacks(settle);
        long delay = Math.max(SETTLE_MS, quietUntil - SystemClock.uptimeMillis());
        ui.postDelayed(settle, delay);
    }

    private final Runnable settle = new Runnable() {
        @Override
        public void run() {
            final String p = pending;
            pending = null;
            if (p == null || !running || paused) return;

            long now = SystemClock.uptimeMillis();
            if (now < flipPauseUntil) {
                AppLog.i("SVC", "防乒乓暂停中，忽略 " + p);
                return;
            }

            exec.execute(() -> {
                DensityEngine.Action a;
                try {
                    a = engine.handle(p);
                } catch (Throwable t) {
                    AppLog.i("SVC", "处理 " + p + " 出错: " + t);
                    return;
                }
                lastFore = p;
                lastDesc = a.desc;
                AppLog.i("SVC", "前台 " + p + " → " + a.desc);

                if (a.changed) {
                    quietUntil = SystemClock.uptimeMillis() + QUIET_AFTER_CHANGE_MS;
                    long t = SystemClock.uptimeMillis();
                    if (t - flipWindowStart > 8000) {
                        flipWindowStart = t;
                        flipCount = 0;
                    }
                    flipCount++;
                    if (flipCount > 5) {
                        flipPauseUntil = t + 8000;
                        flipCount = 0;
                        AppLog.i("SVC", "8 秒内已连续切换 6 次，疑似来回跳变，暂停 8 秒");
                    }
                }
                ui.post(WatchService.this::updateNotif);
            });
        }
    };

    private void updateNotif() {
        try {
            String fg = lastFore.isEmpty() ? "尚未识别到前台应用" : shorten(lastFore);
            int want = lastFore.isEmpty() ? 0 : DpiStore.get(this, lastFore);
            String body = fg
                    + (want > 0 ? " · 目标 " + want + "dpi" : " · 跟随系统")
                    + "  |  " + lastDesc;
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(Notif.ID, Notif.build(this, paused ? "单应用DPI 已暂停" : "单应用DPI 运行中",
                        body, paused));
            }
        } catch (Throwable t) {
            AppLog.i("SVC", "更新通知失败: " + t);
        }
    }

    private static String shorten(String pkg) {
        return pkg.length() > 28 ? pkg.substring(0, 26) + "…" : pkg;
    }

    private void registerScreen() {
        if (screenRx != null) return;
        screenRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = i == null ? null : i.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    poll.stop();
                    AppLog.i("SVC", "熄屏，暂停轮询");
                } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    AppLog.i("SVC", "亮屏，恢复轮询");
                    if (!paused && !A11yWatch.isEnabled(WatchService.this)) {
                        poll.reset();
                        poll.start();
                    }
                    submit(lastFore);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenRx, f);
    }

    private void stopWatch() {
        running = false;
        paused = false;
        DpiStore.setWatching(this, false);
        poll.stop();
        ui.removeCallbacks(settle);
        pending = null;
        exec.execute(() -> {
            // 只还原自己改过的覆盖；用户手动设的密度原样保留
            if (DpiStore.densityOwned(WatchService.this)) {
                DensityEngine.Info i = engine.reset();
                AppLog.i("SVC", "已还原系统密度，回读 " + i);
            } else {
                AppLog.i("SVC", "当前密度不是本应用设置的，保持不动");
            }
            ui.post(() -> {
                try {
                    stopForeground(true);
                } catch (Throwable ignored) {
                }
                stopSelf();
            });
        });
    }

    @Override
    public void onDestroy() {
        running = false;
        paused = false;
        try {
            if (screenRx != null) unregisterReceiver(screenRx);
        } catch (Throwable ignored) {
        }
        screenRx = null;
        FgBus.setSink(null);
        poll.stop();
        if (exec != null) exec.shutdown();
        AppLog.i("SVC", "服务销毁");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
