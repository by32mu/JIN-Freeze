package com.jin.freeze.core;

import android.content.Context;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 核心系统应用保护名单：这些包永远不允许被冻结。
 * 硬编码关键包名 + 特征匹配（systemui / launcher / inputmethod / settings / telephony …）双重判定，
 * 供选择页过滤、名单清理、执行层兜底三处共用。
 */
public final class CoreProtect {

    private CoreProtect() {
    }

    private static final Set<String> EXACT = new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.dialer",
            "com.android.mms",
            "com.android.incallui",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.launcher",
            "com.android.launcher3",
            "com.miui.home",
            "com.jin.freeze"));

    private static final String[] KEYWORDS = {
            // 输入法不在保护范围，允许冻结
            "systemui", "launcher",
            "settings", "telephony", "telecom", "dialer", "packageinstaller",
            "permissioncontroller"
    };

    private static final String[] PREFIXES = {
            "android.",
            "com.android.providers.",
            "com.android.server.",
            "com.android.internal."
    };

    private static final String[] LABEL_HINTS = {
            "系统界面", "系统ui", "桌面", "启动器", "设置", "电话", "短信",
            "安装器", "权限管理"
    };

    public static boolean isProtected(Context c, String pkg, String label) {
        if (pkg == null || pkg.isEmpty()) {
            return true;
        }
        if (pkg.equals(c.getPackageName())) {
            return true;
        }
        if (EXACT.contains(pkg)) {
            return true;
        }
        String p = pkg.toLowerCase(Locale.ROOT);
        for (String prefix : PREFIXES) {
            if (p.startsWith(prefix)) {
                return true;
            }
        }
        for (String key : KEYWORDS) {
            if (p.contains(key)) {
                return true;
            }
        }
        String l = label == null ? "" : label.toLowerCase(Locale.ROOT);
        for (String hint : LABEL_HINTS) {
            if (l.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
