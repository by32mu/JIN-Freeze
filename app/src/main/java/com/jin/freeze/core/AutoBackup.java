package com.jin.freeze.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * 名单自动备份：白名单、主副关联、冻结列表、标签归属等名单类数据，
 * 除了存在应用私有配置里，额外写一份到外部存储（Download/凚/名单自动备份.json）。
 *
 * 正常覆盖升级不会动应用私有配置，本来就不会丢；这里兜底的是卸载重装、
 * 清理数据、换签名重装这类会清空配置的场景：启动时发现名单全空且存在自动备份，
 * 就自动恢复。已有数据的情况下绝不覆盖，避免冲掉用户现在的名单。
 */
public final class AutoBackup {

    private static final String PREFS = "freeze_list";
    private static final String DIR = "凚";
    private static final String FILE = "名单自动备份.json";

    private static final String[] SET_KEYS = {
            "pkgs", "freeze_whitelist"
    };
    private static final String[] STRING_KEYS = {
            "tabs", "app_tabs", "order", "current_tab", "default_tab",
            // 主副关联名单按「主键=副键」的纯文本保存，不是字符串集合
            "sub_app_pairs",
            // 应用自定义显示名
            "app_names"
    };

    private AutoBackup() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File backupFile(Context c) {
        File dir = new File(Environment.getExternalStorageDirectory(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return new File(dir, FILE);
    }

    /** 后台写一份快照；没有存储权限等情况静默跳过，不影响正常使用。 */
    public static void snapshotAsync(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                snapshot(app);
            } catch (Throwable ignored) {
            }
        }, "jin-auto-backup").start();
    }

    private static void snapshot(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        JSONObject root = new JSONObject();

        JSONObject sets = new JSONObject();
        for (String key : SET_KEYS) {
            Set<String> v = p.getStringSet(key, null);
            if (v == null) {
                continue;
            }
            JSONArray arr = new JSONArray();
            for (String s : v) {
                arr.put(s);
            }
            sets.put(key, arr);
        }
        root.put("sets", sets);

        JSONObject strings = new JSONObject();
        for (String key : STRING_KEYS) {
            String v = p.getString(key, null);
            if (v != null) {
                strings.put(key, v);
            }
        }
        root.put("strings", strings);

        File f = backupFile(c);
        if (f == null) {
            return;
        }
        byte[] data = root.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        } catch (Throwable direct) {
            // 清数据/重装后没有「所有文件访问」时，借 root/Shizuku 写回同一路径
            String b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
            PrivShell.Result r = PrivShell.exec(
                    "printf %s " + b64 + " | base64 -d > " + shellQuote(f.getAbsolutePath()), 10);
            if (r.code != 0) {
                if (direct instanceof Exception) {
                    throw (Exception) direct;
                }
                throw new Exception(direct);
            }
        }
    }

    /** 名单是否全空（全新安装、清数据、卸载重装后的状态）。 */
    public static boolean isBlank(Context c) {
        SharedPreferences p = prefs(c);
        Set<String> pkgs = p.getStringSet("pkgs", null);
        return (pkgs == null || pkgs.isEmpty())
                && p.getStringSet("freeze_whitelist", null) == null
                && p.getString("sub_app_pairs", "").isEmpty()
                && p.getString("tabs", "").isEmpty()
                && p.getString("app_tabs", "").isEmpty();
    }

    /**
     * 全新安装/数据被清空时自动恢复名单（直接读外部备份，适合主线程快速调用）。
     *
     * @return 是否真的恢复了数据
     */
    public static boolean restoreIfFresh(Context c) {
        if (!isBlank(c)) {
            return false;
        }
        File f = backupFile(c);
        if (f == null || !f.isFile()) {
            return false;
        }
        String raw;
        try {
            raw = readAll(f);
        } catch (Throwable t) {
            return false;
        }
        return restore(prefs(c), raw);
    }

    /**
     * 同 restoreIfFresh，但直接读不到时借 root/Shizuku 去读同一份备份。
     * 清数据后「所有文件访问」会被系统收回，只有放后台走这条路才能真正恢复。
     */
    public static boolean restoreIfFreshPrivileged(Context c) {
        if (!isBlank(c)) {
            return false;
        }
        File f = backupFile(c);
        if (f == null) {
            return false;
        }
        String raw = null;
        try {
            raw = readAll(f);
        } catch (Throwable ignored) {
        }
        if (raw == null || raw.isEmpty()) {
            try {
                PrivShell.Result r = PrivShell.exec("cat " + shellQuote(f.getAbsolutePath()), 8);
                if (r.code == 0 && r.out != null && !r.out.trim().isEmpty()) {
                    raw = r.out.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        return raw != null && !raw.isEmpty() && restore(prefs(c), raw);
    }

    private static boolean restore(SharedPreferences p, String raw) {
        try {
            if (raw == null || raw.isEmpty()) {
                return false;
            }
            JSONObject root = new JSONObject(raw);
            SharedPreferences.Editor ed = p.edit();
            boolean any = false;

            JSONObject sets = root.optJSONObject("sets");
            if (sets != null) {
                Iterator<String> keys = sets.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONArray arr = sets.optJSONArray(key);
                    if (arr == null) {
                        continue;
                    }
                    Set<String> set = new HashSet<>();
                    for (int i = 0; i < arr.length(); i++) {
                        String s = arr.optString(i, "");
                        if (!s.isEmpty()) {
                            set.add(s);
                        }
                    }
                    if (!set.isEmpty()) {
                        ed.putStringSet(key, set);
                        any = true;
                    }
                }
            }

            JSONObject strings = root.optJSONObject("strings");
            if (strings != null) {
                Iterator<String> keys = strings.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String v = strings.optString(key, null);
                    if (v != null && !v.isEmpty()) {
                        ed.putString(key, v);
                        any = true;
                    }
                }
            }
            if (any) {
                ed.apply();
            }
            return any;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
            }
            return buf.toString("UTF-8");
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
