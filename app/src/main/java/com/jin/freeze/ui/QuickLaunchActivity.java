package com.jin.freeze.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;

import com.jin.freeze.core.DhizukuGate;
import com.jin.freeze.core.LaunchTarget;
import com.jin.freeze.core.PrivShell;
import com.jin.freeze.core.SuspendSupport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 桌面快捷方式的启动中转：快捷方式 Intent 只带 key，这里取回真实目标再启动。
 * 导出页面直接 startActivity，未导出页面按「root 强启」开关走 su。
 * 「一键冻结 / 一键解冻」立刻 finish 让出 top 后台执行：凚 占据 top 会延迟桌面的
 * 图标塌缩 relayout，把桌面滑动吞掉；结果提示按 Hail 的做法先在前台弹 Toast 再 finish。
 */
public class QuickLaunchActivity extends Activity {

    public static final String EXTRA_KEY = "key";
    /** 静态/动态快捷方式：打开冻结列表 / 对名单整批冻结 / 整批解冻。 */
    public static final String ACTION_OPEN_FREEZE_LIST = "com.jin.freeze.action.OPEN_FREEZE_LIST";
    public static final String ACTION_FREEZE_ALL = "com.jin.freeze.action.FREEZE_ALL";
    public static final String ACTION_UNFREEZE_ALL = "com.jin.freeze.action.UNFREEZE_ALL";

    /** 与列表一致：勾了「去掉 toast 提示」后不再弹任何短提示。 */
    private void toast(CharSequence text) {
        if (getSharedPreferences("freeze_list", MODE_PRIVATE)
                .getBoolean("hide_toasts", false)) {
            return;
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String action = getIntent() == null ? null : getIntent().getAction();
        if (ACTION_FREEZE_ALL.equals(action) || ACTION_UNFREEZE_ALL.equals(action)) {
            // 落地壳缩成左上角 1×1：不铺屏、不挡滑、不在系统栏铺黑格；
            // 提示由 TYPE_TOAST 窗口负责，不依赖这个 activity 是否前台
            getWindow().setLayout(1, 1);
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            android.view.WindowManager.LayoutParams wlp = getWindow().getAttributes();
            wlp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            getWindow().setAttributes(wlp);
            // 先弹提示，整批在后台线程跑，完成后收起提示并结束落地壳
            handleBatchAction(ACTION_FREEZE_ALL.equals(action));
            return;
        }
        if (ACTION_OPEN_FREEZE_LIST.equals(action)) {
            // 快捷方式打开的是半屏形态；勾了「隐藏后台」就走带 excludeFromRecents 的入口
            boolean hideRecents = getSharedPreferences("freeze_list", MODE_PRIVATE)
                    .getBoolean("hide_recents", false);
            startActivity(new Intent(this,
                    hideRecents ? FreezePanelHiddenActivity.class : FreezePanelActivity.class)
                    // 从快捷方式进列表也走一次权限检查：Shizuku 未授权时自动申请，
                    // 否则用户只从快捷方式进来会一直不在 Shizuku 的授权列表里
                    .putExtra(FreezeListActivity.EXTRA_CHECK_PRIV, true)
                    // 与桌面图标入口一致：半屏是透明窗口，先清掉旧任务里残留的全屏页面
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }
        if (ShortcutPin.ACTION_LAUNCH.equals(action)) {
            launchApp(getIntent().getStringExtra(ShortcutPin.KEY_PACKAGE));
            return;
        }
        String key = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_KEY);
        LaunchTarget.Target t = LaunchTarget.launchKeyTarget(this, key);
        if (t == null) {
            toast("目标已不存在");
            finish();
            return;
        }
        // 冻结/暂停状态下的应用：先走列表同一套解冻通道，再正常打开
        if (!t.shell() && LaunchTarget.blocked(this, t.pkg)) {
            thawAndLaunch(t);
            return;
        }
        finishWith(t);
    }

    /** 纯包名：兼容快捷方式里的「pkg@999」分身实例键。 */
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

    /** 解冻主用户的一个包：走 SuspendSupport（pm + CLI 兜底），Dhizuku-only 走取消暂停。 */
    private void thawMain(String pkg) {
        if (DhizukuGate.only(this)) {
            SuspendSupport.unsuspend(pkg);
        } else {
            // pm unsuspend 只能撤 pm 自己建的暂停；凚的 root 通道/锁屏/划卡建的是
            // suspender=com.android.shell，必须由 SuspendSupport 按真值再用 CLI 撤一次
            SuspendSupport.unsuspend(pkg);
            PrivShell.exec("pm enable --user 0 " + pkg, 30);
        }
    }

    /** 照搬 Hail 的 launchApp：冻结先解冻，再用系统启动入口拉起；分身按自己的用户启动。 */
    private void launchApp(String key) {
        final String pkg = packagePart(key);
        final int userId = userIdPart(key);
        if (pkg == null || !pkg.matches("[A-Za-z0-9._]+")) {
            toast("该应用没有可启动的界面");
            finish();
            return;
        }
        if (userId != 0) {
            launchCloneApp(pkg, userId);
            return;
        }
        new Thread(() -> {
            if (!LaunchTarget.enabled(this, pkg)) {
                thawMain(pkg);
                for (int i = 0; i < 15 && !LaunchTarget.enabled(this, pkg); i++) {
                    SystemClock.sleep(120);
                }
            } else if (LaunchTarget.blocked(this, pkg)) {
                thawMain(pkg);
            }
            runOnUiThread(() -> {
                Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch == null) {
                    toast("该应用没有可启动的界面");
                    finish();
                    return;
                }
                startActivity(launch);
                finish();
            });
        }, "jin-launch-app").start();
    }

    /** 分身快捷方式：在它自己的用户空间撤销暂停/启用，再用 am start --user 拉起。 */
    private void launchCloneApp(String pkg, int userId) {
        new Thread(() -> {
            // 分身必须借 root / Shizuku 才能跨用户启动，没授权时给出明确提示而不是静默退出
            if (!PrivShell.available()) {
                runOnUiThread(() -> {
                    toast("打开分身需要 Root 或 Shizuku 权限，请先打开凚完成授权");
                    finish();
                });
                return;
            }
            // 分身被暂停时同样记在 com.android.shell 名下，pm 撤不掉：先按 CLI 真撤一次
            SuspendSupport.cliBatch(this, java.util.Collections.singletonList(pkg), null, false, userId);
            PrivShell.exec("pm unsuspend --user " + userId + " " + pkg
                    + "; pm enable --user " + userId + " " + pkg, 30);
            // 主应用被冻结时 getLaunchIntentForPackage 会返回 null，不能作为分身能否启动的依据：
            // 改成在分身自己的用户下解析启动组件
            String comp = resolveCloneComponent(pkg, userId);
            if (comp == null) {
                runOnUiThread(() -> {
                    toast("该应用没有可启动的界面");
                    finish();
                });
                return;
            }
            PrivShell.Result start = PrivShell.exec("am start --user " + userId + " -n "
                    + comp, 15);
            boolean failed = start == null || start.timeout
                    || (start.out != null && start.out.contains("Error"));
            runOnUiThread(() -> {
                if (failed) {
                    toast("分身启动失败，请确认凚的 Root / Shizuku 权限已授权");
                }
                finish();
            });
        }, "jin-launch-clone").start();
    }

    /** 在分身用户下解析启动组件（「包/类」）；解析不到返回 null。 */
    private String resolveCloneComponent(String pkg, int userId) {
        // 注意必须带 -a/-c/-p：resolve-activity 的位置参数是 URI，不是包名
        PrivShell.Result r = PrivShell.exec("cmd package resolve-activity --brief --user "
                + userId + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "
                + pkg + " | tail -n 1", 10);
        String out = r.out == null ? "" : r.out.trim();
        if (out.contains("/")) {
            return out;
        }
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        return i == null || i.getComponent() == null ? null
                : i.getComponent().flattenToShortString();
    }

    private void finishWith(LaunchTarget.Target t) {
        int result = LaunchTarget.launch(this, t);
        if (result != LaunchTarget.OK) {
            toast(LaunchTarget.problemText(result));
        }
        finish();
    }

    /** 与列表里的解冻一致：先撤暂停再补 enable，确认启用后再启动；全程不在主线程跑 su。 */
    private void thawAndLaunch(LaunchTarget.Target t) {
        if (t.pkg == null || !t.pkg.matches("[A-Za-z0-9._]+")) {
            toast("目标已不存在");
            finish();
            return;
        }
        new Thread(() -> {
            thawMain(t.pkg);
            boolean ready = false;
            for (int i = 0; i < 15; i++) {
                if (LaunchTarget.enabled(this, t.pkg)) {
                    ready = true;
                    break;
                }
                SystemClock.sleep(120);
            }
            final boolean ok = ready;
            runOnUiThread(() -> {
                if (ok) {
                    finishWith(t);
                } else {
                    toast("解冻失败，无法打开");
                    finish();
                }
            });
        }, "jin-thaw-launch").start();
    }

    /**
     * 长按一键冻结/解冻：先弹出提示并一直显示，整批跑完（至少显示 1.2 秒）再收起。
     * 提示是 TYPE_TOAST 窗口，不需要悬浮窗权限；落地壳立刻结束，桌面马上可滑，
     * 中途滑动屏幕也不会把提示压掉。
     */
    private void handleBatchAction(final boolean freeze) {
        final long start = SystemClock.uptimeMillis();
        // 先弹提示：整批期间一直显示，跑完再收起
        final FrozenTip.Tip tip = FrozenTip.showToastWindow(this,
                freeze ? "已全部冻结" : "已全部解冻");
        // 立刻结束落地壳：桌面马上可滑，提示窗口由进程独立承载，不受影响
        finish();
        overridePendingTransition(0, 0);
        new Thread(() -> {
            String result;
            try {
                result = setListFrozen(freeze);
            } catch (Throwable t) {
                result = null;
            }
            final String r = result;
            final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            if (r == null) {
                main.post(() -> tip.setText(freeze ? "冻结失败" : "解冻失败"));
            }
            long shown = SystemClock.uptimeMillis() - start;
            long wait = Math.max(0, 1200 - shown);
            main.postDelayed(tip::dismiss, wait);
        }, "shortcut-batch").start();
    }

    /**
     * 桌面快捷方式的一键冻结 / 一键解冻：整份名单全量执行。
     * 不看白名单、不看标签页 / 搜索框；分身实例键（pkg@999 等）按各自 user 执行；
     * 冻结方式跟随「冻结后是否保留桌面图标」：保留时优先 pm suspend，
     * 否则 disable-user，被 ROM 拦住停用时再降级 suspend。
     * 返回值语义照搬 Hail：null = 一个都没成功；1 个 = 应用名；多个 = 数量字符串。
     */
    private String setListFrozen(boolean freeze) {
        SharedPreferences sp = getSharedPreferences("freeze_list", MODE_PRIVATE);
        Set<String> pkgs = new HashSet<>(sp.getStringSet("pkgs", new HashSet<>()));
        boolean keepIcon = sp.getBoolean("keep_launcher_icon", false);
        // 用户标记隐藏的实例键：快捷方式冻结/解冻都不动它们；冻结时改走隐藏
        Set<String> hiddenKeys = com.jin.freeze.core.HideSupport.hiddenIntentKeys(this);
        PackageManager pm = getPackageManager();
        List<String> mainPkgs = new ArrayList<>();
        List<String> cloneTodo = new ArrayList<>(); // 分身实例键 pkg@N
        for (String key : pkgs) {
            String pkg = packagePart(key);
            if (pkg == null || !pkg.matches("[A-Za-z0-9._]+")) {
                continue;
            }
            if (userIdPart(key) != 0) {
                cloneTodo.add(key);
            } else {
                mainPkgs.add(pkg);
            }
        }
        // 暂停态对「冻结」来说等同于已到位；对「解冻」来说必须再撤一次暂停。
        // 这里查真值而不是读缓存：快捷方式可能在别的进程/版本里暂停过，缓存会漏。
        Set<String> suspendedBefore = SuspendSupport.suspendedAmong(mainPkgs);
        List<String> todo = new ArrayList<>();      // 主用户包名
        for (String pkg : mainPkgs) {
            if (hiddenKeys.contains(pkg)) {
                // 隐藏项：冻结时恢复隐藏（不再停用/暂停），解冻时保持不动
                if (freeze) {
                    com.jin.freeze.core.HideSupport.hide(this, pkg, 0);
                }
                continue;
            }
            boolean suspended = suspendedBefore.contains(pkg);
            // 已经是目标状态的不再执行 pm：重复整批调用是原来耗时的主要来源之一
            Boolean enabled = enabledOf(pm, pkg);
            if (freeze ? (suspended || (enabled != null && !enabled))
                    : (enabled != null && enabled && !suspended)) {
                continue;
            }
            todo.add(pkg);
        }
        // 分身也按真值跳过已到位的；读不到状态时照常执行（pm 本身幂等）
        Map<String, boolean[]> cloneBefore = cloneStates(cloneTodo);
        List<String> cloneRun = new ArrayList<>();
        for (String key : cloneTodo) {
            if (hiddenKeys.contains(key)) {
                if (freeze) {
                    com.jin.freeze.core.HideSupport.hide(this, packagePart(key), userIdPart(key));
                }
                continue;
            }
            boolean[] st = cloneBefore.get(key);
            if (st != null) {
                boolean installed = st[0];
                boolean enabled = st[1];
                boolean suspended = st[2];
                if (!installed) {
                    continue;
                }
                if (freeze ? (!enabled || suspended) : (enabled && !suspended)) {
                    continue;
                }
            }
            cloneRun.add(key);
        }
        // Dhizuku 只作用 user 0：分身实例跳过，主用户逐包走暂停/取消暂停
        final boolean dhizuku = DhizukuGate.only(this);
        if (dhizuku) {
            cloneRun.clear();
        }
        if (todo.isEmpty() && cloneRun.isEmpty()) {
            // 与 Hail 同语义：没有需要处理的应用 → i=0 → 提示「已冻结：0」
            return "0";
        }
        // 一次 su + 限并发跑完整批：pm 每条都要拉起一个 app_process，
        // 串行几十条是秒级；并发 8 路能把整批压到一秒左右
        StringBuilder cmd = new StringBuilder();
        // 保留桌面图标时暂停必须走 CLI（suspender=com.android.shell）：
        // root 下 pm suspend 的身份是 root，会把暂停记成不存在的包名，点灰图标时系统界面崩溃
        boolean cliSuspend = freeze && keepIcon && PrivShell.mode() == PrivShell.Mode.ROOT;
        if (!todo.isEmpty() && !dhizuku && !cliSuspend) {
            cmd.append("i=0; for p in");
            for (String p : todo) {
                cmd.append(" '").append(p).append("'");
            }
            cmd.append("; do ")
                    .append(freeze
                            ? (keepIcon
                            ? "pm suspend --user 0 \"$p\" >/dev/null 2>&1 & "
                            : "pm disable-user --user 0 \"$p\" >/dev/null 2>&1 & ")
                            : "pm unsuspend --user 0 \"$p\" >/dev/null 2>&1; "
                            + "pm enable --user 0 \"$p\" >/dev/null 2>&1 & ")
                    .append("i=$((i+1)); ")
                    .append("[ $((i%8)) -eq 0 ] && wait; done; wait; ");
        }
        // 分身：一律停用，不跟「保留桌面图标」走暂停通道。
        // 部分 ROM（MIUI 双开）会拒绝给 user 999 暂停，批量里又没有弹框可确认，
        // 于是主用户冻上、分身一个不少全漏掉；停用各版本都放行，且与列表里单冻分身一致。
        for (String key : cloneRun) {
            String pkg = packagePart(key);
            int userId = userIdPart(key);
            cmd.append(freeze
                            ? "pm disable-user --user " + userId
                            : "pm unsuspend --user " + userId + "; pm enable --user " + userId)
                    .append(" ").append(pkg).append(" >/dev/null 2>&1 & ");
        }
        if (!cloneRun.isEmpty()) {
            cmd.append("wait");
        }
        // 保留桌面图标 + root：整批交给同一个 CLI 进程暂停（一个进程处理完整份名单）
        if (cliSuspend) {
            java.util.Map<String, String> labels = new java.util.HashMap<>();
            for (String p : todo) {
                labels.put(p, labelOf(pm, p));
            }
            SuspendSupport.cliBatch(this, todo, labels, true);
        }
        PrivShell.Result result = cmd.length() == 0 ? null : PrivShell.exec(cmd.toString(), 120);
        if (!freeze && !todo.isEmpty()) {
            // 主用户暂停是 com.android.shell 身份，pm unsuspend 静默失败，按真值再用 CLI 撤一次
            SuspendSupport.unsuspendBatch(this, todo);
        }
        if (!freeze && !cloneRun.isEmpty()) {
            // 分身同理：按各自的用户用一个 CLI 进程撤销暂停
            java.util.Map<Integer, List<String>> byUser = new java.util.HashMap<>();
            for (String key : cloneRun) {
                int uid = userIdPart(key);
                List<String> group = byUser.get(uid);
                if (group == null) {
                    group = new ArrayList<>();
                    byUser.put(uid, group);
                }
                group.add(packagePart(key));
            }
            for (java.util.Map.Entry<Integer, List<String>> e : byUser.entrySet()) {
                SuspendSupport.cliBatch(this, e.getValue(), null, false, e.getKey());
            }
        }
        if (dhizuku) {
            // 没有 shell：主用户逐包走 Dhizuku 暂停/取消暂停
            for (String p : todo) {
                if (freeze) {
                    SuspendSupport.suspend(this, p, labelOf(pm, p));
                } else {
                    SuspendSupport.unsuspend(p);
                }
            }
        }
        // 执行完统一核对真实状态：既拿失败数，也不依赖 pm 的文本输出
        Set<String> suspendedAfter = SuspendSupport.suspendedAmong(todo);
        if (freeze) {
            // 停用被 ROM 拦住（仍可启用且未进入暂停）的包，自动改走 pm suspend
            List<String> retry = new ArrayList<>();
            for (String pkg : todo) {
                Boolean enabled = enabledOf(pm, pkg);
                if (enabled != null && enabled && !suspendedAfter.contains(pkg)) {
                    retry.add(pkg);
                }
            }
            if (!retry.isEmpty()) {
                // 逐包走 SuspendSupport：有 Shizuku 时用 SuspendDialogInfo 定制提示框，
                // 没有时退回带 --dialogMessage 的 pm suspend
                for (String p : retry) {
                    SuspendSupport.suspend(this, p, labelOf(pm, p));
                }
                Set<String> again = SuspendSupport.suspendedAmong(retry);
                if (!again.isEmpty()) {
                    suspendedAfter.addAll(again);
                }
            }
            // 把真正进入暂停的包记进缓存，列表刷新与下次解冻都靠它
            SuspendSupport.remember(this, suspendedAfter);
        } else {
            Set<String> next = SuspendSupport.cached(this);
            next.removeAll(todo);
            next.addAll(suspendedAfter);
            SuspendSupport.replace(this, next);
        }
        String out = result == null || result.out == null ? "" : result.out.trim();
        if (!out.isEmpty()) {
            android.util.Log.w("JinPm", "shortcut batch " + (freeze ? "freeze" : "unfreeze")
                    + " raw output:\n" + out);
        }
        // 与 Hail 一致：统计真正生效的个数，1 个时提示应用名
        int ok = 0;
        String name = "";
        for (String pkg : todo) {
            Boolean enabled = enabledOf(pm, pkg);
            boolean done = freeze
                    ? ((enabled != null && !enabled) || suspendedAfter.contains(pkg))
                    : (enabled != null && enabled && !suspendedAfter.contains(pkg));
            if (done) {
                ok++;
                name = labelOf(pm, pkg);
            }
        }
        if (!cloneRun.isEmpty()) {
            Map<String, boolean[]> cloneAfter = cloneStates(cloneRun);
            if (freeze) {
                // 并发那一刀偶尔会被系统拒一次：还处于启用态的分身单独补冻一遍再核对，
                // 否则主用户成功、分身漏冻也会被提示成「已全部冻结」
                List<String> stuck = new ArrayList<>();
                for (String key : cloneRun) {
                    boolean[] st = cloneAfter.get(key);
                    if (st != null && st[0] && st[1] && !st[2]) {
                        stuck.add(key);
                    }
                }
                if (!stuck.isEmpty()) {
                    StringBuilder retry = new StringBuilder();
                    for (String key : stuck) {
                        retry.append("pm disable-user --user ").append(userIdPart(key))
                                .append(" ").append(packagePart(key))
                                .append(" >/dev/null 2>&1; ");
                    }
                    PrivShell.exec(retry.toString(), 60);
                    cloneAfter = cloneStates(cloneRun);
                }
            }
            for (String key : cloneRun) {
                boolean[] st = cloneAfter.get(key);
                boolean done;
                if (st == null) {
                    // 查不到状态时不误报失败：命令已经执行过，pm 本身幂等
                    done = true;
                } else {
                    done = freeze ? (!st[1] || st[2]) : (st[1] && !st[2]);
                }
                if (done) {
                    ok++;
                    name = labelOf(pm, packagePart(key));
                }
            }
        }
        if (ok == 0) {
            return null;
        }
        return ok == 1 ? name : String.valueOf(ok);
    }

    /**
     * 批量读分身实例的真实状态：实例键 → {是否已安装, 是否启用, 是否暂停}。
     * 读 dumpsys package 里对应「User N:」那一行；读不到的键不出现在返回值里。
     */
    private Map<String, boolean[]> cloneStates(List<String> keys) {
        Map<String, boolean[]> out = new java.util.HashMap<>();
        if (keys == null || keys.isEmpty()) {
            return out;
        }
        StringBuilder cmd = new StringBuilder();
        for (String key : keys) {
            String pkg = packagePart(key);
            int userId = userIdPart(key);
            if (pkg == null) {
                continue;
            }
            cmd.append("echo -n '").append(key).append(" '; ")
                    .append("dumpsys package ").append(pkg)
                    .append(" 2>/dev/null | grep -m1 'User ").append(userId).append(":' || true; ");
        }
        if (cmd.length() == 0) {
            return out;
        }
        PrivShell.Result r = PrivShell.exec(cmd.toString(), 40);
        if (r.out == null) {
            return out;
        }
        for (String line : r.out.split("\n")) {
            int sp = line.indexOf(' ');
            if (sp <= 0) {
                continue;
            }
            String key = line.substring(0, sp).trim();
            if (!line.contains("User ")) {
                continue;
            }
            boolean installed = line.contains("installed=true");
            // PackageUserState.enabled：0=默认、1=启用；2/3/4 都是停用态
            boolean enabled = line.contains("enabled=0") || line.contains("enabled=1");
            boolean suspended = line.contains("suspended=true");
            out.put(key, new boolean[]{installed, enabled, suspended});
        }
        return out;
    }

    private String labelOf(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** null 表示查不到，交给 pm 执行并按结果判定成败。 */
    private Boolean enabledOf(PackageManager pm, String pkg) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return info.enabled;
        } catch (Exception e) {
            return null;
        }
    }
}
