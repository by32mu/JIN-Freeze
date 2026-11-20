package com.jin.freeze.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

import com.jin.freeze.core.LaunchTarget;
import com.jin.freeze.core.PrivShell;
import com.jin.freeze.core.SuspendSupport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 桌面快捷方式的启动中转：快捷方式 Intent 只带 key，这里取回真实目标再启动。
 * 导出页面直接 startActivity，未导出页面按「root 强启」开关走 su。
 * 「一键冻结 / 一键解冻」立刻 finish 让出 top 后台执行：凚 占据 top 会延迟桌面的
 * 图标塌缩 relayout，把桌面滑动吞掉；结果提示走 FrozenTip 悬浮胶囊，后台也能上屏。
 */
public class QuickLaunchActivity extends Activity {

    public static final String EXTRA_KEY = "key";
    /** 静态/动态快捷方式：打开冻结列表 / 对名单整批冻结 / 整批解冻。 */
    public static final String ACTION_OPEN_FREEZE_LIST = "com.jin.freeze.action.OPEN_FREEZE_LIST";
    public static final String ACTION_FREEZE_ALL = "com.jin.freeze.action.FREEZE_ALL";
    public static final String ACTION_UNFREEZE_ALL = "com.jin.freeze.action.UNFREEZE_ALL";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String action = getIntent() == null ? null : getIntent().getAction();
        if (ACTION_FREEZE_ALL.equals(action) || ACTION_UNFREEZE_ALL.equals(action)) {
            // 批量期间不再让凚 占住前台占位：凚 占据 top 会延迟桌面的图标塌缩 relayout，
            // 把桌面滑动吞掉（真机实测）。改成起线程后立刻 finish，桌面马上恢复可滑；
            // 结果提示走 FrozenTip 悬浮胶囊，后台也能上屏，不必为等 toast 上屏而保持窗口。
            boolean freeze = ACTION_FREEZE_ALL.equals(action);
            if (Settings.canDrawOverlays(this)) {
                runBatch(freeze);
            } else {
                // 没有悬浮窗权限：后台的结果胶囊上不了屏。先等 JinApp 的 appops 自授予
                // 落地（进程刚起时可能还没跑完），仍然没有就弹框引导去开启。
                runBatchAfterPermissionCheck(freeze);
            }
            return;
        }
        if (ACTION_OPEN_FREEZE_LIST.equals(action)) {
            // 快捷方式打开的是半屏形态；勾了「隐藏后台」就走带 excludeFromRecents 的入口
            boolean hideRecents = getSharedPreferences("freeze_list", MODE_PRIVATE)
                    .getBoolean("panel_hide_recents", false);
            startActivity(new Intent(this,
                    hideRecents ? FreezePanelHiddenActivity.class : FreezePanelActivity.class)
                    // 从快捷方式进列表也走一次权限检查：Shizuku 未授权时自动申请，
                    // 否则用户只从快捷方式进来会一直不在 Shizuku 的授权列表里
                    .putExtra(FreezeListActivity.EXTRA_CHECK_PRIV, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
            Toast.makeText(this, "目标已不存在", Toast.LENGTH_SHORT).show();
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

    /** 照搬 Hail 的 launchApp：冻结先解冻，再用系统启动入口拉起；分身按自己的用户启动。 */
    private void launchApp(String key) {
        final String pkg = packagePart(key);
        final int userId = userIdPart(key);
        if (pkg == null || !pkg.matches("[A-Za-z0-9._]+")) {
            Toast.makeText(this, "该应用没有可启动的界面", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (userId != 0) {
            launchCloneApp(pkg, userId);
            return;
        }
        new Thread(() -> {
            if (!LaunchTarget.enabled(this, pkg)) {
                PrivShell.exec("pm unsuspend --user 0 " + pkg + "; pm enable --user 0 " + pkg, 30);
                for (int i = 0; i < 15 && !LaunchTarget.enabled(this, pkg); i++) {
                    SystemClock.sleep(120);
                }
            } else if (LaunchTarget.blocked(this, pkg)) {
                PrivShell.exec("pm unsuspend --user 0 " + pkg, 30);
            }
            runOnUiThread(() -> {
                Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch == null) {
                    Toast.makeText(this, "该应用没有可启动的界面", Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(this, "打开分身需要 Root 或 Shizuku 权限，请先打开凚完成授权",
                            Toast.LENGTH_LONG).show();
                    finish();
                });
                return;
            }
            PrivShell.exec("pm unsuspend --user " + userId + " " + pkg
                    + "; pm enable --user " + userId + " " + pkg, 30);
            // 主应用被冻结时 getLaunchIntentForPackage 会返回 null，不能作为分身能否启动的依据：
            // 改成在分身自己的用户下解析启动组件
            String comp = resolveCloneComponent(pkg, userId);
            if (comp == null) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "该应用没有可启动的界面", Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(this, "分身启动失败，请确认凚的 Root / Shizuku 权限已授权",
                            Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, LaunchTarget.problemText(result), Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    /** 与列表里的解冻一致：先撤暂停再补 enable，确认启用后再启动；全程不在主线程跑 su。 */
    private void thawAndLaunch(LaunchTarget.Target t) {
        if (t.pkg == null || !t.pkg.matches("[A-Za-z0-9._]+")) {
            Toast.makeText(this, "目标已不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        new Thread(() -> {
            PrivShell.exec("pm unsuspend --user 0 " + t.pkg + "; pm enable --user 0 " + t.pkg, 30);
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
                    Toast.makeText(this, "解冻失败，无法打开", Toast.LENGTH_SHORT).show();
                    finish();
                }
            });
        }, "jin-thaw-launch").start();
    }

    /**
     * 一键冻结 / 一键解冻。
     * 批量在后台线程跑，但这里立刻 finish 让出 top——凚 占据 top 会延迟桌面的图标塌缩
     * relayout，把桌面滑动吞掉；结果提示走 FrozenTip 悬浮胶囊，后台也能上屏，无需保持窗口。
     */
    private void runBatch(final boolean freeze) {
        new Thread(() -> doBatch(freeze), "shortcut-batch").start();
        // 立即让出 top：桌面马上恢复可滑动。批量在线程里继续跑，完成后 FrozenTip 悬浮胶囊上屏。
        finish();
        overridePendingTransition(0, 0);
    }

    /** 没有悬浮窗权限时的批量入口：等自授予落地，仍没有就引导用户去开，开了再执行。 */
    private void runBatchAfterPermissionCheck(final boolean freeze) {
        new Thread(() -> {
            boolean ok = Settings.canDrawOverlays(this);
            // 进程刚起时 JinApp 的 appops 自授予可能还没跑完：最多重试 1 秒
            for (int i = 0; i < 10 && !ok; i++) {
                SystemClock.sleep(100);
                ok = Settings.canDrawOverlays(this);
            }
            if (!ok) {
                runOnUiThread(() -> promptOverlayPermission(freeze));
                return;
            }
            doBatch(freeze);
            runOnUiThread(() -> {
                finish();
                overridePendingTransition(0, 0);
            });
        }, "shortcut-batch-perm").start();
    }

    /** 长按一键冻结/解冻没有悬浮窗权限：提示去开启，否则桌面看不到任何结果提示。 */
    private void promptOverlayPermission(boolean freeze) {
        new AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("长按图标一键" + (freeze ? "冻结" : "解冻")
                        + "时，凚 要用悬浮窗在桌面显示「已全部" + (freeze ? "冻结" : "解冻")
                        + "」提示。请为 凚 开启「显示在其他应用上层」权限后重试。")
                .setPositiveButton("去开启", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) {
                    }
                    finish();
                    overridePendingTransition(0, 0);
                })
                .setNegativeButton("取消", (d, w) -> {
                    finish();
                    overridePendingTransition(0, 0);
                })
                .setCancelable(false)
                .show();
    }

    private void doBatch(boolean freeze) {
        android.content.SharedPreferences sp = getSharedPreferences("freeze_list", MODE_PRIVATE);
        Set<String> pkgs = new HashSet<>(sp.getStringSet("pkgs", new HashSet<>()));
        if (pkgs.isEmpty()) {
            FrozenTip.showCapsule(this, "冻结名单为空");
            return;
        }
        // 冻结白名单：快捷方式的一键冻结同样不处理白名单里的应用
        Set<String> whitelist = freeze ? sp.getStringSet("freeze_whitelist", null) : null;
        PackageManager pm = getPackageManager();
        // 暂停态对「冻结」来说等同于已到位；对「解冻」来说必须再撤一次暂停
        Set<String> suspendedBefore = SuspendSupport.suspendedAmong(SuspendSupport.cached(this));
        List<String> todo = new ArrayList<>();
        boolean allWhitelisted = whitelist != null;
        for (String pkg : pkgs) {
            if (whitelist != null && whitelist.contains(pkg)) {
                continue;
            }
            if (whitelist != null) {
                allWhitelisted = false;
            }
            boolean suspended = suspendedBefore.contains(pkg);
            // 已经是目标状态的不再执行 pm：重复整批调用是原来耗时的主要来源之一
            Boolean enabled = enabledOf(pm, pkg);
            if (freeze ? (suspended || (enabled != null && !enabled))
                    : (enabled != null && enabled && !suspended)) {
                continue;
            }
            if (pkg.matches("[A-Za-z0-9._]+")) {
                todo.add(pkg);
            }
        }
        if (todo.isEmpty()) {
            // 快路径（状态已满足）也按用户指定文案提示；全在白名单时说明真实情况
            if (freeze && allWhitelisted) {
                FrozenTip.showCapsule(this, "白名单应用不冻结");
            } else {
                FrozenTip.showCapsule(this, freeze ? "已全部冻结" : "已全部解冻");
            }
            return;
        }
        // 一次 su + 限并发跑完整批：pm 每条都要拉起一个 app_process，
        // 串行几十条是秒级；并发 8 路能把整批压到一秒左右
        StringBuilder cmd = new StringBuilder();
        cmd.append("i=0; for p in");
        for (String p : todo) {
            cmd.append(" '").append(p).append("'");
        }
        cmd.append("; do ")
                .append(freeze
                        ? "pm disable-user --user 0 \"$p\" >/dev/null 2>&1 & "
                        : "pm unsuspend --user 0 \"$p\" >/dev/null 2>&1; "
                        + "pm enable --user 0 \"$p\" >/dev/null 2>&1 & ")
                .append("i=$((i+1)); ")
                .append("[ $((i%8)) -eq 0 ] && wait; done; wait");
        PrivShell.Result result = PrivShell.exec(cmd.toString(), 120);
        // 执行完统一核对真实状态：既拿失败名单，也不依赖 pm 的文本输出
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
                    SuspendSupport.remember(this, again);
                }
            }
        } else {
            Set<String> next = SuspendSupport.cached(this);
            next.removeAll(todo);
            next.addAll(suspendedAfter);
            SuspendSupport.replace(this, next);
        }
        List<String> fails = new ArrayList<>();
        for (String pkg : todo) {
            Boolean enabled = enabledOf(pm, pkg);
            boolean ok = freeze
                    ? ((enabled != null && !enabled) || suspendedAfter.contains(pkg))
                    : (enabled != null && enabled && !suspendedAfter.contains(pkg));
            if (!ok) {
                fails.add(labelOf(pm, pkg) + "：" + (freeze
                        ? "执行后未变为冻结，可能被系统限制" : "执行后未变为启用，可能被系统限制"));
            }
        }
        if (!fails.isEmpty()) {
            // 批量失败清单只列应用名 + 短原因；原始 pm 输出只进日志，不进提示
            String out = result.out == null ? "" : result.out.trim();
            if (!out.isEmpty()) {
                android.util.Log.w("JinPm", "shortcut batch " + (freeze ? "freeze" : "unfreeze")
                        + " raw output:\n" + out);
            }
            StringBuilder sb = new StringBuilder("失败 " + fails.size() + " 个");
            for (String f : fails) {
                sb.append("\n\n").append(f);
            }
            toastLong(sb.toString());
        } else {
            // 全部成功：桌面长按菜单进来的一键冻结/解冻，提示整批结果
            FrozenTip.showCapsule(this, freeze ? "已全部冻结" : "已全部解冻");
        }
    }

    private void toastLong(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
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
