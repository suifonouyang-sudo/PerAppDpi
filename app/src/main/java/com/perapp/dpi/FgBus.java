package com.perapp.dpi;

/** 前台应用事件总线：把无障碍服务 / 使用情况轮询的识别结果送到 WatchService */
public final class FgBus {

    public interface Sink {
        void onForeground(String pkg);
    }

    private static volatile Sink sink;

    private FgBus() {
    }

    public static void setSink(Sink s) {
        sink = s;
    }

    public static void dispatch(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Sink s = sink;
        if (s == null) return;
        try {
            s.onForeground(pkg);
        } catch (Throwable t) {
            AppLog.i("FG", "dispatch err: " + t);
        }
    }
}
