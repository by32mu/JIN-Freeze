package com.jin.freeze.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * LSPosed 模块页的「打开」入口（intent-filter 带 de.robv.android.xposed.category.MODULE_SETTINGS）。
 *
 * 桌面图标用 activity-alias 承载，可以被「隐藏桌面图标」关掉；这里是不经过 launcher 的入口，
 * 所以隐藏图标后依然能从 LSPosed 模块列表进入凚。
 */
public class ModuleEntryActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, LaunchActivity.class));
        finish();
    }
}
