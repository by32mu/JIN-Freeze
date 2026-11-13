package com.jin.freeze.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 用户自定义的 Shell 指令。
 * 切换型：开/关两条命令，列表用开关，点一下执行对应那条并翻转状态；
 * 单条型：只有一条命令，点一次执行一次。
 * 数据存在 prefs 的 JSONArray 里，与工程其它自定义列表一致。
 */
public final class ShellCommand {

    public static final int MODE_TOGGLE = 0;
    public static final int MODE_SINGLE = 1;

    /** 输出截断上限，避免几 MB 的日志撑爆界面。 */
    private static final int MAX_OUTPUT = 8000;

    private static final String PREFS = "shell_commands";
    private static final String KEY_LIST = "list";

    public String id = "";
    public String name = "";
    public int mode = MODE_TOGGLE;
    public String onCommand = "";
    public String offCommand = "";
    public boolean useRoot;
    /** 执行成功时不弹任何提示（失败仍报错）。 */
    public boolean silent;
    /** 仅切换型：当前是否处于「开」。 */
    public boolean state;

    public boolean single() {
        return mode == MODE_SINGLE;
    }

    /** 保存前校验；返回 null 表示可以保存。 */
    public String problem() {
        if (name.trim().isEmpty()) {
            return "名称不能为空";
        }
        if (onCommand.trim().isEmpty()) {
            return single() ? "命令不能为空" : "开命令不能为空";
        }
        if (!single() && offCommand.trim().isEmpty()) {
            return "关命令不能为空";
        }
        return null;
    }

    /** 列表副标题：当前状态 + 当前会执行的那条命令。 */
    public String summary() {
        String tail = silent ? " · 静默" : "";
        if (single()) {
            return "单条命令 · " + firstLine(onCommand) + tail;
        }
        return (state ? "已开启" : "已关闭") + " · " + firstLine(state ? onCommand : offCommand) + tail;
    }

    private static String firstLine(String s) {
        String t = s == null ? "" : s.trim();
        int nl = t.indexOf('\n');
        if (nl >= 0) {
            t = t.substring(0, nl);
        }
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    // ==================== 存取 ====================

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<ShellCommand> list(Context c) {
        List<ShellCommand> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                ShellCommand cmd = new ShellCommand();
                cmd.id = o.optString("id", "");
                cmd.name = o.optString("name", "");
                cmd.mode = o.optInt("mode", MODE_TOGGLE);
                cmd.onCommand = o.optString("on", "");
                cmd.offCommand = o.optString("off", "");
                cmd.useRoot = o.optBoolean("root", false);
                cmd.silent = o.optBoolean("silent", false);
                cmd.state = o.optBoolean("state", false);
                if (!cmd.id.isEmpty()) {
                    out.add(cmd);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void save(Context c, List<ShellCommand> list) {
        JSONArray arr = new JSONArray();
        for (ShellCommand cmd : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", cmd.id);
                o.put("name", cmd.name);
                o.put("mode", cmd.mode);
                o.put("on", cmd.onCommand);
                o.put("off", cmd.offCommand);
                o.put("root", cmd.useRoot);
                o.put("silent", cmd.silent);
                o.put("state", cmd.state);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        prefs(c).edit().putString(KEY_LIST, arr.toString()).apply();
    }

    public static ShellCommand find(Context c, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (ShellCommand cmd : list(c)) {
            if (id.equals(cmd.id)) {
                return cmd;
            }
        }
        return null;
    }

    /** 按 id 覆盖，找不到就追加到末尾。 */
    public static void upsert(Context c, ShellCommand cmd) {
        List<ShellCommand> all = list(c);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(cmd.id)) {
                all.set(i, cmd);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            all.add(cmd);
        }
        save(c, all);
    }

    public static void remove(Context c, String id) {
        List<ShellCommand> all = list(c);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                all.remove(i);
                break;
            }
        }
        save(c, all);
    }

    public static String newId() {
        return String.format(Locale.US, "%08x", new Random().nextInt());
    }

    // ==================== 执行 ====================

    public static final class Result {
        public boolean ok;
        public int code = -1;
        public String command = "";
        public String out = "";
        /** 勾了 root 但当前没有 root 权限，未执行。 */
        public boolean needRoot;
        public boolean timedOut;
    }

    /**
     * 执行一次：切换型按当前状态跑开/关命令，成功后才翻转状态并落盘。
     * 勾了 root 但没有 root 权限时直接返回 needRoot，不动状态。
     */
    public static Result run(Context c, ShellCommand cmd) {
        Result r = new Result();
        if (cmd == null) {
            return r;
        }
        String command = cmd.single() ? cmd.onCommand
                : (cmd.state ? cmd.offCommand : cmd.onCommand);
        r.command = command == null ? "" : command.trim();
        if (r.command.isEmpty()) {
            return r;
        }
        if (cmd.useRoot && !LaunchTarget.hasRoot()) {
            r.needRoot = true;
            return r;
        }
        int timeout = cmd.single() ? 60 : 30;
        Shell.Result sr = cmd.useRoot
                ? Shell.exec(r.command, timeout) : Shell.execPlain(r.command, timeout);
        r.code = sr.code;
        r.timedOut = sr.timeout;
        r.out = truncate(sr.out);
        r.ok = sr.code == 0;
        if (r.ok && !cmd.single()) {
            cmd.state = !cmd.state;
            upsert(c, cmd);
        }
        return r;
    }

    private static String truncate(String out) {
        String s = out == null ? "" : out;
        if (s.length() <= MAX_OUTPUT) {
            return s;
        }
        return s.substring(0, MAX_OUTPUT) + "\n…（输出过长，已截断）";
    }
}
