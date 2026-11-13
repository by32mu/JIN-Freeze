package com.jin.freeze.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 桌面图标入口：只做一次分流，不显示任何界面。
 * 「全屏打开模式」开启时进全屏冻结列表，否则进半屏面板（默认）。
 */
public class LaunchActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean fullscreen = getSharedPreferences("freeze_list", MODE_PRIVATE)
                .getBoolean("fullscreen_open", false);
        Intent target = new Intent(this,
                fullscreen ? FreezeListActivity.class : FreezePanelActivity.class);
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(target);
        finish();
        overridePendingTransition(0, 0);
    }
}
