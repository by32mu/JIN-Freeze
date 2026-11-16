package com.jin.freeze.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 桌面图标入口：只做一次分流，不显示任何界面。
 * 默认进全屏冻结列表；「全屏打开模式」关掉后进半屏面板。
 */
public class LaunchActivity extends Activity {

    private static final String PREFS = "freeze_list";
    private static final String KEY_FULLSCREEN_OPEN = "fullscreen_open";
    /** 「隐藏后台」：全屏用 hide_recents，半屏用 panel_hide_recents。 */
    private static final String KEY_HIDE_RECENTS = "hide_recents";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean fullscreen = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_FULLSCREEN_OPEN, true);
        // 与冰箱一致：勾了「隐藏后台」就走带 excludeFromRecents 的入口，
        // 任务从创建起就不进最近任务，系统不会截快照，最近任务里也就没有旧帧残影
        boolean hideRecents = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(fullscreen ? KEY_HIDE_RECENTS : "panel_" + KEY_HIDE_RECENTS, false);
        Intent target = new Intent(this, fullscreen
                ? (hideRecents ? FreezeListHiddenActivity.class : FreezeListActivity.class)
                : (hideRecents ? FreezePanelHiddenActivity.class : FreezePanelActivity.class));
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // 打开 app 先检查 root / Shizuku：目标页面起来后在后台探测，未授权再弹统一提示
        target.putExtra(FreezeListActivity.EXTRA_CHECK_PRIV, true);
        if (!fullscreen) {
            // 桌面图标入口每次都要播放下滑压暗过渡，面板被复用时同样生效
            target.putExtra(FreezeListActivity.EXTRA_PLAY_INTRO, true);
        }
        startActivity(target);
        finish();
        // 入口壳本身不可见：与半屏面板之间不留第二段窗口动画，避免再闪一层底色
        overridePendingTransition(0, 0);
    }
}
