package com.jin.freeze.ui;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.jin.freeze.R;
import com.jin.freeze.core.LaunchTarget;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 桌面快捷方式：照搬 Hail 的 HShortcuts.addPinShortcut。
 * Intent 只带自定义 action + 包名，不解析目标应用的启动页；
 * 点开时由 QuickLaunchActivity 解冻后再用 getLaunchIntentForPackage 拉起，冻结/未冻结都适用。
 * 图标取应用原始图标（Hail 的 app_icon_size = 64dp），名称取应用名。
 *
 * 批量添加不能在一个循环里连续 requestPinShortcut：系统会为每个请求拉起一次桌面的
 * 确认页，后一个请求会把前一个顶掉，最后只有零星几个图标能落到桌面（表现为
 * 「只有一部分应用能加，冻结的那几个都没了」）。这里改成串行队列：等桌面回报
 * 上一个请求的结果（或超时）再发下一个。
 */
public final class ShortcutPin {

    /** 快捷方式 Intent 的 action，必须与 QuickLaunchActivity 的 intent-filter 一致。 */
    public static final String ACTION_LAUNCH = "com.jin.freeze.action.LAUNCH";
    /** 快捷方式里携带的包名 extra。 */
    public static final String KEY_PACKAGE = "package";
    /** 与 Hail R.dimen.app_icon_size 一致。 */
    private static final int ICON_SIZE_DP = 64;
    /** 单个图标的确认超时：桌面不回结果时也要继续下一个，避免整条队列卡死。 */
    private static final long PIN_TIMEOUT_MS = 15000L;

    private static final ArrayDeque<String> QUEUE = new ArrayDeque<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context host;
    private static Runnable allDone;
    /** 队列代数：每次前进自增，用来忽略上一个图标的超时回调。 */
    private static int generation;
    /** 当前等待结果的包名：迟到的旧回调不能把队列提前推进。 */
    private static String currentToken;

    private ShortcutPin() {
    }

    /** 单个快捷方式（长按菜单 / 应用页）：直接请求，桌面弹一次确认框。 */
    public static void pin(android.app.Activity a, String pkg, CharSequence name, Drawable icon) {
        request(a, pkg, name, icon, null);
    }

    /**
     * 批量添加：串行请求，等桌面回报结果再发下一个。
     * 已固定、未安装的自动跳过；队列跑完回调 done（主线程）。
     */
    public static void pinAll(Context c, List<String> pkgs, Runnable done) {
        if (c == null || pkgs == null || pkgs.isEmpty()) {
            if (done != null) {
                done.run();
            }
            return;
        }
        QUEUE.clear();
        Set<String> pinned = pinnedPackages(c);
        PackageManager pm = c.getPackageManager();
        for (String pkg : pkgs) {
            if (pkg == null || pkg.isEmpty() || pinned.contains(pkg)) {
                continue;
            }
            try {
                pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            } catch (Exception e) {
                continue;
            }
            if (!QUEUE.contains(pkg)) {
                QUEUE.add(pkg);
            }
        }
        // 用 Application Context 当宿主：恢复全部时页面会 recreate，队列不能跟着中断
        host = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        allDone = done;
        advance();
    }

    /** 桌面回报「已固定 / 被拒绝」：继续下一个。由 PinResultReceiver 调用。 */
    static void onPinResult(String token) {
        MAIN.post(() -> {
            // 只接受当前这一条的回报；超时后迟到的旧回调直接丢弃
            if (token != null && token.equals(currentToken)) {
                advance();
            }
        });
    }

    private static void advance() {
        final int gen = ++generation;
        Context c = host;
        if (c == null || QUEUE.isEmpty()) {
            currentToken = null;
            Runnable cb = allDone;
            host = null;
            allDone = null;
            if (cb != null) {
                cb.run();
            }
            return;
        }
        String pkg = QUEUE.poll();
        currentToken = pkg;
        request(c, pkg, null, null, pkg);
        // 桌面没回结果（部分 ROM 不回调）时按超时继续
        MAIN.postDelayed(() -> {
            if (gen == generation) {
                advance();
            }
        }, PIN_TIMEOUT_MS);
    }

    /**
     * 发起一次固定请求。
     * @param token 非空表示批量队列里的一个：带上回调，桌面确认/拒绝后继续下一个
     */
    private static void request(Context ctx, String pkg, CharSequence name, Drawable icon,
                                String token) {
        Intent intent = new Intent(ACTION_LAUNCH).putExtra(KEY_PACKAGE, pkg);
        Bitmap bmp = appIcon(ctx, pkg, icon);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
                Toast.makeText(ctx, "添加到桌面失败：当前桌面不支持固定快捷方式",
                        Toast.LENGTH_LONG).show();
                return;
            }
            IconCompat iconCompat = bmp != null
                    ? IconCompat.createWithBitmap(bmp)
                    : IconCompat.createWithResource(ctx, R.drawable.ic_launcher);
            CharSequence label = name == null || name.length() == 0 ? appLabel(ctx, pkg) : name;
            ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(ctx, pkg)
                    .setIcon(iconCompat)
                    .setShortLabel(label)
                    .setIntent(intent)
                    .build();
            android.content.IntentSender sender = null;
            if (token != null) {
                Intent cb = new Intent(ctx, PinResultReceiver.class)
                        .putExtra(PinResultReceiver.EXTRA_TOKEN, token);
                PendingIntent pi = PendingIntent.getBroadcast(ctx, token.hashCode(), cb,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                sender = pi.getIntentSender();
            }
            ShortcutManagerCompat.requestPinShortcut(ctx, info, sender);
        } else {
            Intent legacy = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            legacy.putExtra(Intent.EXTRA_SHORTCUT_INTENT, intent);
            legacy.putExtra(Intent.EXTRA_SHORTCUT_NAME,
                    name == null || name.length() == 0 ? appLabel(ctx, pkg) : name);
            if (bmp != null) {
                legacy.putExtra(Intent.EXTRA_SHORTCUT_ICON, bmp);
            } else {
                legacy.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                        Intent.ShortcutIconResource.fromContext(ctx, R.drawable.ic_launcher));
            }
            ctx.sendBroadcast(legacy);
        }
    }

    /** 当前已被桌面固定的凚快捷方式（包名）。API 25 以下返回空集。 */
    public static Set<String> pinnedPackages(Context c) {
        Set<String> out = new HashSet<>();
        if (c == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return out;
        }
        try {
            android.content.pm.ShortcutManager sm =
                    c.getSystemService(android.content.pm.ShortcutManager.class);
            if (sm == null) {
                return out;
            }
            for (android.content.pm.ShortcutInfo si : sm.getPinnedShortcuts()) {
                if (si == null) {
                    continue;
                }
                String pkg = null;
                Intent it = si.getIntent();
                if (it != null && ACTION_LAUNCH.equals(it.getAction())) {
                    pkg = it.getStringExtra(KEY_PACKAGE);
                }
                if (pkg == null || pkg.isEmpty()) {
                    pkg = si.getId();
                }
                if (pkg != null && !pkg.isEmpty()) {
                    out.add(pkg);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 应用原始图标，按 Hail 的 64dp 画成位图；冻结包查不到时退回列表里已加载的图标。 */
    private static Bitmap appIcon(Context c, String pkg, Drawable fallback) {
        int size = Ui.dp(c, ICON_SIZE_DP);
        Drawable d = null;
        try {
            ApplicationInfo ai = c.getPackageManager()
                    .getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            d = ai.loadIcon(c.getPackageManager());
        } catch (Exception ignored) {
        }
        if (d == null) {
            d = fallback;
        }
        if (d == null) {
            try {
                d = c.getPackageManager().getDefaultActivityIcon();
            } catch (Exception ignored) {
            }
        }
        return LaunchTarget.bitmap(d, size);
    }

    private static CharSequence appLabel(Context c, String pkg) {
        try {
            ApplicationInfo ai = c.getPackageManager()
                    .getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            CharSequence l = ai.loadLabel(c.getPackageManager());
            if (l != null && l.length() > 0) {
                return l;
            }
        } catch (Exception ignored) {
        }
        return pkg;
    }
}
