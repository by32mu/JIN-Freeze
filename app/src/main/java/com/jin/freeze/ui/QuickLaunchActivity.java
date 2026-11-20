package com.jin.freeze.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;

import com.jin.freeze.core.LaunchTarget;
import com.jin.freeze.core.PrivShell;

/**
 * 桌面快捷方式的启动中转：快捷方式 Intent 只带 key，这里取回真实目标再启动。
 * 导出页面直接 startActivity，未导出页面按「root 强启」开关走 su。
 * 「一键冻结 / 一键解冻」交给无界面的 ShortcutBatchService 执行后立刻结束。
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
            // 不在界面上跑批：交给无界面的 Service，入口立刻结束，状态栏保持原样
            Intent batch = new Intent(this, ShortcutBatchService.class)
                    .putExtra(ShortcutBatchService.EXTRA_FREEZE, ACTION_FREEZE_ALL.equals(action));
            startService(batch);
            finish();
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
}
