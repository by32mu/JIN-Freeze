package com.jin.freeze.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * LSPosed 模块页的「打开」入口（intent-filter 带 de.robv.android.xposed.category.MODULE_SETTINGS）。
 * 只负责转到桌面图标同一个入口 Activity，权限检查与过渡都由它统一处理。
 */
public class ModuleEntryActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, LaunchActivity.class));
        finish();
    }
}
