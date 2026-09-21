package com.perapp.dpi;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 悬浮窗服务：在所有应用之上显示一个可拖动的面板，实时调整「当前前台应用」的显示密度（DPI）。
 *
 * <p><b>与监控服务的关系：</b>本服务只负责「手动微调当前应用」。它把调整后的密度同时写入
 * {@link DpiStore}（成为该应用的按应用设置），并立即通过 {@link DensityEngine} 套用。
 * 若用户同时开启了监控（WatchService），离开此应用时监控会自动还原；本服务本身不负责自动还原。
 *
 * <p><b>为何要悬浮窗权限：</b>调整密度需要 Shizuku 以 shell 身份执行 {@code wm density}，
 * 因此启动前必须已获得 Shizuku 授权；而把面板画到别的 App 之上需要 SYSTEM_ALERT_WINDOW 权限。
 */
public class FloatService extends Service {

    public static final String ACT_START = "com.perapp.dpi.FLOAT_START";
    public static final String ACT_STOP = "com.perapp.dpi.FLOAT_STOP";

    /** 供 UI 读取的运行状态 */
    public static volatile boolean running;

    private static final int STEP = 4;

    private WindowManager wm;
    private View panel;
    private WindowManager.LayoutParams lp;
    private DensityEngine engine;
    private UsagePoll poll;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService exec;

    private TextView tvApp, tvDpi, tvStatus;
    private Button btnMinus, btnPlus, btnReset, btnClose;
    private View dragBar;

    private volatile String curPkg = "";
    private volatile int curTarget = 0; // 当前应用的目标密度；0 = 跟随系统

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        engine = new DensityEngine(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        poll = new UsagePoll(this);
        exec = Executors.newSingleThreadExecutor();
        AppLog.i("FLOAT", "悬浮窗服务创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? null : intent.getAction();
        if (ACT_STOP.equals(act)) {
            stopFloat();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            if (!buildPanel()) {
                // 没有悬浮窗权限就不起服务，避免空跑
                running = false;
                stopSelf();
                return START_NOT_STICKY;
            }
            startForeground(Notif.ID + 1,
                    buildNotif("拖动面板可移动；− / + 实时调整当前应用密度"));
            poll.setListener(pkg -> onForeground(pkg));
            poll.reset();
            poll.start();
            ui.post(this::refreshCur);
            ui.post(ticker);
            AppLog.i("FLOAT", "悬浮窗已显示");
        }
        return START_STICKY;
    }

    private boolean buildPanel() {
        if (wm == null) return false;
        try {
            panel = LayoutInflater.from(this).inflate(R.layout.float_panel, null);
        } catch (Throwable t) {
            AppLog.i("FLOAT", "inflate 失败: " + t);
            return false;
        }
        tvApp = panel.findViewById(R.id.floatApp);
        tvDpi = panel.findViewById(R.id.floatDpi);
        tvStatus = panel.findViewById(R.id.floatStatus);
        btnMinus = panel.findViewById(R.id.btnMinus);
        btnPlus = panel.findViewById(R.id.btnPlus);
        btnReset = panel.findViewById(R.id.btnReset);
        btnClose = panel.findViewById(R.id.btnClose);
        dragBar = panel.findViewById(R.id.floatDrag);

        btnMinus.setOnClickListener(v -> adjust(-STEP));
        btnPlus.setOnClickListener(v -> adjust(+STEP));
        btnReset.setOnClickListener(v -> resetCurrent());
        btnClose.setOnClickListener(v -> stopFloat());
        dragBar.setOnTouchListener(new DragListener());

        lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.x = 24;
        lp.y = 220;
        try {
            wm.addView(panel, lp);
            return true;
        } catch (Throwable t) {
            AppLog.i("FLOAT", "addView 失败（多半未获悬浮窗权限）: " + t);
            panel = null;
            return false;
        }
    }

    /** 前台应用切换：更新当前包名与目标密度，并刷新面板 */
    private void onForeground(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        curPkg = pkg;
        curTarget = DpiStore.get(this, pkg);
        ui.post(this::refreshCur);
    }

    /** 重新读取当前前台应用并刷新（用于启动、切回 App 时） */
    private void refreshCur() {
        if (curPkg.isEmpty()) tvApp.setText("识别中…");
        readAndShow();
    }

    /** 后台读取密度与包名信息，再回主线程刷新面板（避免在主线程阻塞等待 Shizuku） */
    private void readAndShow() {
        final String label = curPkg.isEmpty() ? "识别中…" : AppList.labelOf(FloatService.this, curPkg);
        if (!ShizukuShell.isReady()) {
            // Shizuku 未授权时只刷新文案，不再每秒去撞 wm density（避免刷屏日志）
            ui.post(() -> {
                if (tvApp == null) return;
                tvApp.setText(label);
                tvDpi.setText(curTarget > 0 ? curTarget + " dpi" : "需授权");
                tvStatus.setText(curTarget > 0 ? "已托管" : "跟随系统");
                tvStatus.setTextColor(curTarget > 0 ? 0xFF9AE6B0 : 0xFFBFC7D1);
            });
            return;
        }
        exec.execute(() -> {
            DensityEngine.Info info = engine.read();
            int eff = info.effective();
            int shown = curTarget > 0 ? curTarget : eff;
            ui.post(() -> {
                if (tvApp == null) return;
                tvApp.setText(label);
                tvDpi.setText(shown + " dpi");
                if (curTarget > 0) {
                    tvStatus.setText("已托管");
                    tvStatus.setTextColor(0xFF9AE6B0);
                } else {
                    tvStatus.setText("跟随系统");
                    tvStatus.setTextColor(0xFFBFC7D1);
                }
            });
        });
    }

    /** 调整当前应用密度 ±step */
    private void adjust(int delta) {
        if (curPkg.isEmpty()) {
            toast("尚未识别到前台应用");
            return;
        }
        if (!ShizukuShell.isReady()) {
            AppLog.i("FLOAT", "Shizuku 未授权，无法调整");
            toast("请先在「运行」页授权 Shizuku");
            return;
        }
        final String pkg = curPkg;
        exec.execute(() -> {
            DensityEngine.Info info = engine.read();
            int base = curTarget > 0 ? curTarget : info.effective();
            int next = DensityEngine.clamp(base + delta);
            if (next <= 0) return;
            DpiStore.set(FloatService.this, pkg, next);
            engine.apply(next);
            curTarget = next;
            ui.post(() -> {
                readAndShow();
                updateNotif("当前 " + AppList.labelOf(FloatService.this, pkg) + " → " + next + " dpi");
            });
            AppLog.i("FLOAT", "调整 " + pkg + " -> " + next + "dpi");
        });
    }

    /** 当前应用恢复跟随系统密度 */
    private void resetCurrent() {
        if (curPkg.isEmpty()) {
            toast("尚未识别到前台应用");
            return;
        }
        if (!ShizukuShell.isReady()) {
            toast("请先在「运行」页授权 Shizuku");
            return;
        }
        final String pkg = curPkg;
        exec.execute(() -> {
            DpiStore.remove(FloatService.this, pkg);
            engine.reset();
            curTarget = 0;
            ui.post(() -> {
                readAndShow();
                updateNotif("当前 " + AppList.labelOf(FloatService.this, pkg) + " → 跟随系统");
            });
            AppLog.i("FLOAT", "重置 " + pkg + " -> 跟随系统");
        });
    }

    // ---------------- 通知 ----------------

    private android.app.Notification buildNotif(String content) {
        Notif.ensureChannel(this);
        android.content.Intent stop = new android.content.Intent(this, FloatService.class)
                .setAction(ACT_STOP);
        android.app.PendingIntent pi = android.app.PendingIntent.getService(this, 31, stop,
                android.app.PendingIntent.FLAG_IMMUTABLE
                        | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, Notif.CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("悬浮窗调整 DPI")
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, "关闭悬浮窗", pi)
                .build();
    }

    private void updateNotif(String content) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(Notif.ID + 1, buildNotif(content));
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 周期刷新（让面板上的实际密度与系统同步） ----------------

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            readAndShow();
            ui.postDelayed(this, 1000);
        }
    };

    // ---------------- 拖动 ----------------

    private class DragListener implements View.OnTouchListener {
        private int downX, downY, startX, startY;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = (int) e.getRawX();
                    downY = (int) e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = startX + (int) e.getRawX() - downX;
                    lp.y = startY + (int) e.getRawY() - downY;
                    if (wm != null && panel != null) wm.updateViewLayout(panel, lp);
                    return true;
                default:
                    return false;
            }
        }
    }

    private void toast(String s) {
        ui.post(() -> android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show());
    }

    private void stopFloat() {
        running = false;
        ui.removeCallbacks(ticker);
        if (poll != null) poll.stop();
        if (wm != null && panel != null) {
            try {
                wm.removeView(panel);
            } catch (Throwable ignored) {
            }
            panel = null;
        }
        AppLog.i("FLOAT", "悬浮窗已关闭");
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacks(ticker);
        if (poll != null) poll.stop();
        if (wm != null && panel != null) {
            try {
                wm.removeView(panel);
            } catch (Throwable ignored) {
            }
            panel = null;
        }
        if (exec != null) exec.shutdown();
        AppLog.i("FLOAT", "悬浮窗服务销毁");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
