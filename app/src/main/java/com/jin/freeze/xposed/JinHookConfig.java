package com.jin.freeze.xposed;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
        final Set<String> packages;
        final Map<String, Set<String>> tags;
        final Map<String, String> labels;
        /** null = 未写该字段（视为全选） */
        final Set<String> selected;

        Snapshot(boolean enabled, Set<String> packages, Map<String, Set<String>> tags,
                 Map<String, String> labels, Set<String> selected) {
            this.enabled = enabled;
            this.packages = packages;
            this.tags = tags;
            this.labels = labels;
            this.selected = selected;
        }
    }

    private static volatile Snapshot cached;
    private static volatile String cachedStamp;
    private static volatile String lastError;

    private JinHookConfig() {
    }

    public static boolean shouldFreeze(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        Snapshot snap = snapshot();
        if (snap == null || !snap.enabled || !snap.packages.contains(pkg)) {
            return false;
        }
        // 一个标签都没勾：不冻结任何应用
        if (snap.selected != null && snap.selected.isEmpty()) {
            return false;
        }
        Set<String> tags = snap.tags.get(pkg);
        // 没有标签信息的老格式：不参与标签过滤，仍按名单冻结
        if (tags == null || tags.isEmpty()) {
            return true;
        }
        if (snap.selected == null) {
            return true;
        }
        for (String tag : tags) {
            if (snap.selected.contains(tag)) {
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

        Set<String> selected = null;
        if (root.has("selected") && !root.isNull("selected")) {
            selected = new HashSet<>();
            JSONArray array2 = root.optJSONArray("selected");
            if (array2 != null) {
                for (int i = 0; i < array2.length(); i++) {
                    String id = array2.optString(i, "").trim();
                    if (!id.isEmpty()) {
                        selected.add(id);
                    }
                }
            }
        }
        return new Snapshot(enabled, Collections.unmodifiableSet(packages),
                Collections.unmodifiableMap(tags), Collections.unmodifiableMap(labels), selected);
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
