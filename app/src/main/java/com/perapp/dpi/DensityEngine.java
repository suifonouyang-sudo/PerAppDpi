package com.perapp.dpi;

import android.content.Context;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 密度引擎：把「某个应用要多少 dpi」翻译成 <code>wm density</code> 调用。
 *
 * <p><b>为什么是全系统改密度：</b>Android 没有按包名的密度接口。系统里唯一的密度入口是
 * <code>wm density [reset|DENSITY] [-d DISPLAY_ID]</code>（按屏，不按应用），
 * 每个 ActivityRecord 上虽然有 <code>mOverrideConfig</code> 带 density 字段，
 * 但没有任何 shell/公开接口能设置它。所以「单应用 DPI」的实际做法就是：
 * 监听前台应用，进入托管应用时把屏幕密度切成该应用的值，离开时还原。
 *
 * <p><b>只还原自己改的：</b>用 {@link DpiStore#densityOwned} 标记区分「我们留下的覆盖」与
 * 「用户自己在设置里设的覆盖」。后者在停止监控时原样保留，绝不擅自清除。
 */
public final class DensityEngine {

    public static final int MIN = 72;
    public static final int MAX = 640;

    private static final Pattern P_PHYS = Pattern.compile("Physical density:\\s*(\\d+)");
    private static final Pattern P_OVER = Pattern.compile("Override density:\\s*(\\d+)");

    public static int clamp(int dpi) {
        if (dpi < MIN) return MIN;
        if (dpi > MAX) return MAX;
        return dpi;
    }

    /** 一次读取的结果 */
    public static final class Info {
        /** 物理密度；0 表示没解析到 */
        public int physical;
        /** 当前覆盖密度；0 表示没有覆盖（跟随物理密度） */
        public int override;
        public String raw = "";

        public int effective() {
            return override > 0 ? override : physical;
        }

        @Override
        public String toString() {
            return "physical=" + physical + " override=" + (override > 0 ? String.valueOf(override) : "无");
        }
    }

    /** 一次前台切换的处理结果 */
    public static final class Action {
        public String pkg = "";
        public int want;
        public int before;
        public int after;
        public boolean changed;
        public String desc = "";
    }

    private final Context app;

    public DensityEngine(Context c) {
        this.app = c.getApplicationContext();
    }

    public Context ctx() {
        return app;
    }

    // ---------------- 底层 ----------------

    public static Info parse(String text) {
        Info i = new Info();
        i.raw = text == null ? "" : text.trim();
        if (text != null) {
            Matcher m = P_PHYS.matcher(text);
            if (m.find()) {
                try {
                    i.physical = Integer.parseInt(m.group(1));
                } catch (Throwable ignored) {
                }
            }
            Matcher o = P_OVER.matcher(text);
            if (o.find()) {
                try {
                    i.override = Integer.parseInt(o.group(1));
                } catch (Throwable ignored) {
                }
            }
        }
        return i;
    }

    /** 读回当前密度（一次 binder 往返） */
    public Info read() {
        return parse(ShizukuShell.exec(app, "wm density").out);
    }

    /**
     * 套用密度并回读确认。回读是必须的 —— <code>wm density</code> 成功时也不打印任何内容，
     * 只看退出码会把失败当成功。
     */
    public Info apply(int dpi) {
        int d = clamp(dpi);
        Info info = parse(ShizukuShell.exec(app, "wm density " + d + "; echo '---'; wm density").out);
        if (info.override == d) {
            DpiStore.setDensityOwned(app, true);
            AppLog.i("DPI", "apply " + d + " -> OK（回读覆盖=" + info.override + "）");
        } else {
            AppLog.i("DPI", "apply " + d + " -> 失败，回读 " + info);
        }
        return info;
    }

    /** 清除密度覆盖，回到系统物理密度 */
    public Info reset() {
        Info info = parse(ShizukuShell.exec(app, "wm density reset; echo '---'; wm density").out);
        DpiStore.setDensityOwned(app, false);
        AppLog.i("DPI", "reset -> 回读 " + info);
        return info;
    }

    /**
     * 启动时清理上一次运行遗留的覆盖（只清自己留下的）。
     *
     * @return 有话说时返回描述，无需处理时返回 null
     */
    public String cleanupLeftover() {
        if (!DpiStore.densityOwned(app)) return null;
        Info i = read();
        if (i.override == 0) {
            DpiStore.setDensityOwned(app, false);
            AppLog.i("DPI", "遗留标记存在但已无覆盖，清掉标记");
            return null;
        }
        reset();
        String s = "清理上次遗留的密度覆盖 " + i.override + " → 已还原系统密度";
        AppLog.i("DPI", s);
        return s;
    }

    // ---------------- 编排 ----------------

    /**
     * 前台应用变为 pkg 时该做什么。无变化时也会返回结果（changed=false），
     * 便于 UI 与日志如实展示「看过了、不用动」。
     */
    public Action handle(String pkg) {
        Action a = new Action();
        a.pkg = pkg == null ? "" : pkg;

        String self = app.getPackageName();
        // 本应用自己永远跟随系统密度：否则在设置界面上操作时自己也在变形，体验很糟
        int want = self.equals(a.pkg) ? 0 : DpiStore.get(app, a.pkg);

        Info before = read();
        a.before = before.override;
        a.want = want;

        if (want == 0) {
            if (before.override == 0) {
                a.after = 0;
                a.desc = "跟随系统密度（" + before.effective() + "dpi），无需改动";
                return a;
            }
            Info after = reset();
            a.after = after.override;
            a.changed = true;
            a.desc = "离开托管应用 → 还原系统密度 " + before.override + " → " + after.effective();
            return a;
        }

        if (before.override == want) {
            a.after = want;
            a.desc = "已是目标密度 " + want + "dpi，无需改动";
            return a;
        }

        Info after = apply(want);
        a.after = after.override;
        a.changed = true;
        if (after.override == want) {
            a.desc = "套用 " + want + "dpi"
                    + (before.override > 0 ? "（原 " + before.override + "dpi）" : "（原跟随系统）");
        } else {
            a.desc = "套用 " + want + "dpi 失败，当前 " + after.effective() + "dpi";
        }
        return a;
    }
}
