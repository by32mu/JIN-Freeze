package com.jin.freeze.ui;

import android.app.Activity;
import android.content.Intent;
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
 * 桌面快捷方式的创建：26+ 走系统的固定快捷方式弹窗，24/25 用旧广播 best-effort。
 * 页面目标和 Shell 指令目标共用这一套。
 */
public final class ShortcutPin {

    /** 快捷方式 Intent 必须带 action，否则 ShortcutInfo 构造会抛 NPE。 */
    private static final String ACTION_QUICK_LAUNCH = "com.jin.freeze.action.QUICK_LAUNCH";

    private ShortcutPin() {
    }

    public static void pin(Activity a, String id, String name, Drawable icon, LaunchTarget.Target target) {
        LaunchTarget.saveShortcut(a, target);
        String key = LaunchTarget.newLaunchKey(a, target);
        Intent intent = new Intent(a, QuickLaunchActivity.class)
                .setAction(ACTION_QUICK_LAUNCH)
                .putExtra(QuickLaunchActivity.EXTRA_KEY, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        Bitmap bmp = LaunchTarget.bitmap(icon, Ui.dp(a, 48));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(a)) {
                Toast.makeText(a, "当前桌面不支持固定快捷方式，可改用磁贴或在桌面手动添加",
                        Toast.LENGTH_LONG).show();
                return;
            }
            IconCompat iconCompat = bmp != null
                    ? IconCompat.createWithBitmap(bmp)
                    : IconCompat.createWithResource(a, R.drawable.ic_launcher);
            ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(a, id)
                    .setShortLabel(name)
                    .setLongLabel(name)
                    .setIcon(iconCompat)
                    .setIntent(intent)
                    .build();
            ShortcutManagerCompat.requestPinShortcut(a, info, null);
            Toast.makeText(a, "已请求添加到桌面，请在系统弹窗里确认", Toast.LENGTH_LONG).show();
        } else {
            Intent legacy = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            legacy.putExtra(Intent.EXTRA_SHORTCUT_INTENT, intent);
            legacy.putExtra(Intent.EXTRA_SHORTCUT_NAME, name);
            if (bmp != null) {
                legacy.putExtra(Intent.EXTRA_SHORTCUT_ICON, bmp);
            } else {
                legacy.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                        Intent.ShortcutIconResource.fromContext(a, R.drawable.ic_launcher));
            }
            a.sendBroadcast(legacy);
            Toast.makeText(a, "已发送创建请求；桌面若不支持，请升级系统或手动添加",
                    Toast.LENGTH_LONG).show();
        }
    }
}
