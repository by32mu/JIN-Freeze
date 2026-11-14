package com.jin.freeze.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import com.jin.freeze.core.LaunchTarget;

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
}
