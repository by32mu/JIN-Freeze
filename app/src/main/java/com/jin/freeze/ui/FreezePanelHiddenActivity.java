package com.jin.freeze.ui;

/**
 * 半屏面板的「隐藏后台」入口：与 {@link FreezePanelActivity} 完全相同，
 * 只是清单里带 android:excludeFromRecents="true"，保证半屏形态勾选后同样不生成最近任务快照。
 */
public class FreezePanelHiddenActivity extends FreezePanelActivity {
}
