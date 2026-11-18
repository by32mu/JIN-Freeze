package com.jin.freeze.ui;

/**
 * 全屏列表的「隐藏后台」入口：与 {@link FreezeListActivity} 完全相同，
 * 只是清单里带 android:excludeFromRecents="true"。
 * 勾选「隐藏后台」后桌面图标由 LaunchActivity 路由到这里，任务从创建起就不进最近任务，
 * 系统不会生成任务快照，最近任务里也就没有可点的旧帧残影。
 */
public class FreezeListHiddenActivity extends FreezeListActivity {
}
