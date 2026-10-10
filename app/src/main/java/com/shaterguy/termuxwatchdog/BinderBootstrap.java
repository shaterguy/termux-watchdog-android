package com.shaterguy.termuxwatchdog;

import android.app.PendingIntent;
import android.app.BroadcastOptions;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;

/** TermuxAm's documented standalone broadcast route; never impersonates shell or binds a service. */
final class BinderBootstrap {
    static void send(Intent intent, int requestCode) throws Exception {
        Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Class<?> contract = Class.forName("android.app.IActivityManager");
        Object sender = contract.getMethod("getIntentSender", int.class, String.class, IBinder.class,
                String.class, int.class, Intent[].class, String[].class, int.class, Bundle.class, int.class)
                .invoke(am, 1, "com.termux", null, null, requestCode, new Intent[]{intent}, null,
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE,
                        null, Process.myUid() / 100000);
        if (sender == null) throw new IllegalStateException("AUTH_UNAVAILABLE");
        Bundle sendOptions=null;
        if(Build.VERSION.SDK_INT>=34) {
            BroadcastOptions options=BroadcastOptions.makeBasic();
            options.setShareIdentityEnabled(true);
            sendOptions=options.toBundle();
        }
        Class.forName("android.content.IIntentSender").getMethod("send", int.class, Intent.class,
                String.class, IBinder.class, Class.forName("android.content.IIntentReceiver"), String.class, Bundle.class)
                .invoke(sender, 0, null, null, null, null, null, sendOptions);
    }
}
