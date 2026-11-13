package com.jin.freeze.ui;

import android.content.Intent;

/**
 * 半屏浮层形态的冻结列表：与全屏页共用名单、标签、布局设置与冻结状态，
 * 只换承载方式（透明窗口 + 底部圆角面板）。
 */
public class FreezePanelActivity extends FreezeListActivity {

    @Override
    protected boolean isPanelMode() {
        return true;
    }

    /**
     * singleTask 复用：手势再次触发时不会重建窗口，只把已有面板拉到前台并刷新状态。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        reload();
    }
}
