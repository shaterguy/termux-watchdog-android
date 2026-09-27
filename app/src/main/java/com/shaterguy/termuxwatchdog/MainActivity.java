package com.shaterguy.termuxwatchdog;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int REQUEST_RUN_COMMAND = 41002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AlarmScheduler.scheduleBackup(this);
        WatchdogService.requestStart(this);

        if (checkSelfPermission(RecoveryContract.RUN_COMMAND_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{RecoveryContract.RUN_COMMAND_PERMISSION},
                    REQUEST_RUN_COMMAND);
        }

        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Termux Watchdog");
        title.setTextSize(24f);
        root.addView(title);

        TextView status = new TextView(this);
        status.setText("Runtime core 0.1.0-dev1");
        status.setTextSize(16f);
        root.addView(status);

        setContentView(root);
    }
}
