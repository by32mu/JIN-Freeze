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
import com.jin.freeze.core.PrivShell;

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

    /** 纯包名：兼容「pkg@999」的分身实例键。 */
    private static String packagePart(String key) {
        int at = key == null ? -1 : key.lastIndexOf('@');
        return at > 0 ? key.substring(0, at) : key;
    }

    /** 实例用户号：主用户 0，双开/分身是 999 等。 */
    private static int userIdPart(String key) {
        int at = key == null ? -1 : key.lastIndexOf('@');
        if (at >= 0) {
            try {
                return Integer.parseInt(key.substring(at + 1).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /**
     * 单个快捷方式（长按菜单 / 应用页）：直接请求，桌面弹一次确认框。
     * key 可以是「pkg」也可以是「pkg@999」，分身会拿到独立的快捷方式 ID。
     */
    public static void pin(android.app.Activity a, String key, CharSequence name, Drawable icon) {
        request(a, key, name, icon, null);
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
        for (String key : pkgs) {
            if (key == null || key.isEmpty() || pinned.contains(key)) {
                continue;
            }
            try {
                pm.getApplicationInfo(packagePart(key), PackageManager.MATCH_DISABLED_COMPONENTS);
            } catch (Exception e) {
                continue;
            }
            if (!QUEUE.contains(key)) {
                QUEUE.add(key);
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
        String key = QUEUE.poll();
        currentToken = key;
        request(c, key, null, null, key);
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
    private static void request(Context ctx, String key, CharSequence name, Drawable icon,
                                String token) {
        final String pkg = packagePart(key);
        final int userId = userIdPart(key);
        // 快捷方式 ID 用只含字母数字下划线的形式区分主应用和分身，
        // 避免「已固定主应用后，分身因 ID 冲突无法再添加」
        final String shortcutId = userId == 0 ? pkg : pkg + "_u" + userId;
        // 携带的实例键仍用 pkg@用户号，交给 QuickLaunchActivity 解析
        final String launchKey = userId == 0 ? pkg : pkg + "@" + userId;
        Intent intent = new Intent(ACTION_LAUNCH).putExtra(KEY_PACKAGE, launchKey);
        Bitmap bmp = appIcon(ctx, pkg, icon);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Boolean.FALSE.equals(miuiShortcutAllowed(ctx))) {
                // MIUI/HyperOS 的「桌面快捷方式」权限没开时，系统不会弹确认框，图标加不上。
                // 有 root/Shizuku 就先 appops 静默授予（AppOps 10017）再复查，
                // 授上了直接继续添加；确实授不上才提示用户去手动开启。
                PrivShell.exec("appops set " + ctx.getPackageName() + " 10017 allow", 8);
                if (Boolean.FALSE.equals(miuiShortcutAllowed(ctx))) {
                    if (token != null) {
                        QUEUE.clear();
                    }
                    Toast.makeText(ctx, "请先开启「桌面快捷方式」权限",
                            Toast.LENGTH_LONG).show();
                    return;
                }
            }
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
                Toast.makeText(ctx, "添加到桌面失败：当前桌面不支持固定快捷方式",
                        Toast.LENGTH_LONG).show();
                return;
            }
            IconCompat iconCompat = bmp != null
                    ? IconCompat.createWithBitmap(bmp)
                    : IconCompat.createWithResource(ctx, R.drawable.ic_launcher);
            CharSequence label = name == null || name.length() == 0
                    ? fallbackLabel(ctx, key) : name;
            ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(ctx, shortcutId)
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
                    name == null || name.length() == 0 ? fallbackLabel(ctx, key) : name);
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
                    // 分身快捷方式 ID 是 pkg_u999，转回实例键 pkg@999 再比较
                    int u = pkg.lastIndexOf("_u");
                    if (u > 0) {
                        String tail = pkg.substring(u + 2);
                        if (tail.matches("\\d+")) {
                            pkg = pkg.substring(0, u) + "@" + tail;
                        }
                    }
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

    /** 批量/恢复没有显式名称时：重命名过的用自定义名，否则用系统应用名。 */
    private static CharSequence fallbackLabel(Context c, String key) {
        String custom = customName(c, key);
        return custom.isEmpty() ? appLabel(c, packagePart(key)) : custom;
    }

    /**
     * MIUI/HyperOS 的「桌面快捷方式」权限对应自身的 AppOps 10017：
     * 允许 / 拒绝 / 其它（或非小米设备、取不到）三种结果，后者返回 null 不拦。
     */
    private static Boolean miuiShortcutAllowed(Context c) {
        if (!"xiaomi".equalsIgnoreCase(Build.MANUFACTURER)) {
            return null;
        }
        try {
            android.app.AppOpsManager am = (android.app.AppOpsManager)
                    c.getSystemService(Context.APP_OPS_SERVICE);
            if (am == null) {
                return null;
            }
            java.lang.reflect.Method m = android.app.AppOpsManager.class.getMethod(
                    "checkOpNoThrow", int.class, int.class, String.class);
            int mode = (int) m.invoke(am, 10017,
                    android.os.Process.myUid(), c.getPackageName());
            if (mode == android.app.AppOpsManager.MODE_ALLOWED) {
                return Boolean.TRUE;
            }
            return mode == android.app.AppOpsManager.MODE_IGNORED ? Boolean.FALSE : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 名单里保存的自定义显示名（空 = 没重命名）。 */
    private static String customName(Context c, String key) {
        try {
            String raw = c.getSharedPreferences("freeze_list", Context.MODE_PRIVATE)
                    .getString("app_names", "{}");
            return new org.json.JSONObject(raw).optString(key, "");
        } catch (Throwable ignored) {
            return "";
        }
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
