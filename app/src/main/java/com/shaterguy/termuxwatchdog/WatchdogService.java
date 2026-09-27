package com.shaterguy.termuxwatchdog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class WatchdogService extends Service {
    private static final String TAG = "TermuxWatchdog";
    private static final String CHANNEL_ID = "watchdog";
    private static final int NOTIFICATION_ID = 41000;
    private static final long HEALTHY_INTERVAL_MS = 60_000L;
    private static final long CONFIRM_INTERVAL_MS = 5_000L;

    private static volatile boolean runningInProcess;

    private final Object scheduleLock = new Object();

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> scheduledCheck;
    private HealthClient healthClient;
    private HealthStateMachine stateMachine;
    private RecoveryBackoff recoveryBackoff;
    private WatchdogStateStore stateStore;
    private IncidentLogger incidentLogger;
    private long startedElapsedMs;
    private volatile boolean destroyed;

    public static boolean requestStart(Context context) {
        if (!new WatchdogStateStore(context).monitoringEnabled()) {
            return false;
        }

        Intent intent = new Intent(context, WatchdogService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Foreground service start was not allowed", e);
            return false;
        }
    }

    public static boolean isRunningInProcess() {
        return runningInProcess;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        runningInProcess = true;
        startedElapsedMs = SystemClock.elapsedRealtime();
        stateStore = new WatchdogStateStore(this);
        stateMachine = new HealthStateMachine(
                stateStore.monitorState(),
                stateStore.consecutiveFailures());
        recoveryBackoff = new RecoveryBackoff();
        healthClient = new HealthClient();
        incidentLogger = new IncidentLogger(this);
        executor = Executors.newSingleThreadScheduledExecutor(
                runnable -> new Thread(runnable, "termux-watchdog-monitor"));
        startForegroundNow();
        if (stateStore.monitoringEnabled()) {
            AlarmScheduler.scheduleBackup(this);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!stateStore.monitoringEnabled()) {
            AlarmScheduler.cancelBackup(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        scheduleCheck(0L);
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (stateStore != null && stateStore.monitoringEnabled()) {
            AlarmScheduler.scheduleBackup(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        runningInProcess = false;
        synchronized (scheduleLock) {
            if (scheduledCheck != null) {
                scheduledCheck.cancel(false);
                scheduledCheck = null;
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        if (stateStore != null && stateStore.monitoringEnabled()) {
            AlarmScheduler.scheduleBackup(this);
        } else {
            AlarmScheduler.cancelBackup(this);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundNow() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Termux Watchdog",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Monitors local Termux health and fixed recovery.");
            manager.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Termux Watchdog")
                .setContentText("Monitoring local Termux services")
                .setContentIntent(content)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void scheduleCheck(long delayMs) {
        if (destroyed || executor == null || executor.isShutdown()) {
            return;
        }
        synchronized (scheduleLock) {
            if (scheduledCheck != null) {
                scheduledCheck.cancel(false);
            }
            scheduledCheck = executor.schedule(
                    this::performCheck,
                    Math.max(0L, delayMs),
                    TimeUnit.MILLISECONDS);
        }
    }

    private void performCheck() {
        if (destroyed || !stateStore.monitoringEnabled()) {
            return;
        }

        HealthStateMachine.State previous = stateMachine.state();
        HealthClient.HealthResult result = healthClient.check();
        long wallNow = System.currentTimeMillis();

        if (result.isHealthy()) {
            boolean recovering = previous != HealthStateMachine.State.HEALTHY
                    || stateStore.currentIncidentId() != null
                    || recoveryBackoff.completedAttempts() > 0;
            String incidentId = stateStore.currentIncidentId();
            long detectedAt = stateStore.incidentDetectedAt();
            int attempts = recoveryBackoff.completedAttempts();

            stateMachine.onHealthResult(true);
            stateStore.saveMonitorState(
                    stateMachine.state(),
                    stateMachine.consecutiveFailures(),
                    "");
            stateStore.recordHealthSuccess(wallNow);

            if (recovering) {
                incidentLogger.append(
                        "HEALTH_RECOVERED",
                        incidentId,
                        detectedAt,
                        wallNow,
                        0,
                        attempts,
                        "post_recovery_health_ok",
                        wallNow,
                        wallNow,
                        SystemClock.elapsedRealtime() - startedElapsedMs,
                        "");
                if (attempts > 0) {
                    stateStore.recordRecoverySuccess(wallNow);
                }
            }

            stateStore.clearIncident();
            recoveryBackoff.reset();
            scheduleCheck(HEALTHY_INTERVAL_MS);
            return;
        }

        HealthStateMachine.State state = stateMachine.onHealthResult(false);
        stateStore.saveMonitorState(
                state,
                stateMachine.consecutiveFailures(),
                result.failureReason());

        if (state == HealthStateMachine.State.DOWN) {
            String incidentId = stateStore.currentIncidentId();
            if (incidentId == null) {
                incidentId = UUID.randomUUID().toString();
                stateStore.startIncident(incidentId, wallNow);
                incidentLogger.append(
                        "DOWN_DETECTED",
                        incidentId,
                        wallNow,
                        stateStore.lastHealthSuccessAt(),
                        stateMachine.consecutiveFailures(),
                        0,
                        "",
                        0L,
                        0L,
                        SystemClock.elapsedRealtime() - startedElapsedMs,
                        result.failureReason());
            }

            long elapsedNow = SystemClock.elapsedRealtime();
            if (recoveryBackoff.shouldAttempt(elapsedNow)) {
                if (!stateStore.monitoringEnabled()) {
                    return;
                }
                int attempt = recoveryBackoff.nextAttemptNumber();
                RunCommandBridge.DispatchResult dispatch =
                        RunCommandBridge.dispatch(this);
                recoveryBackoff.markAttempt(elapsedNow);
                stateStore.setRecoveryAttempt(attempt);
                incidentLogger.append(
                        dispatch.wasDispatched()
                                ? "RECOVERY_DISPATCHED"
                                : "RECOVERY_DISPATCH_FAILED",
                        incidentId,
                        stateStore.incidentDetectedAt(),
                        stateStore.lastHealthSuccessAt(),
                        stateMachine.consecutiveFailures(),
                        attempt,
                        dispatch.detail(),
                        0L,
                        0L,
                        SystemClock.elapsedRealtime() - startedElapsedMs,
                        result.failureReason());
                if (!dispatch.wasDispatched()) {
                    stateStore.recordRecoveryFailure(wallNow, dispatch.detail());
                }
            }
        }

        scheduleCheck(CONFIRM_INTERVAL_MS);
    }
}
