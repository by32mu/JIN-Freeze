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
 * manifest 里已有静态声明（res/xml/shortcuts.xml）；这里再按动态方式兜底：
 * 系统不允许动态 id 与静态 id 重名，两者同时存在还会在启动器里出现重复项，
 * 所以检测到静态声明已生效时不重复发布，动态只在静态不可用时补位。
 */
public final class ShortcutSync {

    public static final String ID_OPEN_LIST = "open_freeze_list";
    public static final String ID_FREEZE_ALL = "freeze_all";
    public static final String ID_UNFREEZE_ALL = "unfreeze_all";
    /** 动态兜底用独立 id，避免与 manifest 静态 id 冲突。 */
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
            if (!sm.getManifestShortcuts().isEmpty()) {
                // 静态已注册：不再重复发一套同名快捷方式
                return;
            }
            List<ShortcutInfo> list = new ArrayList<>();
            list.add(build(ctx, DYN_PREFIX + ID_OPEN_LIST,
                    R.string.sc_open_list, R.string.sc_open_list_long,
                    R.drawable.ic_shortcut_list,
                    new Intent(ctx, QuickLaunchActivity.class)
                            .setAction(QuickLaunchActivity.ACTION_OPEN_FREEZE_LIST)));
            list.add(build(ctx, DYN_PREFIX + ID_FREEZE_ALL,
                    R.string.sc_freeze_all, R.string.sc_freeze_all_long,
                    R.drawable.ic_shortcut_freeze,
                    new Intent(ctx, QuickLaunchActivity.class)
                            .setAction(QuickLaunchActivity.ACTION_FREEZE_ALL)));
            list.add(build(ctx, DYN_PREFIX + ID_UNFREEZE_ALL,
                    R.string.sc_unfreeze_all, R.string.sc_unfreeze_all_long,
                    R.drawable.ic_shortcut_unfreeze,
                    new Intent(ctx, QuickLaunchActivity.class)
                            .setAction(QuickLaunchActivity.ACTION_UNFREEZE_ALL)));
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
