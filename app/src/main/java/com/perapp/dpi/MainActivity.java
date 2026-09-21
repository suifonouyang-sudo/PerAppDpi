package com.perapp.dpi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * 三页主界面：应用（配置每应用 DPI）／运行（开关监控与环境）／日志。
 *
 * <p>顶部状态栏常驻显示 Shizuku 授权与监控状态，任何页面都能一眼看到。
 */
public class MainActivity extends Activity {

    private static final int REQ_CODE = 1001;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // 应用页
    private EditText etSearch;
    private ListView lvApps;
    private TextView tvAppsSummary;
    private LinearLayout llScopeTabs;
    private LinearLayout llTypeTabs;
    private DpiAdapter adapter;
    private List<AppList.Item> fullList = new ArrayList<>();
    private int scope = 0; // 0 全部 / 1 已托管 / 2 未托管
    private int type = 0;  // 0 全部 / 1 用户 / 2 系统

    // 运行页
    private Switch swWatch;
    private TextView tvWatchState;
    private LinearLayout llModeTabs;
    private TextView tvEnvShizuku, tvEnvPhysical, tvEnvOverride, tvEnvFore, tvEnvA11y, tvEnvUsage;
    private TextView tvHelp, tvAdb;
    private Switch swFloat;
    private TextView tvFloatState;

    // 日志页
    private TextView tvLog;

    // 顶栏
    private TextView tvTopStatus;

    // 顶栏下方：本应用密度快捷调节
    private TextView tvSelfDpi;
    private Button btnSelfMinus, btnSelfPlus, btnSelfReset;
    private static final int SELF_STEP = 5;

    private final StringBuilder logBuf = new StringBuilder();

    /** 悬浮窗权限（SYSTEM_ALERT_WINDOW）申请请求码 */
    private static final int REQ_OVERLAY = 2001;

    /** 已发起悬浮窗权限引导、等待用户回来后重新校验标记 */
    private boolean awaitingOverlay = false;

    /** 监听悬浮窗真正加窗失败，引导用户去开权限 */
    private final BroadcastReceiver floatFailRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (FloatService.ACT_PERM_FAIL.equals(intent.getAction())) {
                String msg = intent.getStringExtra("msg");
                ui.post(() -> {
                    if (swFloat != null) swFloat.setChecked(false);
                    showOverlayGuide(msg);
                });
            }
        }
    };

    // Shizuku 监听（注册一次，活动销毁时注销）
    private final Shizuku.OnBinderReceivedListener binderRx = () -> ui.post(this::refreshAll);
    private final Shizuku.OnBinderDeadListener binderDead = () -> ui.post(this::refreshAll);
    private final Shizuku.OnRequestPermissionResultListener permRx =
            (code, res) -> { if (code == REQ_CODE) ui.post(this::refreshAll); };

    private final Runnable foreTicker = new Runnable() {
        @Override
        public void run() {
            if (tvEnvFore != null) updateForeField();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        AppLog.init(this);
        AppLog.setSink(line -> ui.post(() -> appendLog(line)));

        try {
            Shizuku.addBinderReceivedListener(binderRx);
            Shizuku.addBinderDeadListener(binderDead);
            Shizuku.addRequestPermissionResultListener(permRx);
        } catch (Throwable t) {
            AppLog.i("UI", "注册 Shizuku 监听失败（不影响基本功能）: " + t);
        }

        try {
            registerReceiver(floatFailRx, new IntentFilter(FloatService.ACT_PERM_FAIL), Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            AppLog.i("UI", "注册悬浮窗失败监听失败: " + t);
        }

        tvTopStatus = findViewById(R.id.tvTopStatus);
        setupTabs();
        setupApps();
        setupRun();
        setupLog();
        setupSelfDpi();

        showPage(0);

        // 后台载入应用清单，避免阻塞首帧
        new Thread(this::loadApps).start();

        // 启动时按已保存的本应用密度套用一次（仅当 Shizuku 已就绪）
        new Thread(() -> {
            if (ShizukuShell.isReady()) {
                int saved = DpiStore.get(this, selfPkg());
                if (saved > 0) {
                    new DensityEngine(this).apply(saved);
                    ui.post(this::refreshSelfDpi);
                }
            }
        }).start();

        ui.post(foreTicker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
        // 从权限设置页回来后重新校验：部分 ROM 的 onActivityResult 不触发，这里兜底
        if (awaitingOverlay && OverlayPerm.granted(this)) {
            awaitingOverlay = false;
            if (swFloat != null) swFloat.setChecked(true);
            startFloat();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(foreTicker);
        AppLog.setSink(null);
        try {
            Shizuku.removeBinderReceivedListener(binderRx);
            Shizuku.removeBinderDeadListener(binderDead);
            Shizuku.removeRequestPermissionResultListener(permRx);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(floatFailRx);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 顶栏与分页 ----------------

    private void setupTabs() {
        View.OnClickListener go = v -> {
            int p = 0;
            int id = v.getId();
            if (id == R.id.tabApps) p = 0;
            else if (id == R.id.tabRun) p = 1;
            else if (id == R.id.tabLog) p = 2;
            showPage(p);
        };
        findViewById(R.id.tabApps).setOnClickListener(go);
        findViewById(R.id.tabRun).setOnClickListener(go);
        findViewById(R.id.tabLog).setOnClickListener(go);
    }

    private void showPage(int p) {
        findViewById(R.id.pageApps).setVisibility(p == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.pageRun).setVisibility(p == 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.pageLog).setVisibility(p == 2 ? View.VISIBLE : View.GONE);

        setBar(R.id.tabAppsTitle, R.id.tabAppsBar, p == 0);
        setBar(R.id.tabRunTitle, R.id.tabRunBar, p == 1);
        setBar(R.id.tabLogTitle, R.id.tabLogBar, p == 2);

        if (p == 1) refreshRunPage();
        if (p == 2) refreshLogView();
    }

    private void setBar(int titleId, int barId, boolean on) {
        TextView t = findViewById(titleId);
        View bar = findViewById(barId);
        t.setTextColor(getColor(on ? R.color.primary : R.color.text_dim));
        t.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        bar.setBackgroundColor(getColor(on ? R.color.primary : R.color.bg));
    }

    // ---------------- 应用页 ----------------

    private void setupApps() {
        etSearch = findViewById(R.id.etSearch);
        lvApps = findViewById(R.id.lvApps);
        tvAppsSummary = findViewById(R.id.tvAppsSummary);
        llScopeTabs = findViewById(R.id.llScopeTabs);
        llTypeTabs = findViewById(R.id.llTypeTabs);
        adapter = new DpiAdapter(this);
        lvApps.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) { }
        });

        lvApps.setOnItemClickListener((parent, view, pos, id) -> {
            AppList.Item it = adapter.getItem(pos);
            if (it != null) openDpiDialog(it.pkg, it.label);
        });

        findViewById(R.id.btnManualPkg).setOnClickListener(v -> openManualPkg());

        rebuildScopeChips();
        rebuildTypeChips();
    }

    private void rebuildScopeChips() {
        llScopeTabs.removeAllViews();
        String[] names = {"全部", "已设置", "未设置"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            addChip(llScopeTabs, names[i], scope == i, () -> { scope = idx; rebuildScopeChips(); applyFilter(); });
        }
    }

    private void rebuildTypeChips() {
        llTypeTabs.removeAllViews();
        String[] names = {"全部类型", "用户应用", "系统应用"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            addChip(llTypeTabs, names[i], type == i, () -> { type = idx; rebuildTypeChips(); applyFilter(); });
        }
    }

    private void addChip(ViewGroup parent, String text, boolean sel, Runnable onClick) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setBackgroundResource(sel ? R.drawable.chip_bg_on : R.drawable.chip_bg);
        t.setTextColor(getColor(sel ? R.color.primary : R.color.text_dim));
        t.setOnClickListener(v -> onClick.run());
        parent.addView(t);
        // 标签之间留点间距
        View gap = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), 1);
        parent.addView(gap, lp);
    }

    private void loadApps() {
        final List<AppList.Item> list = AppList.load(this);
        ui.post(() -> {
            fullList = list;
            applyFilter();
        });
    }

    private void applyFilter() {
        if (fullList == null) return;
        String q = etSearch.getText().toString().trim().toLowerCase(Locale.CHINA);
        List<AppList.Item> out = new ArrayList<>();
        for (AppList.Item it : fullList) {
            if (scope == 1 && it.dpi <= 0) continue;
            if (scope == 2 && it.dpi > 0) continue;
            if (type == 1 && it.system) continue;
            if (type == 2 && !it.system) continue;
            if (!q.isEmpty() && !(it.label.toLowerCase(Locale.CHINA).contains(q)
                    || it.pkg.toLowerCase(Locale.CHINA).contains(q))) {
                continue;
            }
            out.add(it);
        }
        adapter.setData(out);
        int managed = DpiStore.count(this);
        tvAppsSummary.setText(String.format(Locale.CHINA,
                "共 %d 个应用 · 已设置密度 %d 个 · 当前筛选 %d 个",
                fullList.size(), managed, out.size()));
    }

    private void openManualPkg() {
        EditText et = new EditText(this);
        et.setHint("例如 com.example.app");
        et.setSingleLine();
        et.setTextSize(14);
        new AlertDialog.Builder(this)
                .setTitle("手动设置包名密度")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("下一步", (d, w) -> {
                    String pkg = et.getText().toString().trim();
                    if (pkg.isEmpty()) return;
                    openDpiDialog(pkg, AppList.labelOf(this, pkg));
                })
                .show();
    }

    private void openDpiDialog(String pkg, String label) {
        DensityEngine.Info info = new DensityEngine(this).read();
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_dpi, null);
        TextView dlgApp = v.findViewById(R.id.dlgApp);
        TextView dlgPkg = v.findViewById(R.id.dlgPkg);
        EditText dlgInput = v.findViewById(R.id.dlgInput);
        LinearLayout dlgPresets = v.findViewById(R.id.dlgPresets);
        LinearLayout dlgFine = v.findViewById(R.id.dlgFine);
        TextView dlgHint = v.findViewById(R.id.dlgHint);

        dlgApp.setText(label);
        dlgPkg.setText(pkg);
        int cur = DpiStore.get(this, pkg);
        dlgInput.setHint("系统密度 " + info.effective());
        if (cur > 0) dlgInput.setText(String.valueOf(cur));

        dlgHint.setText(String.format(Locale.CHINA,
                "范围 %d–%d dpi。设为 0 即跟随系统密度（%d）。数值越小界面越大越清晰，越大越能显示更多内容。",
                DensityEngine.MIN, DensityEngine.MAX, info.effective()));

        int[] presets = {120, 160, 200, 213, 240, 280, 320, 400, 480};
        for (int p : presets) {
            TextView c = new TextView(this);
            c.setText(String.valueOf(p));
            c.setTextSize(12);
            c.setPadding(dp(10), dp(6), dp(10), dp(6));
            c.setBackgroundResource(R.drawable.chip_bg);
            c.setTextColor(getColor(R.color.text_dim));
            c.setOnClickListener(x -> dlgInput.setText(String.valueOf(p)));
            dlgPresets.addView(c);
            dlgPresets.addView(gap());
        }

        Button minus = new Button(this);
        minus.setText("− 4");
        minus.setTextSize(13);
        minus.setOnClickListener(x -> bump(dlgInput, -4));
        Button plus = new Button(this);
        plus.setText("+ 4");
        plus.setTextSize(13);
        plus.setOnClickListener(x -> bump(dlgInput, +4));
        dlgFine.addView(minus);
        dlgFine.addView(gap());
        dlgFine.addView(plus);

        new AlertDialog.Builder(this)
                .setTitle("设置显示密度")
                .setView(v)
                .setNegativeButton("清除（跟随系统）", (d, w) -> {
                    DpiStore.remove(this, pkg);
                    AppLog.i("UI", "清除 " + pkg + " 的密度配置（改回跟随系统）");
                    AppList.refreshDpi(this, fullList);
                    applyFilter();
                    bumpService();
                })
                .setNeutralButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    String s = dlgInput.getText().toString().trim();
                    int dpi = 0;
                    try {
                        if (!s.isEmpty()) dpi = Integer.parseInt(s);
                    } catch (Throwable ignored) {
                    }
                    if (dpi < 0) dpi = 0;
                    if (dpi == 0) {
                        DpiStore.remove(this, pkg);
                    } else {
                        dpi = DensityEngine.clamp(dpi);
                        DpiStore.set(this, pkg, dpi);
                    }
                    AppLog.i("UI", "设置 " + pkg + " -> " + (dpi > 0 ? dpi + "dpi" : "跟随系统"));
                    AppList.refreshDpi(this, fullList);
                    applyFilter();
                    bumpService();
                })
                .show();
    }

    private void bumpService() {
        // 配置变化后，让运行中的服务按最新前台应用重新判定一次
        if (WatchService.running && !WatchService.paused) {
            startService(new Intent(this, WatchService.class).setAction(WatchService.ACT_REFRESH));
        }
    }

    private void bump(EditText et, int d) {
        int v = 0;
        try {
            String s = et.getText().toString().trim();
            if (!s.isEmpty()) v = Integer.parseInt(s);
        } catch (Throwable ignored) {
        }
        v = DensityEngine.clamp(v + d);
        et.setText(String.valueOf(v));
        et.setSelection(et.getText().length());
    }

    // ---------------- 运行页 ----------------

    private void setupRun() {
        swWatch = findViewById(R.id.swWatch);
        tvWatchState = findViewById(R.id.tvWatchState);
        llModeTabs = findViewById(R.id.llModeTabs);
        tvEnvShizuku = findViewById(R.id.tvEnvShizuku);
        tvEnvPhysical = findViewById(R.id.tvEnvPhysical);
        tvEnvOverride = findViewById(R.id.tvEnvOverride);
        tvEnvFore = findViewById(R.id.tvEnvFore);
        tvEnvA11y = findViewById(R.id.tvEnvA11y);
        tvEnvUsage = findViewById(R.id.tvEnvUsage);
        tvHelp = findViewById(R.id.tvHelp);
        tvAdb = findViewById(R.id.tvAdb);

        swWatch.setOnCheckedChangeListener((btn, on) -> toggleWatch(on));

        swFloat = findViewById(R.id.swFloat);
        tvFloatState = findViewById(R.id.tvFloatState);
        swFloat.setOnCheckedChangeListener((btn, on) -> toggleFloat(on));

        findViewById(R.id.btnApplyNow).setOnClickListener(v -> {
            if (!ShizukuShell.isReady()) { ensureShizuku(); return; }
            startService(new Intent(this, WatchService.class).setAction(WatchService.ACT_REFRESH));
        });

        findViewById(R.id.btnRestore).setOnClickListener(v -> {
            new Thread(() -> {
                DensityEngine.Info i = new DensityEngine(this).reset();
                ui.post(() -> {
                    AppLog.i("UI", "手动恢复系统密度，回读 " + i);
                    refreshRunPage();
                });
            }).start();
        });

        findViewById(R.id.btnStopWatch).setOnClickListener(v ->
                startService(new Intent(this, WatchService.class).setAction(WatchService.ACT_STOP)));

        findViewById(R.id.btnA11ySet).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t) {
                toast("无法打开无障碍设置: " + t);
            }
        });

        findViewById(R.id.btnUsageSet).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Throwable t) {
                toast("无法打开使用情况设置: " + t);
            }
        });

        tvHelp.setText("原理：Android 没有「按包名」的密度接口，系统里唯一的密度入口是 wm density（按屏、不按应用）。"
                + "因此本工具的做法是——监听当前前台应用，进入你托管的应用时把屏幕密度切到该应用的值，离开时还原。\n\n"
                + "• 顶部「本应用显示密度」可单独调整本工具自己的界面大小，方便在小屏/高分屏上操作；未设置时仍跟随系统。\n"
                + "• 监控停止时只还原「本应用改过」的密度，你手动在系统里设的密度原样保留。\n"
                + "• 识别方式分两种：无障碍（极速，切到托管应用即时生效）与使用情况统计（零操作授权，约 0.5 秒延迟）。\n"
                + "• 切换密度会让目标应用重建界面，过程中可能有一次明显闪烁，属正常现象。\n"
                + "• 开启悬浮窗时，会优先通过 Shizuku 以 shell 身份自动授予 SYSTEM_ALERT_WINDOW，绝大多数设备（含国产 ROM）一步到位，不用去系统/厂商设置页翻找。");

        tvAdb.setText("adb shell am start-service -n com.perapp.dpi/.WatchService --es a com.perapp.dpi.START\n"
                + "adb shell am start-service -n com.perapp.dpi/.WatchService --es a com.perapp.dpi.STOP\n"
                + "adb shell am start-service -n com.perapp.dpi/.WatchService --es a com.perapp.dpi.TOGGLE");
    }

    private void rebuildModeChips() {
        llModeTabs.removeAllViews();
        String[] names = {"自动", "无障碍", "使用情况"};
        String[] vals = {DpiStore.MODE_AUTO, DpiStore.MODE_A11Y, DpiStore.MODE_USAGE};
        String cur = DpiStore.mode(this);
        int sel = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(cur)) sel = i;
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            addChip(llModeTabs, names[i], idx == sel, () -> {
                DpiStore.setMode(this, vals[idx]);
                rebuildModeChips();
                refreshRunPage();
            });
        }
    }

    private void toggleWatch(boolean on) {
        if (on) {
            if (!ShizukuShell.isReady()) {
                ensureShizuku();
                swWatch.setChecked(false);
                return;
            }
            startService(new Intent(this, WatchService.class).setAction(WatchService.ACT_START));
        } else {
            startService(new Intent(this, WatchService.class).setAction(WatchService.ACT_STOP));
        }
    }

    // ---------------- 悬浮窗 ----------------

    private void toggleFloat(boolean on) {
        if (on) {
            if (!ShizukuShell.isReady()) {
                ensureShizuku();
                swFloat.setChecked(false);
                return;
            }
            if (OverlayPerm.granted(this)) {
                startFloat();
                return;
            }
            swFloat.setChecked(false);
            // Shizuku 已拿到：直接以 shell 身份 appops set SYSTEM_ALERT_WINDOW allow，
            // 绝大多数设备（含国产 ROM）这一步就够，不用再让用户去系统/厂商页翻找
            new Thread(() -> {
                final boolean ok = OverlayPerm.grantViaShizuku(this);
                ui.post(() -> {
                    if (ok || OverlayPerm.granted(this)) {
                        AppLog.i("UI", "经 Shizuku 自动授予悬浮窗权限");
                        toast("已自动授予悬浮窗权限");
                        startFloat();
                    } else {
                        AppLog.i("UI", "Shizuku 自动授予失败，转人工引导");
                        awaitingOverlay = true;
                        openOverlaySettings();
                    }
                });
            }).start();
        } else {
            startService(new Intent(this, FloatService.class).setAction(FloatService.ACT_STOP));
        }
    }

    private void openOverlaySettings() {
        // 优先深链到厂商自家的悬浮窗权限页；失败再回退到系统 ACTION_MANAGE_OVERLAY_PERMISSION
        boolean oem = OverlayPerm.openOemSettings(this);
        if (!oem) {
            try {
                startActivityForResult(OverlayPerm.settingsIntent(this), REQ_OVERLAY);
            } catch (Throwable t) {
                toast("无法打开悬浮窗权限设置: " + t);
            }
        }
    }

    private void startFloat() {
        startService(new Intent(this, FloatService.class).setAction(FloatService.ACT_START));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            if (OverlayPerm.granted(this)) {
                awaitingOverlay = false;
                startFloat();
            } else {
                awaitingOverlay = false;
                swFloat.setChecked(false);
                toast("仍未获得「显示在其他应用上层」权限，悬浮窗无法开启");
                showOverlayGuide(null);
            }
        }
    }

    /** 悬浮窗权限未授予时的引导对话框，给出具体路径并再次拉起设置 */
    private void showOverlayGuide(String detail) {
        String mf = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER.toLowerCase();
        StringBuilder sb = new StringBuilder();
        sb.append("请到「设置 → 应用 → 本应用 → 显示在其他应用上层（悬浮窗）」中允许权限，然后返回本应用再打开悬浮窗。\n\n");
        sb.append("部分国产系统（小米 / 华为 / OPPO / vivo / 魅族等）需要到系统「权限管理」单独开启「悬浮窗」，仅系统设置页可能不生效。\n\n");
        if (mf.contains("xiaomi") || mf.contains("redmi")) {
            sb.append("小米/Redmi：设置 → 应用设置 → 权限管理 → 悬浮窗 → 允许。\n\n");
        } else if (mf.contains("huawei") || mf.contains("honor")) {
            sb.append("华为：设置 → 应用 → 应用管理 → 本应用 → 权限 → 悬浮窗 → 允许。\n\n");
        } else if (mf.contains("oppo") || mf.contains("realme") || mf.contains("oneplus")) {
            sb.append("OPPO/realme/一加：设置 → 权限管理 → 悬浮窗 → 允许。\n\n");
        } else if (mf.contains("vivo")) {
            sb.append("vivo/iQOO：设置 → 应用与权限 → 权限管理 → 悬浮窗 → 允许。\n\n");
        }
        if (detail != null && !detail.isEmpty()) sb.append("技术信息：").append(detail);
        new AlertDialog.Builder(this)
                .setTitle("开启悬浮窗权限")
                .setMessage(sb.toString())
                .setPositiveButton("去设置", (d, w) -> openOverlaySettings())
                .setNegativeButton("知道了", null)
                .show();
    }

    private void refreshRunPage() {
        swWatch.setChecked(WatchService.running);
        tvWatchState.setText(WatchService.running
                ? (WatchService.paused ? "已暂停（密度保留）" : "运行中 · 正在自动切换密度")
                : "未运行");
        swFloat.setChecked(FloatService.running);
        tvFloatState.setText(FloatService.running
                ? "已开启 · 悬浮于其它应用之上"
                : (OverlayPerm.granted(this) ? "未开启" : "未开启（需悬浮窗权限）"));
        rebuildModeChips();
        refreshEnv();
    }

    private void refreshEnv() {
        boolean ready = ShizukuShell.isReady();
        if (ready) {
            int uid = ShizukuShell.uid();
            tvEnvShizuku.setText(String.format(Locale.CHINA, "已授权 · uid=%d · v%d",
                    uid, ShizukuShell.version()));
            tvEnvShizuku.setTextColor(getColor(R.color.ok));
        } else if (ShizukuShell.binderAlive()) {
            tvEnvShizuku.setText("已连接，待授权（点「启用」会弹窗）");
            tvEnvShizuku.setTextColor(getColor(R.color.warn));
        } else {
            tvEnvShizuku.setText("未连接：请先在 Shizuku 管理器启动授权");
            tvEnvShizuku.setTextColor(getColor(R.color.err));
        }

        DensityEngine.Info info = new DensityEngine(this).read();
        tvEnvPhysical.setText(String.valueOf(info.physical));
        tvEnvOverride.setText(info.override > 0
                ? (info.override + "（覆盖）") : "无（跟随物理密度 " + info.physical + "）");
        tvEnvOverride.setTextColor(getColor(info.override > 0 ? R.color.primary : R.color.text));

        updateForeField();

        boolean a11y = A11yWatch.isEnabled(this);
        tvEnvA11y.setText(a11y ? "已开启（极速）" : "未开启");
        tvEnvA11y.setTextColor(getColor(a11y ? R.color.ok : R.color.warn));

        boolean usage = UsagePoll.allowed(this);
        tvEnvUsage.setText(usage ? "已授权" : "未授权（可经 Shizuku 自动授予）");
        tvEnvUsage.setTextColor(getColor(usage ? R.color.ok : R.color.warn));
    }

    private void updateForeField() {
        String fg = WatchService.lastFore;
        if (fg == null || fg.isEmpty()) {
            tvEnvFore.setText("—");
        } else {
            int want = DpiStore.get(this, fg);
            tvEnvFore.setText(fg + (want > 0 ? " · 目标 " + want + "dpi" : " · 跟随系统")
                    + "  |  " + WatchService.lastDesc);
        }
    }

    private void ensureShizuku() {
        if (ShizukuShell.isReady()) return;
        if (Shizuku.pingBinder()) {
            try {
                Shizuku.requestPermission(REQ_CODE);
            } catch (Throwable t) {
                toast("请求 Shizuku 授权失败: " + t);
            }
        } else {
            toast("Shizuku 未连接：请先在 Shizuku 管理器里启动授权服务");
        }
    }

    // ---------------- 日志页 ----------------

    private void setupLog() {
        tvLog = findViewById(R.id.tvLog);
        findViewById(R.id.btnCopyLog).setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("perdpi", logBuf.toString()));
                toast("日志已复制");
            } catch (Throwable t) {
                toast("复制失败: " + t);
            }
        });
        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            logBuf.setLength(0);
            tvLog.setText("");
            toast("已清空视图");
        });
    }

    private void appendLog(String line) {
        logBuf.append(line).append("\n");
        if (tvLog != null && findViewById(R.id.pageLog).getVisibility() == View.VISIBLE) {
            tvLog.append(line + "\n");
        }
    }

    private void refreshLogView() {
        tvLog.setText(logBuf.toString());
    }

    // ---------------- 本应用密度 ----------------

    private String selfPkg() {
        return getPackageName(); // com.perapp.dpi
    }

    private void setupSelfDpi() {
        tvSelfDpi = findViewById(R.id.tvSelfDpi);
        btnSelfMinus = findViewById(R.id.btnSelfMinus);
        btnSelfPlus = findViewById(R.id.btnSelfPlus);
        btnSelfReset = findViewById(R.id.btnSelfReset);
        btnSelfMinus.setOnClickListener(v -> changeSelfDpi(-SELF_STEP));
        btnSelfPlus.setOnClickListener(v -> changeSelfDpi(+SELF_STEP));
        btnSelfReset.setOnClickListener(v -> resetSelfDpi());
        refreshSelfDpi();
    }

    private void refreshSelfDpi() {
        int cur = DpiStore.get(this, selfPkg());
        DensityEngine.Info info = new DensityEngine(this).read();
        if (cur > 0) {
            tvSelfDpi.setText(cur + " dpi");
            tvSelfDpi.setTextColor(getColor(R.color.primary));
        } else {
            tvSelfDpi.setText("跟随系统（" + info.effective() + "）");
            tvSelfDpi.setTextColor(getColor(R.color.text_dim));
        }
    }

    private void changeSelfDpi(int delta) {
        if (!ShizukuShell.isReady()) {
            ensureShizuku();
            return;
        }
        int cur = DpiStore.get(this, selfPkg());
        DensityEngine.Info info = new DensityEngine(this).read();
        // 从未配置时起调基于系统当前有效密度，避免一次跨太大
        int base = cur > 0 ? cur : info.effective();
        int next = DensityEngine.clamp(base + delta);
        DpiStore.set(this, selfPkg(), next);
        AppLog.i("UI", "本应用密度 -> " + next + "dpi");
        final int apply = next;
        new Thread(() -> {
            new DensityEngine(this).apply(apply);
            ui.post(this::refreshSelfDpi);
        }).start();
        bumpService();
    }

    private void resetSelfDpi() {
        if (!ShizukuShell.isReady()) {
            ensureShizuku();
            return;
        }
        DpiStore.remove(this, selfPkg());
        AppLog.i("UI", "本应用密度 -> 跟随系统");
        new Thread(() -> {
            new DensityEngine(this).reset();
            ui.post(this::refreshSelfDpi);
        }).start();
        bumpService();
    }

    // ---------------- 通用 ----------------

    private void refreshAll() {
        refreshTopStatus();
        refreshSelfDpi();
        if (findViewById(R.id.pageRun).getVisibility() == View.VISIBLE) refreshRunPage();
    }

    private void refreshTopStatus() {
        boolean ready = ShizukuShell.isReady();
        String s = ready ? "Shizuku 已授权" : "Shizuku 未授权";
        if (WatchService.running) s += " · 监控中";
        else s += " · 监控关";
        tvTopStatus.setText(s);
        tvTopStatus.setTextColor(getColor(ready ? R.color.ok : R.color.warn));
    }

    private View gap() {
        View g = new View(this);
        g.setLayoutParams(new LinearLayout.LayoutParams(dp(6), 1));
        return g;
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
