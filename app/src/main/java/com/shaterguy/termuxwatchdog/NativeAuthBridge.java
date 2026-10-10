package com.shaterguy.termuxwatchdog;

import android.content.Context;
import android.os.UserManager;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import java.util.HashSet;

/** Every credential admission and final delivery is linearized against local revocation. */
final class NativeAuthBridge {
    private static final AuthDeliveryGate delivery=new AuthDeliveryGate();
    private static volatile String routeState="unverified";
    private static boolean forceBlocked;
    private static final Set<CompletableFuture<AuthCoordinator.Result>> nativeFlights=new HashSet<>();
    static AuthDeliveryGate deliveryLock() { return delivery; }
    static void noteHandshake() { routeState="verified"; }
    static void noteFailure() { routeState="unavailable"; }
    private static boolean unlocked(Context context) {
        UserManager users=context.getSystemService(UserManager.class);
        return users!=null && users.isUserUnlocked();
    }
    private static boolean enabled(Context context) {
        synchronized(delivery) {
            return !forceBlocked && unlocked(context)
                    && context.getSharedPreferences("native_auth",Context.MODE_PRIVATE).getBoolean("bridge_enabled",false);
        }
    }
    static String connectionState(Context context) {
        synchronized(delivery) {
            if(!enabled(context)) return "stopped";
            if(!new AuthPeerVerifier(context).configured()) return "not_configured";
            if(!BuildConfig.NATIVE_TOKEN_TRANSPORT_ENABLED) return "probe_required";
            if(settledSaver(context)) return "paused";
            return routeState;
        }
    }
    static boolean mayUseTokens(Context context) {
        synchronized(delivery) {
            return AuthAvailability.mayUse(BuildConfig.NATIVE_TOKEN_TRANSPORT_ENABLED,enabled(context),unlocked(context),settledSaver(context));
        }
    }
    static boolean deliveryAllowed(Context context,long admittedEpoch) {
        synchronized(delivery) { return delivery.current(admittedEpoch) && mayUseTokens(context); }
    }
    static String status(Context context) {
        synchronized(delivery) {
            NativeAuthRuntime existing=NativeAuthRuntime.peek();
            String sdkState=existing==null ? "not_ready" : existing.auth.state();
            return AuthAvailability.state(BuildConfig.NATIVE_TOKEN_TRANSPORT_ENABLED,enabled(context),unlocked(context),settledSaver(context),sdkState);
        }
    }
    static final class Admission {
        final long epoch;
        final CompletableFuture<AuthCoordinator.Result> operation;
        Admission(long epoch,CompletableFuture<AuthCoordinator.Result> operation) { this.epoch=epoch; this.operation=operation; }
    }
    static Admission request(Context context,String rejectedToken,long latestStartNanos) {
        synchronized(delivery) {
            long accepted=delivery.epoch();
            if(!BuildConfig.NATIVE_TOKEN_TRANSPORT_ENABLED) return new Admission(accepted,CompletableFuture.completedFuture(AuthCoordinator.Result.error("AUTH_NOT_CONFIGURED")));
            if(!mayUseTokens(context)) return new Admission(accepted,CompletableFuture.completedFuture(AuthCoordinator.Result.error("AUTH_UNAVAILABLE")));
            CompletableFuture<AuthCoordinator.Result> operation=NativeAuthRuntime.get(context).auth.request(rejectedToken,latestStartNanos);
            nativeFlights.add(operation);
            operation.whenComplete((value,error)->{ synchronized(delivery) { nativeFlights.remove(operation); } });
            return new Admission(accepted,operation);
        }
    }
    /** Invoked while the same monitor protects the preferences commit. Failure denies locally. */
    static void preferenceChanged(boolean enabled,boolean saved,AuthCoordinator auth) {
        synchronized(delivery) {
            forceBlocked=!enabled || !saved;
            delivery.revoke();
            auth.invalidate("AUTH_UNAVAILABLE");
            routeState="unverified";
        }
    }
    static boolean requestStart(Context context,String origin) {
        if(!unlocked(context)) return false;
        NativeAuthRuntime runtime=NativeAuthRuntime.get(context);
        synchronized(delivery) {
            return AuthLifecyclePolicy.canStart(runtime.enabled(),true,origin)
                    && new AuthPeerVerifier(context).configured() && BuildConfig.NATIVE_TOKEN_TRANSPORT_ENABLED
                    && ("visible_restore".equals(origin) || !settledSaver(context));
        }
    }
    static void stopLocal(Context context) {
        synchronized(delivery) {
            forceBlocked=true; delivery.revoke(); routeState="unverified";
            NativeAuthRuntime runtime=NativeAuthRuntime.peek();
            if(runtime!=null) runtime.auth.invalidate("AUTH_UNAVAILABLE");
        }
    }
    private static boolean settledSaver(Context context) {
        PowerModeStore store=new PowerModeStore(context);
        PowerModeSnapshot snapshot=store.snapshot(); PowerModePolicy policy=store.policy();
        return AuthLifecyclePolicy.shouldPause(snapshot!=null && snapshot.isPowerSavingVerified(),policy.desiredMode(),policy.awaitingAcknowledgement());
    }
    static void pauseIfSettled(Context context) {
        synchronized(delivery) {
            if(settledSaver(context)) {
                // Revoke every confirmed settled observation; no stale restore latch can skip it.
                delivery.revoke();
                NativeAuthRuntime runtime=NativeAuthRuntime.peek();
                // Explicit foreground consent remains usable while saver is settled.
                if(runtime!=null && !nativeFlights.isEmpty()) runtime.auth.invalidate("AUTH_UNAVAILABLE");
            }
        }
    }
}
