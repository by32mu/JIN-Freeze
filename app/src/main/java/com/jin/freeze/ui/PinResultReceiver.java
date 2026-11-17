package com.jin.freeze.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 桌面固定快捷方式的结果回调：{@link ShortcutPin} 在批量添加时把它挂到
 * requestPinShortcut 上，系统在用户确认或拒绝后发出，收到后继续队列里的下一个。
 * 不导出：广播由系统按 PendingIntent 的调用方身份投递。
 */
public class PinResultReceiver extends BroadcastReceiver {

    /** 回调里带回的包名：用于确认这是「当前这一条」的结果，迟到的不再推进队列。 */
    static final String EXTRA_TOKEN = "pin_token";

    @Override
    public void onReceive(Context context, Intent intent) {
        ShortcutPin.onPinResult(intent == null ? null : intent.getStringExtra(EXTRA_TOKEN));
    }
}
