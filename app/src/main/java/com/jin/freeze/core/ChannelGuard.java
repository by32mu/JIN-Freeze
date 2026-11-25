package com.jin.freeze.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 冻结通道变化检测：root / Shizuku / Dhizuku 三种身份能撤销的冻结并不相同。
 *
 * 典型问题：用 root 冻结（停用或 suspender=root 的暂停）后失去 root，只剩 Dhizuku，
 * 这批应用就成了 Dhizuku 处理不了的「孤儿」——DPM 只能撤销自己发起的暂停，
 * 停用类冻结连 pm enable 都跑不了（root 兜底除外）。
 *
 * 所以每次进凚都对比「上次使用的通道」和「本次生效的通道」：
 * 一旦真的换了通道，就提示用户先在原通道把存量冻结应用解冻，再重新冻结。
 */
public final class ChannelGuard {

    public static final String NONE = "none";
    public static final String ROOT = "root";
    public static final String SHIZUKU = "shizuku";
    public static final String DHIZUKU = "dhizuku";

    private static final String PREFS = "freeze_list";
    private static final String KEY_LAST = "last_channel";

    private ChannelGuard() {
    }

    /** 当前实际生效的通道；必须在 IO 线程调用（PrivShell.mode 会探测 su）。 */
    public static String current(Context c) {
        PrivShell.Mode mode = PrivShell.mode();
        if (mode == PrivShell.Mode.ROOT) {
            return ROOT;
        }
        if (mode == PrivShell.Mode.SHIZUKU) {
            return SHIZUKU;
        }
        if (DhizukuGate.only(c)) {
            return DHIZUKU;
        }
        return NONE;
    }

    /** 上次记录的通道；没有记录返回空串。 */
    public static String last(Context c) {
        return prefs(c).getString(KEY_LAST, "");
    }

    /** 记下本次通道。 */
    public static void remember(Context c, String channel) {
        prefs(c).edit().putString(KEY_LAST, channel).apply();
    }

    /** 通道的中文名（提示框用）。 */
    public static String label(String channel) {
        if (ROOT.equals(channel)) {
            return "Root";
        }
        if (SHIZUKU.equals(channel)) {
            return "Shizuku";
        }
        if (DHIZUKU.equals(channel)) {
            return "Dhizuku";
        }
        return "未激活";
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
