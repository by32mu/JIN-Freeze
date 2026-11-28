package com.jin.freeze.tile;

import android.content.Intent;
import android.widget.Toast;

import com.jin.freeze.JinApp;
import com.jin.freeze.core.HideSupport;
import com.jin.freeze.core.PrivShell;
import com.jin.freeze.core.SwipeFreeze;
import com.jin.freeze.ui.FreezeListActivity;

/**
 * 隐藏应用模式开关（临时）：
 * 关闭时只临时取消隐藏并保留「要隐藏」的账本，列表里的 × 变灰；
 * 再次点击恢复隐藏。与设置页的「取消隐藏应用模式」不同，后者会彻底放弃隐藏。
 */
public class HideModeTileService extends JinTileService {

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        final boolean next = !HideSupport.modeOn(this);
        if (next && !HideSupport.available(this)) {
            Toast.makeText(this, "当前权限不支持隐藏应用", Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        HideSupport.setModeOn(this, next);
        refresh();
        // 列表正开着时先把 × 标识切成灰 / 红，不用等回到前台
        notifyList();
        JinApp.IO.execute(() -> {
            for (String key : new java.util.LinkedHashSet<>(HideSupport.hiddenIntentKeys(this))) {
                String pkg = HideSupport.pkgOf(key);
                int uid = HideSupport.userIdOf(key);
                if (next) {
                    // 恢复：按账本重新隐藏
                    HideSupport.hide(this, pkg, uid);
                } else {
                    // 临时关闭：取消系统隐藏位但保留账本，应用可以正常使用
                    HideSupport.unhideForLaunch(this, pkg, uid);
                    PrivShell.exec("pm enable --user " + uid + " " + pkg, 20);
                }
            }
            SwipeFreeze.syncAsync(this);
            // 隐藏 / 取消隐藏落库后再刷一次，列表的冻结态跟着真值走
            notifyList();
        });
    }

    private void notifyList() {
        sendBroadcast(new Intent(FreezeListActivity.ACTION_TILE_REFRESH)
                .setPackage(getPackageName()));
    }

    private void refresh() {
        boolean on = HideSupport.modeOn(this);
        render(on, on ? "已开启" : "已临时关闭");
    }
}
