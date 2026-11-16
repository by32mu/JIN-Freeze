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
            startActivity(new Intent(this, FreezePanelActivity.class)
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

    /** 照搬 Hail 的 launchApp：冻结先解冻，再用系统启动入口拉起。 */
    private void launchApp(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9._]+")) {
            Toast.makeText(this, "该应用没有可启动的界面", Toast.LENGTH_SHORT).show();
            finish();
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
