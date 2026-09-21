package com.perapp.dpi;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 悬浮窗（SYSTEM_ALERT_WINDOW）权限的可靠获取工具。
 *
 * <p><b>为什么要这个类：</b>Android 上判断悬浮窗权限主要靠 {@link Settings#canDrawOverlays}，
 * 但大量国产 ROM（小米 MIUI/HyperOS、华为、OPPO/realme/一加、vivo/iQOO、魅族等）会：
 * <ul>
 *   <li>明明已经在系统里授予，{@code canDrawOverlays} 却返回 {@code false}（谎报）；</li>
 *   <li>跳到标准 {@code ACTION_MANAGE_OVERLAY_PERMISSION} 页面后，那个页面在这类 ROM 上
 *       根本不开真正的「悬浮窗」开关，必须去厂商自家的「权限管理」里开；</li>
 *   <li>{@code onActivityResult} 在这些 ROM 上经常拿不到回调。</li>
 * </ul>
 * 于是光靠 {@code canDrawOverlays} 前置判断，结果就是「部分设备死活打不开悬浮窗」。
 *
 * <p><b>本类的策略：</b>
 * <ol>
 *   <li>{@link #granted} 以 {@code canDrawOverlays} 为主，若它谎报 {@code false}，再用
 *       「能否真的加一个 1px 透明视图」兜底探测——能加成功就代表权限其实已给；</li>
 *   <li>{@link #openOemSettings} 尽量深链到厂商自家的悬浮窗权限页；</li>
 *   <li>{@link #settingsIntent} 作为通用回退。</li>
 * </ol>
 */
public final class OverlayPerm {

    private OverlayPerm() {
    }

    /** 是否已获得悬浮窗权限（含 OEM 谎报兜底探测）。只应在前台（Activity/Service）调用。 */
    public static boolean granted(Context c) {
        if (c == null) return false;
        try {
            if (Settings.canDrawOverlays(c)) return true;
        } catch (Throwable ignored) {
        }
        // 兜底：真的尝试加一个 1px 透明视图，成功即代表权限其实已给（常见于国产 ROM 谎报）
        return probe(c);
    }

    /** 用 1px 透明视图探测是否真能叠加。成功返回 true；任何异常都视为无权限。 */
    public static boolean probe(Context c) {
        if (c == null) return false;
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return false;
        View v = new View(c);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                1, 1,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSPARENT);
        lp.gravity = Gravity.START | Gravity.TOP;
        lp.x = 0;
        lp.y = 0;
        try {
            wm.addView(v, lp);
            wm.removeViewImmediate(v);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 通用系统设置页（包级），拿不到厂商页时回退用。 */
    public static Intent settingsIntent(Context c) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + c.getPackageName()));
    }

    /**
     * 尽量深链到厂商自家的「悬浮窗」权限页。返回 true 表示已成功发起一个厂商页。
     * 失败（该类 ROM 不存在/页面不存在）返回 false，调用方应回退到 {@link #settingsIntent}。
     */
    public static boolean openOemSettings(Context c) {
        String mf = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase();

        Intent i = null;
        if (mf.contains("xiaomi") || brand.contains("xiaomi") || mf.contains("redmi") || brand.contains("redmi")) {
            // 小米/Redmi：权限管理 -> 悬浮窗
            i = new Intent("miui.intent.action.APP_PERM_EDITOR");
            i.putExtra("extra_pkgname", c.getPackageName());
            i.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity");
            if (!safeStart(c, i)) {
                i = new Intent();
                i.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity");
                i.putExtra("extra_pkgname", c.getPackageName());
            }
        } else if (mf.contains("huawei") || brand.contains("huawei") || brand.contains("honor")) {
            i = new Intent();
            i.setClassName("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.MainActivity");
        } else if (mf.contains("oppo") || brand.contains("oppo")
                || brand.contains("realme") || brand.contains("oneplus")) {
            i = new Intent();
            i.setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.PermissionManagerActivity");
            if (!safeStart(c, i)) {
                i = new Intent();
                i.setClassName("com.oppo.safe", "com.oppo.safe.permission.PermissionAppListActivity");
            }
        } else if (mf.contains("vivo") || brand.contains("vivo")) {
            i = new Intent();
            i.setClassName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.FloatWindowManager");
            if (!safeStart(c, i)) {
                i = new Intent();
                i.setClassName("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity");
            }
        } else if (mf.contains("meizu") || brand.contains("meizu")) {
            i = new Intent("com.meizu.safe.security.SHOW_APPSEC");
            i.addCategory(Intent.CATEGORY_DEFAULT);
            i.putExtra("packageName", c.getPackageName());
        }

        return i != null && safeStart(c, i);
    }

    private static boolean safeStart(Context c, Intent i) {
        try {
            if (i.resolveActivity(c.getPackageManager()) != null) {
                c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
