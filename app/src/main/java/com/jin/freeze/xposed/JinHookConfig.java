package com.jin.freeze.xposed;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * system_server 侧读取凚 写下的划卡冻结配置。
 *
 * 只看 stamp（mtime + 长度）：文件没变就直接用内存快照，避免每次划卡都读盘解析。
 * 读失败或解析失败一律沿用上一次成功快照，绝不把「读不到」当成「没开启/名单为空」，
 * 但从来没有成功读过时按「不冻结」处理，避免误冻。
 */
public final class JinHookConfig {

    /** 由凚 用 root 写入：system_data_file / system:system / 644 */
    public static final String CONFIG_PATH = "/data/system/jin_freeze_list.json";

    /** 模块加载时写的心跳，凚 用它判断「本机这次开机后 hook 真的进了 system_server」。 */
    public static final String HEARTBEAT_PATH = "/data/system/jin_freeze_hb";

    public static final class Snapshot {
        final boolean enabled;
        /** 锁屏冻结开关（与划卡冻结互不影响） */
        final boolean lockEnabled;
        /** 锁屏后延时秒数，0 = 立即冻结 */
        final int lockDelaySeconds;
        final Set<String> packages;
        final Map<String, Set<String>> tags;
        final Map<String, String> labels;
        /** null = 未写该字段（视为全选） */
        final Set<String> selected;
        /** 锁屏冻结自己的标签勾选；null = 未写该字段（视为全选） */
        final Set<String> lockSelected;
        /** 消除启动白底：关掉时完全按系统默认的启动动画走。 */
        final boolean noLaunchBg;
        /** 活体解冻（DoL）：点图标/通知启动冻结应用时，先解冻再放行。 */
        final boolean dol;
        /** 静默打开置灰图标：真正取消暂停后静默打开，回后台不再自动暂停。 */
        final boolean silentOpen;
        /** 保留桌面图标（转暂停）：划卡/锁屏冻结用 suspend 而不是 disable。 */
        final boolean suspend;
        /** 用户标记隐藏的实例键：划卡/锁屏遇到它们改走隐藏，不再冻结。 */
        final Set<String> hiddenIntent;

        Snapshot(boolean enabled, boolean lockEnabled, int lockDelaySeconds,
                 Set<String> packages, Map<String, Set<String>> tags,
                 Map<String, String> labels, Set<String> selected, Set<String> lockSelected,
                 boolean noLaunchBg, boolean dol, boolean silentOpen, boolean suspend,
                 Set<String> hiddenIntent) {
            this.enabled = enabled;
            this.lockEnabled = lockEnabled;
            this.lockDelaySeconds = lockDelaySeconds;
            this.packages = packages;
            this.tags = tags;
            this.labels = labels;
            this.selected = selected;
            this.lockSelected = lockSelected;
            this.noLaunchBg = noLaunchBg;
            this.dol = dol;
            this.silentOpen = silentOpen;
            this.suspend = suspend;
            this.hiddenIntent = hiddenIntent;
        }
    }

    private static volatile Snapshot cached;
    private static volatile String cachedStamp;
    private static volatile String lastError;

    private JinHookConfig() {
    }

    /** 名单里的一条：包名 + 用户号（主用户 0，双开/分身是 999、10 等）。 */
    public static final class Instance {
        public final String pkg;
        public final int userId;

        public Instance(String pkg, int userId) {
            this.pkg = pkg;
            this.userId = userId;
        }

        /** 实例键：主用户是纯包名，分身带 @用户，与 App 侧名单键一致。 */
        public String key() {
            return instanceKey(pkg, userId);
        }
    }

    /** 实例键：userId 0 用纯包名，其余追加「@用户」。 */
    static String instanceKey(String pkg, int userId) {
        return userId == 0 ? pkg : pkg + "@" + userId;
    }

    /** 从实例键取用户号：没有 @ 视为主用户 0。 */
    static int userIdOf(String key) {
        int at = key.lastIndexOf('@');
        if (at <= 0) {
            return 0;
        }
        try {
            return Integer.parseInt(key.substring(at + 1).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** 从实例键取纯包名。 */
    static String pkgOf(String key) {
        int at = key.lastIndexOf('@');
        return at > 0 ? key.substring(0, at) : key;
    }

    /** 解析实例键；格式不认识时按主用户处理，交给 setApplicationEnabledSetting 自然失败。 */
    static Instance parseInstance(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        return new Instance(pkgOf(key), userIdOf(key));
    }

    public static boolean shouldFreeze(String pkg, int userId) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        Snapshot snap = snapshot();
        if (snap == null || !snap.enabled) {
            return false;
        }
        String key = instanceKey(pkg, userId);
        if (!snap.packages.contains(key)) {
            // 兼容旧配置（只有纯包名）：退回按包名匹配
            if (!snap.packages.contains(pkg)) {
                return false;
            }
            key = pkg;
        }
        return passesTags(snap, key, snap.selected);
    }

    /**
     * 锁屏冻结用：只看名单 + 标签口径，与划卡冻结开关无关
     * （锁屏冻结是独立开关，关掉划卡不影响锁屏）。
     */
    public static List<Instance> freezablePackages() {
        List<Instance> instances = new ArrayList<>();
        Snapshot snap = snapshot();
        if (snap == null) {
            return instances;
        }
        for (String key : snap.packages) {
            Instance instance = parseInstance(key);
            if (instance != null && passesTags(snap, key, snap.lockSelected)) {
                instances.add(instance);
            }
        }
        return instances;
    }

    /** 标签口径：一个都没勾不冻；包没有标签信息则不参与过滤（按名单冻结）。 */
    private static boolean passesTags(Snapshot snap, String key, Set<String> selected) {
        if (selected != null && selected.isEmpty()) {
            return false;
        }
        Set<String> tags = snap.tags.get(key);
        if (tags == null) {
            // 实例键查不到时退回纯包名（旧配置 / 旧标签数据）
            tags = snap.tags.get(pkgOf(key));
        }
        if (tags == null || tags.isEmpty()) {
            return true;
        }
        if (selected == null) {
            return true;
        }
        for (String tag : tags) {
            if (selected.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    public static Snapshot snapshot() {
        try {
            File file = new File(CONFIG_PATH);
            if (!file.exists()) {
                return cached;
            }
            String stamp = file.lastModified() + ":" + file.length();
            Snapshot hit = cached;
            if (hit != null && stamp.equals(cachedStamp)) {
                return hit;
            }
            Snapshot parsed = parse(file);
            cached = parsed;
            cachedStamp = stamp;
            return parsed;
        } catch (Throwable t) {
            String message = t.getClass().getSimpleName() + ": " + t.getMessage();
            if (!message.equals(lastError)) {
                lastError = message;
                android.util.Log.w("JinSwipeFreeze", "配置读取失败，沿用上次缓存: " + message);
            }
            return cached;
        }
    }

    private static Snapshot parse(File file) throws Exception {
        String text = read(file);
        JSONObject root = new JSONObject(text);
        boolean enabled = root.optBoolean("enabled", false);
        boolean lockEnabled = root.optBoolean("lock_enabled", false);
        int lockDelaySeconds = Math.max(0, Math.min(300, root.optInt("lock_delay", 0)));

        Set<String> packages = new HashSet<>();
        JSONArray array = root.optJSONArray("packages");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String pkg = array.optString(i, "").trim();
                if (!pkg.isEmpty()) {
                    packages.add(pkg);
                }
            }
        }

        Map<String, Set<String>> tags = new HashMap<>();
        JSONObject tagsObj = root.optJSONObject("tags");
        if (tagsObj != null) {
            JSONArray names = tagsObj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String pkg = names.optString(i, "");
                    JSONArray values = tagsObj.optJSONArray(pkg);
                    if (pkg.isEmpty() || values == null) {
                        continue;
                    }
                    Set<String> set = new HashSet<>();
                    for (int j = 0; j < values.length(); j++) {
                        String id = values.optString(j, "").trim();
                        if (!id.isEmpty()) {
                            set.add(id);
                        }
                    }
                    if (!set.isEmpty()) {
                        tags.put(pkg, set);
                    }
                }
            }
        }

        Map<String, String> labels = new HashMap<>();
        JSONObject labelsObj = root.optJSONObject("labels");
        if (labelsObj != null) {
            JSONArray names = labelsObj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String id = names.optString(i, "");
                    if (!id.isEmpty()) {
                        labels.put(id, labelsObj.optString(id, id));
                    }
                }
            }
        }

        Set<String> selected = readIds(root, "selected");
        Set<String> lockSelected = readIds(root, "lock_tags");
        boolean noLaunchBg = root.optBoolean("no_launch_bg", false);
        boolean dol = root.optBoolean("dol", false);
        boolean silentOpen = root.optBoolean("silent_open", false);
        boolean suspend = root.optBoolean("suspend", false);
        Set<String> hiddenIntent = new HashSet<>();
        JSONArray hiddenArr = root.optJSONArray("hidden");
        if (hiddenArr != null) {
            for (int i = 0; i < hiddenArr.length(); i++) {
                String k = hiddenArr.optString(i, "").trim();
                if (!k.isEmpty()) {
                    hiddenIntent.add(k);
                }
            }
        }
        return new Snapshot(enabled, lockEnabled, lockDelaySeconds,
                Collections.unmodifiableSet(packages),
                Collections.unmodifiableMap(tags), Collections.unmodifiableMap(labels),
                selected, lockSelected, noLaunchBg, dol, silentOpen, suspend,
                Collections.unmodifiableSet(hiddenIntent));
    }

    /** 消除启动白底开关：配置里显式打开才生效，默认关、完全走系统默认。 */
    public static boolean noLaunchBg() {
        Snapshot snap = snapshot();
        return snap != null && snap.noLaunchBg;
    }

    /** 活体解冻总开关。 */
    public static boolean dolEnabled() {
        Snapshot snap = snapshot();
        return snap != null && snap.dol;
    }

    /** 静默打开置灰图标总开关。 */
    public static boolean silentOpenEnabled() {
        Snapshot snap = snapshot();
        return snap != null && snap.silentOpen;
    }

    /** 保留桌面图标（转暂停）：划卡/锁屏冻结用 suspend 而不是 disable。 */
    public static boolean suspendMode() {
        Snapshot snap = snapshot();
        return snap != null && snap.suspend;
    }

    /** 该实例是否被用户标记为「隐藏」：划卡/锁屏遇到它改走隐藏，不再冻结。 */
    public static boolean hiddenIntent(String pkg, int userId) {
        Snapshot snap = snapshot();
        if (snap == null || pkg == null || pkg.isEmpty()) {
            return false;
        }
        return snap.hiddenIntent.contains(instanceKey(pkg, userId))
                || snap.hiddenIntent.contains(pkg);
    }

    /**
     * 该包名在名单里的实例用户号（主用户 0；双开/分身 999、10 等）。
     * DoL 只按纯包名匹配，不参与标签过滤：被冻结的实例都应该能点开。
     */
    public static List<Integer> listedUsers(String pkg) {
        List<Integer> users = new ArrayList<>();
        Snapshot snap = snapshot();
        if (snap == null || pkg == null || pkg.isEmpty()) {
            return users;
        }
        for (String key : snap.packages) {
            if (pkg.equals(pkgOf(key)) && !users.contains(userIdOf(key))) {
                users.add(userIdOf(key));
            }
        }
        return users;
    }

    /** 读字符串数组字段；字段缺失（或 null）= 未设置，返回 null；空数组 = 一个都没勾。 */
    private static Set<String> readIds(JSONObject root, String key) {
        if (!root.has(key) || root.isNull(key)) {
            return null;
        }
        Set<String> ids = new HashSet<>();
        JSONArray array = root.optJSONArray(key);
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String id = array.optString(i, "").trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private static String read(File file) throws Exception {
        if (file.length() > 4L * 1024 * 1024) {
            throw new IllegalStateException("配置过大: " + file.length());
        }
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
