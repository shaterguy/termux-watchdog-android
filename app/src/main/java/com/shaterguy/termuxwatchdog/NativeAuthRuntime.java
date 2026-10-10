package com.shaterguy.termuxwatchdog;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Only the opt-in boolean persists. Tokens and consent PendingIntents stay in memory. */
final class NativeAuthRuntime {
    private static NativeAuthRuntime instance;
    final GoogleAuthorization google;
    final AuthCoordinator auth;
    final ScheduledExecutorService timers;
    private final SharedPreferences preferences;
    private NativeAuthRuntime(Context context) {
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences("native_auth", Context.MODE_PRIVATE);
        google = new GoogleAuthorization(app);
        timers = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "watchdog-auth-deadlines"));
        auth = new AuthCoordinator(google, timers, 5_000L);
    }
    static synchronized NativeAuthRuntime get(Context context) {
        if (instance == null) instance = new NativeAuthRuntime(context);
        return instance;
    }
    static synchronized NativeAuthRuntime peek() { return instance; }
    boolean enabled() { return preferences.getBoolean("bridge_enabled", false); }
    boolean setEnabled(boolean enabled) {
        synchronized (NativeAuthBridge.deliveryLock()) {
            boolean saved = preferences.edit().putBoolean("bridge_enabled", enabled).commit();
            NativeAuthBridge.preferenceChanged(enabled, saved, auth);
            return saved;
        }
    }
}
