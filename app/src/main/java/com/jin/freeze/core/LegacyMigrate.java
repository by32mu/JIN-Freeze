package com.jin.freeze.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 旧版「搞机助手」(com.bigfre.manager) 的冻结名单与布局设置迁移。
 * 旧数据在对方私有目录里，只有 root（或 root 模式运行的 Shizuku）能直接读取；
 * 读不到时返回明确原因，由界面引导改用备份文本粘贴导入。
 */
public final class LegacyMigrate {

    public static final String LEGACY_PKG = "com.bigfre.manager";
    public static final String LEGACY_PREFS = "freeze_list";
    private static final String[] LEGACY_PATHS = {
            "/data/data/" + LEGACY_PKG + "/shared_prefs/" + LEGACY_PREFS + ".xml",
            "/data/user/0/" + LEGACY_PKG + "/shared_prefs/" + LEGACY_PREFS + ".xml"
    };

    /** 名单类 key 原样搬；布局类 key 面板形态下还要补一份 panel_ 前缀。 */
    private static final String[] DATA_KEYS = {
            "pkgs", "order", "custom_order", "tabs", "app_tabs", "current_tab"
    };
    private static final String[] LAYOUT_KEYS = {
            "grid_columns", "grid_icon_dp", "grid_icon_manual", "grid_show_name",
            "grid_move_mode", "fab_dx", "fab_dy", "fab_size", "show_frozen_toast",
            "panel_height_dp", "panel_handle_enabled", "panel_hide_recents",
            "panel_dismiss_after_freeze"
    };
    private static final String[] PANEL_KEYS = {
            "panel_grid_columns", "panel_grid_icon_dp", "panel_grid_icon_manual",
            "panel_grid_show_name", "panel_grid_move_mode", "panel_fab_dx",
            "panel_fab_dy", "panel_fab_size", "panel_show_frozen_toast"
    };

    public static final class Outcome {
        public final int count;
        public final String error;

        Outcome(int count, String error) {
            this.count = count;
            this.error = error;
        }
    }

    private LegacyMigrate() {
    }

    public static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(LEGACY_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取并导入；必须在线程里调用（会执行特权命令）。 */
    public static Outcome readAndImport(Context c) {
        String xml = readLegacyXml();
        if (xml == null) {
            return new Outcome(0, "读不到旧版数据：需要 root，或已授权的 Shizuku（root 模式）");
        }
        Map<String, Object> values = parse(xml);
        if (values.isEmpty()) {
            return new Outcome(0, "旧版数据为空或格式无法识别");
        }
        return new Outcome(apply(c, values), null);
    }

    private static String readLegacyXml() {
        for (String path : LEGACY_PATHS) {
            PrivShell.Result r = PrivShell.exec("cat " + path, 15);
            String out = r.out == null ? "" : r.out;
            if (out.contains("<map")) {
                return out;
            }
        }
        return null;
    }

    private static int apply(Context c, Map<String, Object> values) {
        SharedPreferences p = c.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = p.edit();
        int count = 0;
        for (String key : DATA_KEYS) {
            Object v = values.get(key);
            if (v instanceof String) {
                ed.putString(key, (String) v);
            } else if (v instanceof Set) {
                @SuppressWarnings("unchecked")
                Set<String> set = new HashSet<>((Set<String>) v);
                ed.putStringSet(key, set);
                if ("pkgs".equals(key)) {
                    count = set.size();
                }
            } else if (v instanceof Boolean) {
                ed.putBoolean(key, (Boolean) v);
            } else if (v instanceof Integer) {
                ed.putInt(key, (Integer) v);
            }
        }
        for (String key : LAYOUT_KEYS) {
            Object v = values.get(key);
            if (v == null) {
                continue;
            }
            putTyped(ed, key, v);
            if (key.startsWith("grid_") || key.startsWith("fab_")) {
                putTyped(ed, "panel_" + key, v);
            }
        }
        // 旧版半屏布局优先（如果它自己存过）
        for (String key : PANEL_KEYS) {
            Object v = values.get(key);
            if (v != null) {
                putTyped(ed, key, v);
            }
        }
        ed.putBoolean("panel_layout_init", true)
                .putBoolean("migrate_checked", true)
                .putBoolean("legacy_offer_done", true)
                .apply();
        return count;
    }

    private static void putTyped(SharedPreferences.Editor ed, String key, Object v) {
        if (v instanceof String) {
            ed.putString(key, (String) v);
        } else if (v instanceof Boolean) {
            ed.putBoolean(key, (Boolean) v);
        } else if (v instanceof Integer) {
            ed.putInt(key, (Integer) v);
        } else if (v instanceof Set) {
            @SuppressWarnings("unchecked")
            Set<String> set = new HashSet<>((Set<String>) v);
            ed.putStringSet(key, set);
        }
    }

    /** 极简 SharedPreferences XML 解析：string / set / boolean / int / long / float。 */
    private static Map<String, Object> parse(String xml) {
        Map<String, Object> map = new HashMap<>();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new StringReader(xml));
            int ev;
            Set<String> curSet = null;
            while ((ev = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("set".equals(tag)) {
                        String name = parser.getAttributeValue(null, "name");
                        curSet = new HashSet<>();
                        if (name != null) {
                            map.put(name, curSet);
                        }
                    } else if ("string".equals(tag)) {
                        String name = parser.getAttributeValue(null, "name");
                        String value = parser.nextText();
                        if (curSet != null) {
                            curSet.add(value);
                        } else if (name != null) {
                            map.put(name, value);
                        }
                    } else if ("boolean".equals(tag)) {
                        String name = parser.getAttributeValue(null, "name");
                        String value = parser.getAttributeValue(null, "value");
                        if (name != null) {
                            map.put(name, "true".equalsIgnoreCase(value));
                        }
                    } else if ("int".equals(tag)) {
                        String name = parser.getAttributeValue(null, "name");
                        String value = parser.getAttributeValue(null, "value");
                        if (name != null) {
                            try {
                                map.put(name, Integer.parseInt(value));
                            } catch (Exception ignored) {
                            }
                        }
                    } else if ("long".equals(tag)) {
                        String name = parser.getAttributeValue(null, "name");
                        String value = parser.getAttributeValue(null, "value");
                        if (name != null) {
                            try {
                                map.put(name, (int) Long.parseLong(value));
                            } catch (Exception ignored) {
                            }
                        }
                    } else if ("float".equals(tag)) {
                        String name = parser.getAttributeValue(null, "name");
                        String value = parser.getAttributeValue(null, "value");
                        if (name != null) {
                            try {
                                map.put(name, (int) Float.parseFloat(value));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG
                        && "set".equals(parser.getName())) {
                    curSet = null;
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }
}
