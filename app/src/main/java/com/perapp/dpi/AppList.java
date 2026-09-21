package com.perapp.dpi;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 有启动图标的已安装应用清单（含用户 / 系统分流）。
 *
 * <p>Android 11+ 的包可见性：不在 Manifest 的 &lt;queries&gt; 里声明 MAIN/LAUNCHER intent，
 * queryIntentActivities 只会返回自己，列表会是空的。
 *
 * <p>图标不在这里加载 —— 400+ 个应用逐个 loadIcon 会明显卡顿。交给 Adapter 按需取 + LruCache。
 */
public final class AppList {

    public static final class Item {
        public final String pkg;
        public final String label;
        public final String act;
        public final boolean system;
        /** 该应用配置的密度；0 = 跟随系统 */
        public int dpi;

        Item(String pkg, String label, String act, boolean system) {
            this.pkg = pkg;
            this.label = label;
            this.act = act;
            this.system = system;
        }
    }

    private AppList() {
    }

    public static List<Item> load(Context ctx) {
        Map<String, Item> map = new LinkedHashMap<>();
        PackageManager pm = ctx.getPackageManager();
        try {
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(ctx.getPackageName())) continue;
                if (map.containsKey(pkg)) continue;

                String label;
                try {
                    label = ri.loadLabel(pm).toString();
                } catch (Throwable t) {
                    label = pkg;
                }
                boolean system = false;
                try {
                    ApplicationInfo ai = ri.activityInfo.applicationInfo;
                    if (ai != null) {
                        system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                    }
                } catch (Throwable ignored) {
                }
                map.put(pkg, new Item(pkg, label, ri.activityInfo.name, system));
            }
        } catch (Throwable t) {
            AppLog.i("APPS", "load err: " + t);
        }

        List<Item> out = new ArrayList<>(map.values());
        // 中文必须用 Collator，String.compareTo 会把中文排得毫无规律
        Collator collator = Collator.getInstance(Locale.CHINA);
        out.sort((a, b) -> collator.compare(a.label, b.label));
        refreshDpi(ctx, out);
        AppLog.i("APPS", "载入 " + out.size() + " 个可启动应用，其中已设置密度 "
                + DpiStore.count(ctx) + " 个");
        return out;
    }

    /** 从配置里回填每个应用的密度 */
    public static void refreshDpi(Context ctx, List<Item> list) {
        if (list == null) return;
        for (Item it : list) {
            it.dpi = DpiStore.get(ctx, it.pkg);
        }
    }

    /** 用包名反查名称（用于手动填包名、日志可读性） */
    public static String labelOf(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getApplicationLabel(
                    ctx.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }
}
