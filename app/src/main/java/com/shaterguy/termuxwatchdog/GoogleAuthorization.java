package com.shaterguy.termuxwatchdog;

import android.content.Context;
import android.content.Intent;
import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/** Google Play services owns grant/cache renewal; this app never owns a refresh token. */
final class GoogleAuthorization implements AuthCoordinator.Provider {
    static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive";
    private final AuthorizationClient client;
    private final AuthorizationRequest request;
    GoogleAuthorization(Context context) {
        client = Identity.getAuthorizationClient(context.getApplicationContext());
        request = AuthorizationRequest.builder().setRequestedScopes(
                Collections.singletonList(new Scope(DRIVE_SCOPE))).build();
    }
    @Override public CompletableFuture<AuthCoordinator.Result> authorize() {
        CompletableFuture<AuthCoordinator.Result> future = new CompletableFuture<>();
        client.authorize(request).addOnCompleteListener(task -> {
            if (task.isSuccessful()) future.complete(convert(task.getResult()));
            else future.complete(AuthCoordinator.Result.error(code(task.getException())));
        });
        return future;
    }
    @Override public CompletableFuture<Void> clear(String rejectedToken) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        client.clearToken(ClearTokenRequest.builder().setToken(rejectedToken).build())
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) future.complete(null);
                    else future.completeExceptionally(new IllegalStateException("AUTH_UNAVAILABLE"));
                });
        return future;
    }
    AuthCoordinator.Result resolutionResult(Intent data) {
        try { return convert(client.getAuthorizationResultFromIntent(data)); }
        catch (ApiException | RuntimeException ignored) { return AuthCoordinator.Result.error("AUTH_REAUTH_REQUIRED"); }
    }
    private AuthCoordinator.Result convert(AuthorizationResult result) {
        if (result == null) return AuthCoordinator.Result.error("AUTH_UNAVAILABLE");
        if (result.hasResolution()) return AuthCoordinator.Result.resolution(result.getPendingIntent());
        if (result.getGrantedScopes() == null || !result.getGrantedScopes().contains(DRIVE_SCOPE)) {
            return AuthCoordinator.Result.error("AUTH_REAUTH_REQUIRED");
        }
        return AuthCoordinator.Result.token(result.getAccessToken());
    }
    private String code(Exception failure) {
        if (failure instanceof ApiException) {
            int code = ((ApiException) failure).getStatusCode();
            if (code == CommonStatusCodes.DEVELOPER_ERROR) return "AUTH_NOT_CONFIGURED";
            if (code == CommonStatusCodes.SIGN_IN_REQUIRED || code == CommonStatusCodes.CANCELED) return "AUTH_REAUTH_REQUIRED";
        }
        return "AUTH_UNAVAILABLE";
    }
}
