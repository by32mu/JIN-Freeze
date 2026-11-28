package com.jin.freeze.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 白名单总开关：关掉后所有冻结通道都不再剔除白名单条目，
 * 白名单数据本身保留，重新打开立即恢复生效。
 */
public final class Whitelist {

    private static final String PREFS = "freeze_list";
    private static final String KEY_ENABLED = "whitelist_enabled";

    private Whitelist() {
    }

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
