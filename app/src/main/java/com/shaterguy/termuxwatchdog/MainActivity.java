package com.shaterguy.termuxwatchdog;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        status.setText("Foundation build 0.1.0-dev1");
        status.setTextSize(16f);
        root.addView(status);

        setContentView(root);
    }
}
