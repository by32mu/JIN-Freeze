package com.jin.freeze.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import com.jin.freeze.core.CoreProtect;
import com.jin.freeze.core.LaunchTarget;
import com.jin.freeze.core.PrivShell;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 桌面快捷方式的启动中转：快捷方式 Intent 只带 key，这里取回真实目标再启动。
 * 导出页面直接 startActivity，未导出页面按「root 强启」开关走 su。
 * 「一键冻结 / 一键解冻」不显示界面，直接对名单执行后结束。
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
            runBatchSilently(ACTION_FREEZE_ALL.equals(action));
            return;
        }
        if (ACTION_OPEN_FREEZE_LIST.equals(action)) {
            startActivity(new Intent(this, FreezePanelActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        String key = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_KEY);
        LaunchTarget.Target t = LaunchTarget.launchKeyTarget(this, key);
        if (t == null) {
            Toast.makeText(this, "目标已不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        int result = LaunchTarget.launch(this, t);
        if (result != LaunchTarget.OK) {
            Toast.makeText(this, LaunchTarget.problemText(result), Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    /** 不显示界面：对名单整批冻结/解冻，全部成功时静默，只有失败才提示。 */
    private void runBatchSilently(final boolean freeze) {
        final Set<String> pkgs = new HashSet<>(getSharedPreferences("freeze_list", MODE_PRIVATE)
                .getStringSet("pkgs", new HashSet<>()));
        if (pkgs.isEmpty()) {
            Toast.makeText(this, "冻结名单为空", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<String> fails = new ArrayList<>();
            for (String pkg : pkgs) {
                String label = pkg;
                try {
                    label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                } catch (Exception ignored) {
                }
                if (freeze && CoreProtect.isProtected(this, pkg, label)) {
                    continue;
                }
                String cmd = freeze ? "pm disable-user --user 0 " + pkg : "pm enable " + pkg;
                PrivShell.Result r = PrivShell.exec(cmd, 30);
                String out = r.out == null ? "" : r.out.trim();
                if (!isPmOk(out)) {
                    fails.add(label + "（" + pkg + "）\n" + (out.isEmpty() ? "pm 无输出" : out));
                }
            }
            final List<String> failed = fails;
            runOnUiThread(() -> {
                if (!failed.isEmpty()) {
                    StringBuilder sb = new StringBuilder("失败 " + failed.size() + " 个");
                    for (String f : failed) {
                        sb.append("\n\n").append(f);
                    }
                    Toast.makeText(this, sb.toString(), Toast.LENGTH_LONG).show();
                }
                finish();
            });
        }, "shortcut-batch").start();
    }

    private boolean isPmOk(String out) {
        String low = out.toLowerCase(Locale.ROOT);
        return out.contains("new state:")
                || (!out.isEmpty() && !low.contains("error") && !low.contains("exception")
                && !low.contains("failure") && !low.contains("denied"));
    }
}
