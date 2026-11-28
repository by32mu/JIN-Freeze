package com.jin.freeze.tile;

import android.content.Intent;

import com.jin.freeze.core.SwipeFreeze;
import com.jin.freeze.core.Whitelist;
import com.jin.freeze.ui.FreezeListActivity;

/** 白名单总开关：关闭后所有冻结通道都不再保护白名单里的应用。 */
public class WhitelistTileService extends JinTileService {

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        Whitelist.setEnabled(this, !Whitelist.enabled(this));
        // 白名单变更要同步给 LSPosed / 专属 Shizuku 侧
        SwipeFreeze.syncAsync(this);
        refresh();
        // 列表正开着时立刻重刷白名单标识（斜杠 / 正常）
        sendBroadcast(new Intent(FreezeListActivity.ACTION_TILE_REFRESH)
                .setPackage(getPackageName()));
    }

    private void refresh() {
        boolean on = Whitelist.enabled(this);
        render(on, on ? "已开启" : "已临时关闭");
    }
}
