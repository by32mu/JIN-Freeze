package com.jin.freeze.core;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.jin.freeze.JinApp;

/**
 * 划卡冻结（LSPosed 模块）的应用侧实现。
 *
 * system_server 读不到凚 的私有目录，所以开关、名单、标签勾选都由凚 在 root 通道下
 * 写成系统可读的一份 JSON：/data/system/jin_freeze_list.json（system:system, 644，
 * 先写 .tmp 再 mv 原子替换）。hook 侧只按 mtime + 长度的 stamp 变化重读。
 */
public final class SwipeFreeze {

    /** 与 FreezeListActivity.PREFS 一致：这里是同一份数据，不新建存储。 */
    public static final String PREFS = "freeze_list";
    public static final String KEY_ENABLED = "swipe_freeze_enabled";
    public static final String KEY_TAGS = "swipe_freeze_tags";
    /** 锁屏冻结开关：独立于划卡冻结，默认关闭。 */
    public static final String KEY_LOCK_ENABLED = "lock_freeze_enabled";
    /** 锁屏后延时秒数（0-300），0 表示立即冻结。 */
    public static final String KEY_LOCK_DELAY = "lock_freeze_delay_seconds";
    /** 锁屏冻结自己的标签过滤（与划卡冻结互不影响）。 */
    public static final String KEY_LOCK_TAGS = "lock_freeze_tags";
    public static final int LOCK_DELAY_MAX = 300;
    /** 默认「立即冻结」 */
    public static final int LOCK_DELAY_DEFAULT = 0;
    /** 设置页探测 LSPosed 模块是否在线：发 ping，模块在 system_server 回 pong */
    public static final String ACTION_STATUS_PING = "com.jin.freeze.action.STATUS_PING";
    public static final String ACTION_STATUS_PONG = "com.jin.freeze.action.STATUS_PONG";
    public static final String KEY_PKGS = "pkgs";
    public static final String KEY_TABS = "tabs";
    public static final String KEY_APP_TABS = "app_tabs";

    public static final String CONFIG_PATH = "/data/system/jin_freeze_list.json";
    private static final String HEARTBEAT_PATH = "/data/system/jin_freeze_hb";
    private static final String TAB_USER = "user";
    private static final String TAB_SYSTEM = "system";

    /** 标签：id 稳定，name 只用于显示（改名不会影响已勾选的过滤）。 */
    public static final class Tag {
        public final String id;
        public final String name;

        Tag(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static volatile String lastWritten;

    private SwipeFreeze() {
    }

    // ---------------- 偏好 ----------------

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    public static boolean lockEnabled(Context context) {
        return prefs(context).getBoolean(KEY_LOCK_ENABLED, false);
    }

    public static void setLockEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_LOCK_ENABLED, on).apply();
    }

    /** 锁屏后延时秒数：0 表示立即冻结，范围 0-300。 */
    public static int lockDelaySeconds(Context context) {
        int value = prefs(context).getInt(KEY_LOCK_DELAY, LOCK_DELAY_DEFAULT);
        return Math.max(0, Math.min(LOCK_DELAY_MAX, value));
    }

    public static void setLockDelaySeconds(Context context, int seconds) {
        int value = Math.max(0, Math.min(LOCK_DELAY_MAX, seconds));
        prefs(context).edit().putInt(KEY_LOCK_DELAY, value).apply();
    }

    /** 锁屏冻结的标签过滤；null = 从未改过，按「全选」处理。 */
    public static Set<String> lockSelectedTags(Context context) {
        if (!prefs(context).contains(KEY_LOCK_TAGS)) {
            return null;
        }
        Set<String> raw = prefs(context).getStringSet(KEY_LOCK_TAGS, null);
        return raw == null ? null : new HashSet<>(raw);
    }

    public static void setLockSelectedTags(Context context, Set<String> ids) {
        prefs(context).edit().putStringSet(KEY_LOCK_TAGS, new HashSet<>(ids)).apply();
    }

    /** null = 用户从未改过勾选，按「全选」处理（与默认全勾选一致）。 */
    public static Set<String> selectedTags(Context context) {
        if (!prefs(context).contains(KEY_TAGS)) {
            return null;
        }
        Set<String> raw = prefs(context).getStringSet(KEY_TAGS, null);
        return raw == null ? null : new HashSet<>(raw);
    }

    public static void setSelectedTags(Context context, Set<String> ids) {
        prefs(context).edit().putStringSet(KEY_TAGS, new HashSet<>(ids)).apply();
    }

    public static boolean hasRoot() {
        return PrivShell.mode() == PrivShell.Mode.ROOT;
    }

    // ---------------- 标签 ----------------

    private static List<Tag> allTabs(Context context) {
        List<Tag> tabs = new ArrayList<>();
        String raw = prefs(context).getString(KEY_TABS, "");
        if (!raw.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    String id = o.optString("id", "");
                    String name = o.optString("name", "");
                    if (!id.isEmpty() && !name.isEmpty() && !containsTab(tabs, id)) {
                        tabs.add(new Tag(id, name));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (!containsTab(tabs, TAB_USER)) {
            tabs.add(0, new Tag(TAB_USER, "用户应用"));
        }
        if (!containsTab(tabs, TAB_SYSTEM)) {
            tabs.add(Math.min(1, tabs.size()), new Tag(TAB_SYSTEM, "系统应用"));
        }
        return tabs;
    }

    private static boolean containsTab(List<Tag> tabs, String id) {
        for (Tag t : tabs) {
            if (t.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> appTabMap(Context context) {
        Map<String, String> map = new LinkedHashMap<>();
        String raw = prefs(context).getString(KEY_APP_TABS, "");
        if (raw.isEmpty()) {
            return map;
        }
        try {
            JSONObject o = new JSONObject(raw);
            JSONArray names = o.names();
            if (names == null) {
                return map;
            }
            for (int i = 0; i < names.length(); i++) {
                String pkg = names.optString(i, "");
                String tab = o.optString(pkg, "");
                if (!pkg.isEmpty() && !tab.isEmpty()) {
                    map.put(pkg, tab);
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    /** 凚 的全部标签（「按标签冻结」勾选界面用），默认全选。 */
    public static List<Tag> allTags(Context context) {
        return allTabs(context);
    }

    private static String tabIdOf(Context context, String pkg, Map<String, String> mapping) {
        String id = mapping.get(pkg);
        if (id != null && containsTab(allTabs(context), id)) {
            return id;
        }
        return isSystemApp(context, pkg) ? TAB_SYSTEM : TAB_USER;
    }

    private static boolean isSystemApp(Context context, String pkg) {
        try {
            return (context.getPackageManager().getApplicationInfo(pkg, 0).flags
                    & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 名单文件 ----------------

    /** 按当前偏好生成配置文件内容；格式与 hook 侧 JinHookConfig 一一对应。 */
    public static String buildConfig(Context context) {
        Set<String> pkgs = new TreeSet<>(prefs(context).getStringSet(KEY_PKGS, new HashSet<>()));
        Map<String, String> mapping = appTabMap(context);
        Map<String, String> labels = new LinkedHashMap<>();
        for (Tag tag : allTabs(context)) {
            labels.put(tag.id, tag.name);
        }
        Map<String, Set<String>> tags = new LinkedHashMap<>();
        for (String pkg : pkgs) {
            Set<String> set = new HashSet<>();
            set.add(tabIdOf(context, pkg, mapping));
            tags.put(pkg, set);
        }
        Set<String> selected = selectedTags(context);
        Set<String> lockSelected = lockSelectedTags(context);

        JSONObject root = new JSONObject();
        try {
            root.put("version", 1);
            root.put("enabled", enabled(context));
            // 锁屏冻结独立开关与延时（秒），hook 侧锁屏时读取
            root.put("lock_enabled", lockEnabled(context));
            root.put("lock_delay", lockDelaySeconds(context));
            root.put("packages", new JSONArray(pkgs));

            JSONObject tagsObj = new JSONObject();
            for (Map.Entry<String, Set<String>> e : tags.entrySet()) {
                tagsObj.put(e.getKey(), new JSONArray(e.getValue()));
            }
            root.put("tags", tagsObj);

            JSONObject labelsObj = new JSONObject();
            for (Map.Entry<String, String> e : labels.entrySet()) {
                labelsObj.put(e.getKey(), e.getValue());
            }
            root.put("labels", labelsObj);

            // 没写过勾选：不写 selected，hook 侧按「全选」处理
            if (selected != null) {
                root.put("selected", new JSONArray(selected));
            }
            if (lockSelected != null) {
                root.put("lock_tags", new JSONArray(lockSelected));
            }
        } catch (Exception ignored) {
        }
        return root.toString();
    }

    /** 后台同步，不阻塞 UI；非 root 直接跳过（Shizuku 是 uid 2000，写不进 /data/system）。 */
    public static void syncAsync(Context context) {
        Context app = context.getApplicationContext();
        JinApp.IO.execute(() -> sync(app));
    }

    public static void sync(Context context) {
        if (!hasRoot()) {
            return;
        }
        String json = buildConfig(context);
        if (json.equals(lastWritten)) {
            return;
        }
        if (writeConfig(json)) {
            lastWritten = json;
        }
    }

    private static boolean writeConfig(String json) {
        String cmd = "umask 022; cat > " + CONFIG_PATH + ".tmp"
                + " && chown system:system " + CONFIG_PATH + ".tmp"
                + " && chmod 644 " + CONFIG_PATH + ".tmp"
                + " && mv " + CONFIG_PATH + ".tmp " + CONFIG_PATH;
        try {
            Process process = new ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            try (OutputStream out = process.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            int code = finished ? process.exitValue() : -1;
            if (!finished) {
                process.destroy();
            }
            if (code == 0) {
                return true;
            }
            android.util.Log.w("JinSwipeFreeze", "写入名单失败，退出码 " + code);
        } catch (Throwable t) {
            android.util.Log.w("JinSwipeFreeze", "写入名单异常", t);
        }
        return false;
    }

    /**
     * 模块是否已在本次开机后进入 system_server。
     * 心跳文件由 hook 加载时写入；没有心跳说明 LSPosed 未激活 / 凚 没被勾选作用域 / 还没重启。
     */
    public static boolean moduleActive() {
        if (!hasRoot()) {
            return false;
        }
        PrivShell.Result r = PrivShell.exec("cat " + HEARTBEAT_PATH + " 2>/dev/null", 6);
        if (r.out == null || r.out.trim().isEmpty()) {
            return false;
        }
        String[] parts = r.out.trim().split("\\s+");
        try {
            long written = Long.parseLong(parts[parts.length - 1]);
            long bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime();
            // 允许 2 分钟误差：心跳必须产生在本次开机之后
            return written >= bootAt - 120_000L;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 前往 LSPosed 管理器；找不到管理器时退回系统「模块管理」入口。 */
    public static void openLsposed(Context context) {
        String[] candidates = {
                "org.lsposed.manager",
                "de.robv.android.xposed.installer",
        };
        for (String pkg : candidates) {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(intent);
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
        // 部分 ROM 用口令码打开管理器
        try {
            Intent secret = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("android_secret_code://5776733"));
            secret.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(secret);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent module = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            module.setData(android.net.Uri.parse("package:" + context.getPackageName()));
            module.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(module);
        } catch (Throwable ignored) {
        }
    }
}
