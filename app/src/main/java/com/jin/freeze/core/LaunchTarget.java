package com.jin.freeze.core;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

import com.jin.freeze.R;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Random;

/**
 * 快捷方式的目标与启动。
 * 目标有两种：page（某个应用的页面）和 shell（一条 Shell 指令）。
 * 桌面快捷方式、Root启动开关各存各的 key，互不覆盖。
 * 快捷方式的启动 Intent 只带一个随机 key，真实目标留在应用内部，
 * 避免其他应用借本应用伪造目标做 root 启动。
 */
public final class LaunchTarget {

    public static final int OK = 0;
    /** 目标应用、页面或指令已不存在。 */
    public static final int NO_TARGET = 1;
    /** 页面未导出（或指令要 root），且没开 Root启动 / 没有 root 权限。 */
    public static final int NEED_ROOT = 2;
    /** 执行或启动失败。 */
    public static final int FAILED = 3;

    public static final String TYPE_PAGE = "page";
    public static final String TYPE_SHELL = "shell";

    private static final String PREFS = "quick_launch";
    private static final String SC_TYPE = "shortcut_type";
    private static final String SC_PKG = "shortcut_pkg";
    private static final String SC_CLS = "shortcut_cls";
    private static final String SC_ID = "shortcut_id";
    private static final String SC_LABEL = "shortcut_label";
    private static final String ROOT_FORCE = "root_force";
    private static final String LAUNCH_PREFIX = "launch_";

    /** 一个可绑定目标：页面或 Shell 指令。 */
    public static final class Target {
        public final String type;
        public final String pkg;
        public final String cls;
        /** Shell 指令的 id。 */
        public final String id;
        public final String label;

        public Target(String type, String pkg, String cls, String id, String label) {
            this.type = type == null || type.isEmpty() ? TYPE_PAGE : type;
            this.pkg = pkg == null ? "" : pkg;
            this.cls = cls == null ? "" : cls;
            this.id = id == null ? "" : id;
            this.label = label == null ? "" : label;
        }

        public Target(String pkg, String cls, String label) {
            this(TYPE_PAGE, pkg, cls, "", label);
        }

        public static Target page(String pkg, String cls, String label) {
            return new Target(TYPE_PAGE, pkg, cls, "", label);
        }

        public static Target shell(String id, String label) {
            return new Target(TYPE_SHELL, "", "", id, label);
        }

        public boolean shell() {
            return TYPE_SHELL.equals(type);
        }

        public boolean valid() {
            return shell() ? !id.isEmpty() : (!pkg.isEmpty() && !cls.isEmpty());
        }
    }

    private LaunchTarget() {
    }

    // ==================== 存储 ====================

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Target shortcut(Context c) {
        return read(c, SC_TYPE, SC_PKG, SC_CLS, SC_ID, SC_LABEL);
    }

    private static Target read(Context c, String kType, String kPkg, String kCls,
                               String kId, String kLabel) {
        SharedPreferences sp = prefs(c);
        Target t = new Target(sp.getString(kType, TYPE_PAGE),
                sp.getString(kPkg, ""), sp.getString(kCls, ""),
                sp.getString(kId, ""), sp.getString(kLabel, ""));
        return t.valid() ? t : null;
    }

    public static void saveShortcut(Context c, Target t) {
        prefs(c).edit()
                .putString(SC_TYPE, t.type)
                .putString(SC_PKG, t.pkg)
                .putString(SC_CLS, t.cls)
                .putString(SC_ID, t.id)
                .putString(SC_LABEL, t.label)
                .apply();
    }

    public static void clearShortcut(Context c) {
        prefs(c).edit().remove(SC_TYPE).remove(SC_PKG).remove(SC_CLS)
                .remove(SC_ID).remove(SC_LABEL).apply();
    }

    /** Root启动开关，默认关。 */
    public static boolean rootForce(Context c) {
        return prefs(c).getBoolean(ROOT_FORCE, false);
    }

    public static void setRootForce(Context c, boolean value) {
        prefs(c).edit().putBoolean(ROOT_FORCE, value).apply();
    }

    /** 快捷方式只带这个 key，真实目标留在 prefs 里。 */
    public static String newLaunchKey(Context c, Target t) {
        String key = String.format(Locale.US, "%08x", new Random().nextInt());
        try {
            JSONObject o = new JSONObject();
            o.put("type", t.type);
            o.put("pkg", t.pkg);
            o.put("cls", t.cls);
            o.put("id", t.id);
            prefs(c).edit().putString(LAUNCH_PREFIX + key, o.toString()).apply();
        } catch (Exception ignored) {
        }
        return key;
    }

    public static Target launchKeyTarget(Context c, String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String raw = prefs(c).getString(LAUNCH_PREFIX + key, "");
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(raw);
            Target t = new Target(o.optString("type", TYPE_PAGE),
                    o.optString("pkg", ""), o.optString("cls", ""),
                    o.optString("id", ""), "");
            return t.valid() ? t : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 查询 ====================

    public static boolean hasRoot() {
        Shell.Result r = Shell.exec("id", 5);
        return r.out != null && r.out.contains("uid=0");
    }

    /** 页面是否存在且启用（含被禁用的组件，避免误判为已卸载）。 */
    public static boolean exists(Context c, String pkg, String cls) {
        if (pkg == null || cls == null) {
            return false;
        }
        try {
            ActivityInfo ai = c.getPackageManager().getActivityInfo(
                    new ComponentName(pkg, cls), PackageManager.MATCH_DISABLED_COMPONENTS);
            if (!ai.enabled) {
                return false;
            }
            return ai.applicationInfo == null || ai.applicationInfo.enabled;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean exists(Context c, Target t) {
        if (t == null) {
            return false;
        }
        if (t.shell()) {
            return ShellCommand.find(c, t.id) != null;
        }
        return exists(c, t.pkg, t.cls);
    }

    /** 应用本身的启用状态（disable-user 的冻结包为 false，暂停包仍为 true）。 */
    public static boolean enabled(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        try {
            ApplicationInfo ai = c.getPackageManager()
                    .getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            return ai.enabled;
        } catch (Exception e) {
            return false;
        }
    }

    /** 是否因冻结（disable-user）或暂停而暂时打不开，需要先解冻。 */
    public static boolean blocked(Context c, String pkg) {
        if (!enabled(c, pkg)) {
            return true;
        }
        // Dhizuku-only 直接查暂停真值：不看本机缓存，谁暂停的都先解了再打开
        if (DhizukuGate.only(c)) {
            return Boolean.TRUE.equals(SuspendSupport.suspendedOne(pkg));
        }
        return SuspendSupport.cached(c).contains(pkg)
                && Boolean.TRUE.equals(SuspendSupport.suspendedOne(pkg));
    }


    /** 是否对外导出，可直接用普通 Intent 启动。 */
    public static boolean exported(Context c, String pkg, String cls) {
        try {
            return c.getPackageManager()
                    .getActivityInfo(new ComponentName(pkg, cls), 0).exported;
        } catch (Exception e) {
            return false;
        }
    }

    public static String appName(Context c, String pkg) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence l = ai.loadLabel(c.getPackageManager());
            return l == null ? pkg : l.toString().trim();
        } catch (Exception e) {
            return pkg;
        }
    }

    public static String activityLabel(Context c, String pkg, String cls) {
        try {
            ActivityInfo ai = c.getPackageManager().getActivityInfo(
                    new ComponentName(pkg, cls), 0);
            CharSequence l = ai.loadLabel(c.getPackageManager());
            if (l != null && l.toString().trim().length() > 0) {
                return l.toString().trim();
            }
        } catch (Exception ignored) {
        }
        String shortName = cls == null ? "" : cls;
        int dot = shortName.lastIndexOf('.');
        return dot >= 0 ? shortName.substring(dot + 1) : shortName;
    }

    /** 与所属应用名相同就用应用名，不同则「应用名 · 页面名」。 */
    public static String displayName(Context c, String pkg, String cls) {
        String app = appName(c, pkg);
        String page = activityLabel(c, pkg, cls);
        if (page.isEmpty() || page.equalsIgnoreCase(app)) {
            return app;
        }
        return app + " · " + page;
    }

    /** 目标的显示名：自定义名优先，其次页面名 / 指令名。 */
    public static String labelOf(Context c, Target t) {
        if (t == null) {
            return "";
        }
        if (!t.label.isEmpty()) {
            return t.label;
        }
        if (t.shell()) {
            ShellCommand cmd = ShellCommand.find(c, t.id);
            return cmd == null ? "Shell 指令" : cmd.name;
        }
        return displayName(c, t.pkg, t.cls);
    }

    /** 目标副标题：页面显示组件，指令显示类型。 */
    public static String detail(Context c, Target t) {
        if (t == null) {
            return "";
        }
        if (t.shell()) {
            ShellCommand cmd = ShellCommand.find(c, t.id);
            if (cmd == null) {
                return "Shell 指令 · 已删除";
            }
            return "Shell 指令 · " + (cmd.single() ? "单条型" : "切换型");
        }
        return t.pkg + "/" + t.cls;
    }

    /** 页面图标；页面自己没声明图标时回退应用图标。 */
    public static Drawable icon(Context c, String pkg, String cls) {
        PackageManager pm = c.getPackageManager();
        try {
            ActivityInfo ai = pm.getActivityInfo(new ComponentName(pkg, cls), 0);
            if (ai.icon != 0) {
                Drawable d = ai.loadIcon(pm);
                if (d != null) {
                    return d;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            Drawable d = pm.getApplicationIcon(pkg);
            if (d != null) {
                return d;
            }
        } catch (Exception ignored) {
        }
        try {
            return pm.getDefaultActivityIcon();
        } catch (Exception e) {
            return null;
        }
    }

    public static Drawable icon(Context c, Target t) {
        if (t != null && t.shell()) {
            return ContextCompat.getDrawable(c, R.drawable.ic_shell);
        }
        return t == null ? null : icon(c, t.pkg, t.cls);
    }

    public static Bitmap bitmap(Drawable d, int sizePx) {
        if (d == null || sizePx <= 0) {
            return null;
        }
        try {
            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            d.setBounds(0, 0, sizePx, sizePx);
            d.draw(canvas);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 启动 ====================

    /** 按目标类型分派：页面走 Intent / su，Shell 指令直接执行。 */
    public static int launch(Context c, Target t) {
        if (t == null || !t.valid()) {
            return NO_TARGET;
        }
        if (t.shell()) {
            ShellCommand cmd = ShellCommand.find(c, t.id);
            if (cmd == null) {
                return NO_TARGET;
            }
            ShellCommand.Result r = ShellCommand.run(c, cmd);
            if (r.needRoot) {
                return NEED_ROOT;
            }
            return r.ok ? OK : FAILED;
        }
        return launch(c, t.pkg, t.cls);
    }

    /**
     * 导出页面直接启动；未导出页面在 Root启动打开且有 root 时走
     * `su -c am start -n pkg/cls`。
     */
    public static int launch(Context c, String pkg, String cls) {
        if (!exists(c, pkg, cls)) {
            return NO_TARGET;
        }
        ComponentName cn = new ComponentName(pkg, cls);
        if (exported(c, pkg, cls)) {
            try {
                c.startActivity(new Intent().setComponent(cn)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return OK;
            } catch (Exception ignored) {
                // 导出页面也可能被系统限制，继续尝试 root 通道
            }
        }
        if (!rootForce(c) || !hasRoot()) {
            return NEED_ROOT;
        }
        Shell.Result r = Shell.exec("am start -n " + pkg + "/" + cls, 10);
        String out = r.out == null ? "" : r.out;
        if (out.contains("Error") || out.contains("Exception") || r.code != 0) {
            return FAILED;
        }
        return OK;
    }

    /** 启动失败时的提示文案。 */
    public static String problemText(int result) {
        if (result == NO_TARGET) {
            return "目标已不存在";
        }
        if (result == NEED_ROOT) {
            return "该页面未导出，需要打开「Root启动」并有 root 权限";
        }
        if (result == FAILED) {
            return "启动失败，请检查 root 授权";
        }
        return "";
    }
}
