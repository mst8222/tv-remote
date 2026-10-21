package com.koco.tvremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * 接收 PackageInstaller 会话状态（SPEC-017 FR-17-7）。
 * 普通应用提交会话后系统会返回待用户确认状态，必须由这里拉起安装确认界面。
 */
public class UpdateInstallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // 系统确认界面：必须带 NEW_TASK 才能在广播里启动
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    c.startActivity(confirm);
                } catch (Exception ignored) {
                }
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(c, R.string.update_installed, Toast.LENGTH_LONG).show();
            return;
        }
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (msg == null) msg = "";
        Toast.makeText(c, c.getString(R.string.update_install_failed, msg),
                Toast.LENGTH_LONG).show();
    }
}
