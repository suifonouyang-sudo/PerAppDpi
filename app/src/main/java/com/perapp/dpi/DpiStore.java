package com.perapp.dpi;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每个应用的密度配置（按包名存 DPI）。存在 SharedPreferences 里，跨重启保留。
 *
 * <p>约定：某包名有 <code>dpi_&lt;pkg&gt;</code> 键 = 该应用被托管；没有键 = 跟随系统密度。
 */
public final class DpiStore {

    public static final String PREFS = "perappdpi";

    private static final String K_PREFIX = "dpi_";
    private static final String K_WATCHING = "watching";
    private static final String K_MODE = "mode";
    private static final String K_OWNED = "density_owned";

    public static final String MODE_AUTO = "auto";
    public static final String MODE_A11Y = "a11y";
    public static final String MODE_USAGE = "usage";

    private DpiStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int get(Context c, String pkg) {
        if (pkg == null) return 0;
        try {
            return sp(c).getInt(K_PREFIX + pkg, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static boolean isManaged(Context c, String pkg) {
        return get(c, pkg) > 0;
    }

    public static void set(Context c, String pkg, int dpi) {
        if (pkg == null) return;
        try {
            sp(c).edit().putInt(K_PREFIX + pkg, DensityEngine.clamp(dpi)).apply();
        } catch (Throwable ignored) {
        }
    }

    public static void remove(Context c, String pkg) {
        if (pkg == null) return;
        try {
            sp(c).edit().remove(K_PREFIX + pkg).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 已托管配置，按包名排序（仅用于展示与统计） */
    public static Map<String, Integer> all(Context c) {
        Map<String, Integer> out = new LinkedHashMap<>();
        try {
            Map<String, ?> m = sp(c).getAll();
            for (Map.Entry<String, ?> e : m.entrySet()) {
                if (!e.getKey().startsWith(K_PREFIX)) continue;
                Object v = e.getValue();
                if (v instanceof Integer) {
                    out.put(e.getKey().substring(K_PREFIX.length()), (Integer) v);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static int count(Context c) {
        return all(c).size();
    }

    public static void clearAll(Context c) {
        try {
            SharedPreferences.Editor ed = sp(c).edit();
            for (String k : sp(c).getAll().keySet()) {
                if (k.startsWith(K_PREFIX)) ed.remove(k);
            }
            ed.apply();
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 运行状态 ----------------

    public static boolean watching(Context c) {
        try {
            return sp(c).getBoolean(K_WATCHING, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setWatching(Context c, boolean v) {
        try {
            sp(c).edit().putBoolean(K_WATCHING, v).apply();
        } catch (Throwable ignored) {
        }
    }

    public static String mode(Context c) {
        try {
            return sp(c).getString(K_MODE, MODE_AUTO);
        } catch (Throwable t) {
            return MODE_AUTO;
        }
    }

    public static void setMode(Context c, String m) {
        try {
            sp(c).edit().putString(K_MODE, m).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 当前显示密度是不是本应用改的。
     *
     * <p>用来区分「我们留下的覆盖」和「用户自己在设置/其它工具里设的覆盖」——
     * 只有前者才会在监控停止时被还原，绝不擅自动后者。
     */
    public static boolean densityOwned(Context c) {
        try {
            return sp(c).getBoolean(K_OWNED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setDensityOwned(Context c, boolean v) {
        try {
            sp(c).edit().putBoolean(K_OWNED, v).apply();
        } catch (Throwable ignored) {
        }
    }
}
