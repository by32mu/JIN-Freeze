package com.jin.freeze.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;

import com.jin.freeze.R;
import com.jin.freeze.ui.QuickLaunchActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 「打开列表 / 一键冻结 / 一键解冻」三条快捷方式。
 * 现在只发布动态快捷方式：manifest 里的静态声明已清空，因为 MIUI 桌面按 iconRes 取图时
 * 会把三条画成同一个图标；动态快捷方式把渲染好的位图直接带在 ShortcutInfo 里，逐条独立。
 */
public final class ShortcutSync {

    public static final String ID_OPEN_LIST = "open_freeze_list";
    public static final String ID_FREEZE_ALL = "freeze_all";
    public static final String ID_UNFREEZE_ALL = "unfreeze_all";
    /** 动态快捷方式用独立 id，避免与历史版本残留的静态 id 混淆。 */
    private static final String DYN_PREFIX = "dyn_";

    private ShortcutSync() {
    }

    public static void publish(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return;
        }
        ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
        if (sm == null) {
            return;
        }
        try {
            List<ShortcutInfo> list = new ArrayList<>();
            list.add(build(ctx, DYN_PREFIX + ID_OPEN_LIST,
                    R.string.sc_open_list, R.string.sc_open_list_long,
                    R.drawable.ic_shortcut_list,
                    new Intent(ctx, QuickLaunchActivity.class)
                            .setAction(QuickLaunchActivity.ACTION_OPEN_FREEZE_LIST)));
            list.add(build(ctx, DYN_PREFIX + ID_FREEZE_ALL,
                    R.string.sc_freeze_all, R.string.sc_freeze_all_long,
                    R.drawable.ic_shortcut_freeze,
                    // NO_ANIMATION：批量执行本来就看不见界面，
                    // 打开过渡动画期间系统会吞掉桌面滑动，直接取消掉
                    new Intent(ctx, QuickLaunchActivity.class)
                            .setAction(QuickLaunchActivity.ACTION_FREEZE_ALL)
                            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)));
            list.add(build(ctx, DYN_PREFIX + ID_UNFREEZE_ALL,
                    R.string.sc_unfreeze_all, R.string.sc_unfreeze_all_long,
                    R.drawable.ic_shortcut_unfreeze,
                    new Intent(ctx, QuickLaunchActivity.class)
                            .setAction(QuickLaunchActivity.ACTION_UNFREEZE_ALL)
                            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)));
            sm.setDynamicShortcuts(list);
        } catch (Exception e) {
            android.util.Log.e("ShortcutSync", "publish dynamic shortcuts failed", e);
        }
    }

    private static ShortcutInfo build(Context ctx, String id, int shortLabel,
                                      int longLabel, int icon, Intent intent) {
        return new ShortcutInfo.Builder(ctx, id)
                .setShortLabel(ctx.getString(shortLabel))
                .setLongLabel(ctx.getString(longLabel))
                // 位图跨进程可用；资源 id 在某些启动器会被当成单色图标重绘
                .setIcon(Icon.createWithBitmap(renderIcon(ctx, icon)))
                .setIntent(intent)
                .build();
    }

    /** 把矢量图标按 144px 渲染成位图（ARGB_8888、透明底）。 */
    public static android.graphics.Bitmap renderIcon(Context ctx, int resId) {
        int size = 144;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        android.graphics.drawable.Drawable d =
                androidx.core.content.ContextCompat.getDrawable(ctx, resId);
        if (d == null) {
            return bmp;
        }
        d = d.mutate();
        d.setBounds(0, 0, size, size);
        d.draw(canvas);
        return bmp;
    }
}
