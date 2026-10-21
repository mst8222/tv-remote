package com.koco.tvremoteserver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启：盒子重启后服务自动恢复，手机无需任何操作 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            ServerService.start(c);
        } catch (Exception ignored) {}
    }
}
