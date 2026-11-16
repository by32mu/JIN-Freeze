package com.jin.freeze.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.widget.Toast;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.jin.freeze.R;
import com.jin.freeze.core.LaunchTarget;

/**
 * 桌面快捷方式：照搬 Hail 的 HShortcuts.addPinShortcut。
 * Intent 只带自定义 action + 包名，不解析目标应用的启动页；
 * 点开时由 QuickLaunchActivity 解冻后再用 getLaunchIntentForPackage 拉起，冻结/未冻结都适用。
 * 图标取应用原始图标（Hail 的 app_icon_size = 64dp），名称取应用名。
 */
public final class ShortcutPin {

    /** 快捷方式 Intent 的 action，必须与 QuickLaunchActivity 的 intent-filter 一致。 */
    public static final String ACTION_LAUNCH = "com.jin.freeze.action.LAUNCH";
    /** 快捷方式里携带的包名 extra。 */
    public static final String KEY_PACKAGE = "package";
    /** 与 Hail R.dimen.app_icon_size 一致。 */
    private static final int ICON_SIZE_DP = 64;

    private ShortcutPin() {
    }

    public static void pin(Activity a, String pkg, CharSequence name, Drawable icon) {
        Intent intent = new Intent(ACTION_LAUNCH).putExtra(KEY_PACKAGE, pkg);
        Bitmap bmp = appIcon(a, pkg, icon);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(a)) {
                Toast.makeText(a, "添加到桌面失败：当前桌面不支持固定快捷方式",
                        Toast.LENGTH_LONG).show();
                return;
            }
            IconCompat iconCompat = bmp != null
                    ? IconCompat.createWithBitmap(bmp)
                    : IconCompat.createWithResource(a, R.drawable.ic_launcher);
            CharSequence label = name == null || name.length() == 0 ? appLabel(a, pkg) : name;
            ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(a, pkg)
                    .setIcon(iconCompat)
                    .setShortLabel(label)
                    .setIntent(intent)
                    .build();
            ShortcutManagerCompat.requestPinShortcut(a, info, null);
        } else {
            Intent legacy = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            legacy.putExtra(Intent.EXTRA_SHORTCUT_INTENT, intent);
            legacy.putExtra(Intent.EXTRA_SHORTCUT_NAME,
                    name == null || name.length() == 0 ? appLabel(a, pkg) : name);
            if (bmp != null) {
                legacy.putExtra(Intent.EXTRA_SHORTCUT_ICON, bmp);
            } else {
                legacy.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                        Intent.ShortcutIconResource.fromContext(a, R.drawable.ic_launcher));
            }
            a.sendBroadcast(legacy);
        }
    }

    /** 应用原始图标，按 Hail 的 64dp 画成位图；冻结包查不到时退回列表里已加载的图标。 */
    private static Bitmap appIcon(Activity a, String pkg, Drawable fallback) {
        int size = Ui.dp(a, ICON_SIZE_DP);
        Drawable d = null;
        try {
            ApplicationInfo ai = a.getPackageManager()
                    .getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            d = ai.loadIcon(a.getPackageManager());
        } catch (Exception ignored) {
        }
        if (d == null) {
            d = fallback;
        }
        if (d == null) {
            try {
                d = a.getPackageManager().getDefaultActivityIcon();
            } catch (Exception ignored) {
            }
        }
        return LaunchTarget.bitmap(d, size);
    }

    private static CharSequence appLabel(Activity a, String pkg) {
        try {
            ApplicationInfo ai = a.getPackageManager()
                    .getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            CharSequence l = ai.loadLabel(a.getPackageManager());
            if (l != null && l.length() > 0) {
                return l;
            }
        } catch (Exception ignored) {
        }
        return pkg;
    }
}
