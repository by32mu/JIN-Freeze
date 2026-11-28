package com.jin.freeze.tile;

import android.content.Intent;

import com.jin.freeze.ui.QuickLaunchActivity;

/** 一键解冻：交给桌面快捷方式同一套整批逻辑执行。 */
public class UnfreezeAllTileService extends JinTileService {

    @Override
    public void onStartListening() {
        render(false, null);
    }

    @Override
    public void onClick() {
        startQuickAction(new Intent(this, QuickLaunchActivity.class)
                .setAction(QuickLaunchActivity.ACTION_UNFREEZE_ALL));
    }
}
