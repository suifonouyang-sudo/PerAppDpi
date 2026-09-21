package com.perapp.dpi;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

/**
 * 极速模式：用无障碍的窗口变化事件代替轮询，切到托管应用时几乎即时套用密度。
 *
 * <p>本服务在配置里声明了 canRetrieveWindowContent=false —— 只读「哪个包名变成了前台」，
 * 不读取任何界面内容。
 */
public class A11yWatch extends AccessibilityService {

    public static volatile boolean connected;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        AppLog.i("A11Y", "无障碍服务已连接（极速模式可用）");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }
        CharSequence p = event.getPackageName();
        if (p == null) return;
        // 监控没开的时候什么都不做，避免无障碍服务常驻时白白触发
        if (!WatchService.running) return;
        FgBus.dispatch(p.toString());
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        connected = false;
        AppLog.i("A11Y", "无障碍服务已断开");
        return super.onUnbind(intent);
    }

    /** 本服务是否已在系统里被用户开启 */
    public static boolean isEnabled(Context c) {
        try {
            if (connected) return true;
            String flat = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(flat)) return false;
            ComponentName me = new ComponentName(c, A11yWatch.class);
            String target = me.flattenToString();
            String targetShort = me.flattenToShortString();
            for (String s : flat.split(":")) {
                if (s.equalsIgnoreCase(target) || s.equalsIgnoreCase(targetShort)) return true;
                // 有的 ROM 会把包名简写，做一次宽松匹配
                if (s.toLowerCase().contains(c.getPackageName().toLowerCase())
                        && s.toLowerCase().contains("a11ywatch")) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}
