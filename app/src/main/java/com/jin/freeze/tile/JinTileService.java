package com.jin.freeze.tile;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** 快捷设置磁贴公共基类：统一刷新状态与从磁贴拉起中转页的写法。 */
public abstract class JinTileService extends TileService {

    /** 开关类磁贴：active 决定高亮，subtitle 仅 Android 10+ 显示。 */
    protected void render(boolean active, String subtitle) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (subtitle != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(subtitle);
        }
        tile.updateTile();
    }

    /** 拉起应用内中转页执行动作；Android 14 起只能传 PendingIntent。 */
    protected void startQuickAction(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pi);
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(intent);
        }
    }
}
