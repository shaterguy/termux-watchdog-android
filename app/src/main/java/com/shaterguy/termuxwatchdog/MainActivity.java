package com.shaterguy.termuxwatchdog;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.IntentSender;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.DateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_RUN_COMMAND = 41002;
    private static final int REQUEST_GOOGLE_AUTH = 41005;
    private static final long UI_REFRESH_MS = 2_000L;
    private static final long MODE_REFRESH_MS = 10_000L;
    private long nextModeRefresh;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (SystemClock.elapsedRealtime() >= nextModeRefresh) {
                nextModeRefresh = SystemClock.elapsedRealtime() + MODE_REFRESH_MS;
                PowerModeBridge.send(MainActivity.this, "status");
            }
            refreshStatus();
            handler.postDelayed(this, UI_REFRESH_MS);
        }
    };

    private WatchdogStateStore stateStore;
    private IncidentLogger incidentLogger;
    private PowerModeStore powerModeStore;

    private TextView watchdogValue;
    private TextView termuxValue;
    private TextView driveWorkerValue;
    private TextView lastHealthyValue;
    private TextView recentIncidentValue;
    private TextView recoveryValue;
    private TextView failuresValue;
    private TextView backupAlarmValue;
    private TextView incidentsText;
    private Button toggleButton;
    private Button backupAlarmButton;
    private Button powerModeButton;
    private TextView powerModeValue;
    private TextView authModeValue;
    private NativeAuthRuntime nativeAuth;
    private Button googleAuthButton;
    private Button stopAuthBridgeButton;
    private boolean foreground;
    private boolean authBusy;
    private boolean consentPending;
    private String authUiMessage = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        stateStore = new WatchdogStateStore(this);
        incidentLogger = new IncidentLogger(this);
        powerModeStore = new PowerModeStore(this);
        nativeAuth = NativeAuthRuntime.get(this);
        consentPending = savedInstanceState != null && savedInstanceState.getBoolean("google_consent_pending", false);

        if (stateStore.monitoringEnabled()) {
            AlarmScheduler.scheduleBackup(this);
            WatchdogService.requestStart(this);
            requestRunCommandPermissionIfNeeded();
        }

        setContentView(buildContent());
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        NativeAuthBridge.requestStart(this, "visible");
        if (stateStore != null && stateStore.monitoringEnabled()) {
            AlarmScheduler.scheduleBackup(this);
        }
        handler.removeCallbacks(refreshRunnable);
        nextModeRefresh = 0;
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        foreground = false;
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private View buildContent() {
        int pad = dp(20);
        int gap = dp(12);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Termux 실행환경과 핵심 서버의 상태를 확인하고 자동 복구를 관리합니다.");
        subtitle.setTextSize(15f);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(6);
        subtitleParams.bottomMargin = gap;
        root.addView(subtitle, subtitleParams);

        powerModeButton = new Button(this);
        powerModeButton.setAllCaps(false);
        powerModeButton.setOnClickListener(view -> {
            if (powerModeStore.policy().blocksRecovery()) prepareRestore();
            else PowerModeBridge.send(this, "enable");
            nextModeRefresh = 0;
            refreshStatus();
        });
        root.addView(powerModeButton, matchWrap());
        powerModeValue = addStatus(root, "절전모드");
        authModeValue = addStatus(root, "Watchdog Google 인증 · Termux 전달");
        googleAuthButton = new Button(this);
        googleAuthButton.setAllCaps(false);
        googleAuthButton.setText("Google 인증 · 다시 확인");
        googleAuthButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Google Drive 권한")
                .setMessage("기존 Termux 서비스가 사용하는 Drive 파일을 읽고 수정하기 위해 전체 Drive 범위를 요청합니다. 승인하고 연결 검증이 완료되면 이 기기의 UID와 서명이 확인된 Termux 실행환경에 요청 시 access token을 전달합니다. Google 승인 화면에서 계정과 권한을 확인하세요.")
                .setNegativeButton("취소", null)
                .setPositiveButton("Google에서 확인", (dialog, which) -> authorizeFromUi()).show());
        root.addView(googleAuthButton, matchWrap());
        stopAuthBridgeButton = new Button(this);
        stopAuthBridgeButton.setAllCaps(false);
        stopAuthBridgeButton.setText("Termux 인증 전달 중지");
        stopAuthBridgeButton.setOnClickListener(view -> {
            if (!nativeAuth.setEnabled(false)) authUiMessage = "인증 전달 설정을 저장하지 못했습니다.";
            else authUiMessage = "이 기기의 인증 전달을 중지했습니다. Google 권한은 계정 설정에서 관리할 수 있습니다.";
            NativeAuthBridge.stopLocal(this);
            refreshStatus();
        });
        root.addView(stopAuthBridgeButton, matchWrap());
        TextView modeHelp = new TextView(this);
        modeHelp.setText("공용 Termux 잠금의 소유 상태는 확인할 수 없어 절전 중 임의 해제하지 않습니다.\n절전모드를 켜면 진행 중인 작업을 마친 뒤 Termux 인증 전달 서비스까지 중지합니다. 끄면 인증 전달을 먼저 준비하고 이전에 켜져 있던 서비스만 복원합니다. Google 재인증이 필요하면 이 앱에서 확인합니다.");
        modeHelp.setTextSize(14f);
        root.addView(modeHelp, matchWrap());

        toggleButton = new Button(this);
        toggleButton.setAllCaps(false);
        toggleButton.setOnClickListener(view -> toggleMonitoring());
        root.addView(toggleButton, matchWrap());

        LinearLayout statusArea = new LinearLayout(this);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        statusArea.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams statusAreaParams = matchWrap();
        statusAreaParams.topMargin = gap;
        root.addView(statusArea, statusAreaParams);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftParams = wide
                ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                : matchWrap();
        statusArea.addView(left, leftParams);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightParams = wide
                ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                : matchWrap();
        if (wide) {
            rightParams.leftMargin = gap;
        } else {
            rightParams.topMargin = gap;
        }
        statusArea.addView(right, rightParams);

        watchdogValue = addStatus(left, "Watchdog");
        termuxValue = addStatus(left, "Termux 실행환경");
        driveWorkerValue = addStatus(left, "Drive Worker");
        failuresValue = addStatus(left, "연속 실패");
        backupAlarmValue = addStatus(left, "백업 자동재기동 권한");

        backupAlarmButton = new Button(this);
        backupAlarmButton.setAllCaps(false);
        backupAlarmButton.setText("알람 및 리마인더 권한 열기");
        backupAlarmButton.setOnClickListener(view -> openExactAlarmSettingsIfNeeded());
        left.addView(backupAlarmButton, matchWrap());

        lastHealthyValue = addStatus(right, "마지막 정상 확인");
        recentIncidentValue = addStatus(right, "최근 장애 발생");
        recoveryValue = addStatus(right, "최근 자동복구");

        TextView incidentsTitle = new TextView(this);
        incidentsTitle.setText("최근 장애 로그");
        incidentsTitle.setTextSize(20f);
        incidentsTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams incidentTitleParams = matchWrap();
        incidentTitleParams.topMargin = dp(22);
        root.addView(incidentsTitle, incidentTitleParams);

        incidentsText = new TextView(this);
        incidentsText.setTextSize(14f);
        incidentsText.setTextIsSelectable(true);
        incidentsText.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams incidentParams = matchWrap();
        incidentParams.topMargin = dp(8);
        root.addView(incidentsText, incidentParams);

        return scroll;
    }

    private TextView addStatus(LinearLayout parent, String label) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView key = new TextView(this);
        key.setText(label);
        key.setTextSize(13f);
        block.addView(key);

        TextView value = new TextView(this);
        value.setTextSize(18f);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams valueParams = matchWrap();
        valueParams.topMargin = dp(3);
        block.addView(value, valueParams);

        LinearLayout.LayoutParams blockParams = matchWrap();
        blockParams.bottomMargin = dp(6);
        parent.addView(block, blockParams);
        return value;
    }

    private void toggleMonitoring() {
        if (powerModeStore.policy().blocksRecovery()) return;
        if (stateStore.monitoringEnabled()) {
            stateStore.setMonitoringEnabled(false);
            AlarmScheduler.cancelBackup(this);
            stopService(new Intent(this, WatchdogService.class));
        } else {
            stateStore.setMonitoringEnabled(true);
            requestRunCommandPermissionIfNeeded();
            AlarmScheduler.scheduleBackup(this);
            WatchdogService.requestStart(this);
            openExactAlarmSettingsIfNeeded();
        }
        refreshStatus();
    }

    private void requestRunCommandPermissionIfNeeded() {
        if (checkSelfPermission(RecoveryContract.RUN_COMMAND_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{RecoveryContract.RUN_COMMAND_PERMISSION},
                    REQUEST_RUN_COMMAND);
        }
    }

    private void openExactAlarmSettingsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || AlarmScheduler.canScheduleExactAlarms(this)) {
            return;
        }

        Intent request = new Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + getPackageName()));
        if (request.resolveActivity(getPackageManager()) != null) {
            startActivity(request);
        }
    }

    private void refreshStatus() {
        if (watchdogValue == null) {
            return;
        }

        boolean enabled = stateStore.monitoringEnabled();
        boolean powerBlocked = powerModeStore.policy().blocksRecovery();
        boolean running = WatchdogService.isRunningInProcess();
        HealthStateMachine.State state = stateStore.monitorState();

        watchdogValue.setText(powerBlocked ? "절전모드 · 자동복구 일시정지"
                : !enabled ? "중지됨" : running ? "실행 중" : "시작 대기");
        termuxValue.setText(enabled
                ? termuxText(state)
                : "감시 중지 · 마지막 " + termuxText(state));
        driveWorkerValue.setText(enabled
                ? driveWorkerText(state)
                : "감시 중지 · 마지막 " + driveWorkerText(state));
        failuresValue.setText(String.valueOf(stateStore.consecutiveFailures()));
        boolean exactAlarmReady = AlarmScheduler.canScheduleExactAlarms(this);
        backupAlarmValue.setText(exactAlarmReady
                ? "사용 가능"
                : "권한 필요 · 장애 시 자동 재기동 보장 안 됨");
        backupAlarmButton.setVisibility(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exactAlarmReady
                        ? View.VISIBLE
                        : View.GONE);
        lastHealthyValue.setText(formatTime(stateStore.lastHealthSuccessAt()));
        recentIncidentValue.setText(formatTime(stateStore.lastIncidentAt()));
        recoveryValue.setText(formatRecovery());
        toggleButton.setEnabled(!powerBlocked);
        toggleButton.setText(stateStore.monitoringRequested() ? "Watchdog 중지" : "Watchdog 시작");
        refreshPowerMode();
        incidentsText.setText(formatRecentIncidents(incidentLogger.readRecentLines(8)));
    }

    private void refreshPowerMode() {
        PowerModePolicy policy = powerModeStore.policy();
        PowerModeSnapshot observed = powerModeStore.snapshot();
        powerModeButton.setEnabled(!policy.awaitingAcknowledgement()
                && (policy.blocksRecovery() || powerModeStore.canEnable()));
        powerModeButton.setText(policy.blocksRecovery()
                ? "절전모드 끄기 · 기존 서비스 복원" : "절전모드 켜기");
        String error = powerModeStore.error();
        long observedAge = System.currentTimeMillis() - powerModeStore.observedAt();
        boolean fresh = powerModeStore.observedAt() > 0 && observedAge >= 0 && observedAge < 30_000;
        if (policy.awaitingAcknowledgement()) {
            powerModeValue.setText((policy.desiredMode().equals("power_saving")
                    ? "켜기 요청 확인 중" : "서비스 복원 요청 확인 중")
                    + (error.isEmpty() ? "" : "\n" + modeError(error)));
        } else if (!policy.blocksRecovery() && !powerModeStore.canEnable()) {
            powerModeValue.setText(modeError("MODE_BACKEND_NOT_READY")
                    + (error.isEmpty() || error.equals("MODE_BACKEND_NOT_READY") ? "" : "\n" + modeError(error)));
        } else if (observed == null) {
            powerModeValue.setText(error.isEmpty() ? "상태 확인 중" : modeError(error));
        } else {
            String label;
            switch (observed.state) {
                case "power_saving": label = "켜짐 · Termux 인증 전달 중지"; break;
                case "draining": label = "진행 중인 작업 마무리 대기"; break;
                case "resuming": label = "이전 서비스 복원 중"; break;
                case "blocked": label = "전환 보류 · 강제 종료하지 않음"; break;
                default: label = observed.isNormalVerified() ? "꺼짐" : "복원 확인 필요";
            }
            label += "\n쉼 " + observed.pausedServices + "개 · 대기 " + observed.pendingServices + "개";
            String issue = error.isEmpty() ? observed.errorCode : error;
            if (!fresh) label = "현재 상태 확인 불가 · 마지막 기록\n" + label;
            if (!issue.isEmpty()) label += "\n" + modeError(issue);
            label += "\n마지막 확인 " + formatTime(powerModeStore.observedAt());
            powerModeValue.setText(label);
        }
        String nativeStatus;
        switch (nativeAuth.auth.state()) {
            case "ready": nativeStatus = "최근 Google 인증 요청 성공"; break;
            case "reauth_required": nativeStatus = "Google 재인증 필요 · 위 버튼에서 확인"; break;
            case "not_configured": nativeStatus = "Google 앱 인증 설정 확인 필요"; break;
            case "unavailable": nativeStatus = "Google 인증 연결 확인 필요"; break;
            default: nativeStatus = "Google 인증은 요청 시 확인합니다.";
        }
        String bridge;
        switch (NativeAuthBridge.connectionState(this)) {
            case "verified": bridge = "마지막 요청의 앱 연결 검증 성공"; break;
            case "unverified": bridge = "Termux 인증 전달 연결 미확인 · 첫 요청 확인 대기"; break;
            case "probe_required": bridge = "앱 인증 전달 경로 검증 대기"; break;
            case "paused": bridge = "절전 중 · 앱 인증 전달 중지"; break;
            case "not_configured": bridge = "Termux 앱 서명 확인 설정 필요"; break;
            case "starting": bridge = "앱 인증 전달 준비 중"; break;
            case "start_denied": bridge = "앱에서 인증 전달을 다시 시작하세요."; break;
            case "unavailable": bridge = "앱 인증 전달 연결 실패 · Termux 상태를 확인하세요."; break;
            default: bridge = "앱 인증 전달 중지";
        }
        String broker = observed == null || !error.isEmpty() || !fresh ? "Termux 전달 서비스 상태 미확인"
                : observed.authBrokerRunning ? "Termux 전달 서비스 실행 중" : "Termux 전달 서비스 중지됨";
        authModeValue.setText(nativeStatus + "\n" + bridge + "\n" + broker
                + (authUiMessage.isEmpty() ? "" : "\n" + authUiMessage));
        googleAuthButton.setEnabled(!authBusy && !consentPending);
        stopAuthBridgeButton.setEnabled(nativeAuth.enabled() && !authBusy && !consentPending);
    }

    private String modeError(String code) {
        return PowerModeText.error(code, powerModeStore.policy().blocksRecovery());
    }

    private void prepareRestore() {
        if (!foreground || powerModeStore.policy().awaitingAcknowledgement()) return;
        NativeAuthBridge.requestStart(this, "visible_restore");
        // Backend checks native readiness only when the saved journal needs the broker.
        // A broker that was already stopped must not force a new Google consent.
        PowerModeBridge.send(this, "disable");
        nextModeRefresh = 0;
        refreshStatus();
    }

    private void authorizeFromUi() {
        if (!foreground || authBusy || consentPending) return;
        if (!nativeAuth.setEnabled(true)) {
            authUiMessage = "인증 전달 설정을 저장하지 못했습니다.";
            refreshStatus();
            return;
        }
        NativeAuthBridge.requestStart(this, "visible");
        authBusy = true;
        authUiMessage = "Google 인증 확인 중";
        refreshStatus();
        nativeAuth.auth.request(null).whenComplete((result, failure) -> runOnUiThread(() -> {
            authBusy = false;
            if (isFinishing() || isDestroyed()) return;
            if (failure != null || result == null) authUiMessage = "Google 인증에 연결하지 못했습니다. 잠시 후 다시 확인하세요.";
            else if (result.ok()) authUiMessage = "Google 인증을 확인했습니다.";
            else if (result.code.equals("AUTH_REAUTH_REQUIRED") && result.resolution instanceof PendingIntent && foreground) {
                try {
                    consentPending = true;
                    startIntentSenderForResult(((PendingIntent) result.resolution).getIntentSender(), REQUEST_GOOGLE_AUTH, null, 0, 0, 0);
                    authUiMessage = "Google 승인 화면에서 확인해 주세요.";
                } catch (IntentSender.SendIntentException | RuntimeException ignored) {
                    consentPending = false;
                    authUiMessage = "Google 승인 화면을 열지 못했습니다. 다시 눌러 주세요.";
                }
            } else if (result.code.equals("AUTH_NOT_CONFIGURED")) authUiMessage = "Google Cloud의 앱 패키지와 서명 등록을 확인해야 합니다.";
            else if (result.code.equals("AUTH_REAUTH_REQUIRED")) authUiMessage = "앱에서 Google 인증 버튼을 눌러 권한을 확인해 주세요.";
            else authUiMessage = "Google 인증에 연결하지 못했습니다. 잠시 후 다시 확인하세요.";
            refreshStatus();
        }));
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_GOOGLE_AUTH || !consentPending) return;
        consentPending = false;
        nativeAuth.auth.invalidate();
        if (resultCode != RESULT_OK || data == null || !nativeAuth.google.resolutionResult(data).ok()) {
            authUiMessage = "Google 인증이 완료되지 않았습니다. 권한을 확인하고 다시 시도하세요.";
            refreshStatus();
            return;
        }
        // The returned credential is never displayed or stored. Ask the SDK again on demand.
        authBusy = true;
        nativeAuth.auth.request(null).whenComplete((result, failure) -> runOnUiThread(() -> {
            authBusy = false;
            if (isFinishing() || isDestroyed()) return;
            authUiMessage = failure == null && result != null && result.ok() ? "Google 인증을 확인했습니다." : "Google 인증을 다시 확인해야 합니다.";
            refreshStatus();
        }));
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("google_consent_pending", consentPending);
        super.onSaveInstanceState(outState);
    }

    private String termuxText(HealthStateMachine.State state) {
        switch (state) {
            case UNKNOWN:
                return "확인 불가 · " + healthFailureText();
            case HEALTHY:
                return "응답 정상";
            case SUSPECT:
                return "응답 재확인 중";
            case DOWN:
                return "응답 없음";
            default:
                return "확인 불가";
        }
    }

    private String driveWorkerText(HealthStateMachine.State state) {
        switch (state) {
            case UNKNOWN:
                return "상태 확인 실패";
            case HEALTHY:
                return "정상";
            case SUSPECT:
                return "의심";
            case DOWN:
                return "중단";
            default:
                return "확인 불가";
        }
    }

    private String healthFailureText() {
        String reason = stateStore.lastFailureReason();
        if ("health_timeout".equals(reason)) return "응답 시간 초과";
        if ("permission_denied".equals(reason)) return "접속 권한 오류";
        if ("health_invalid_json".equals(reason)) return "응답 형식 오류";
        if ("health_json_nesting_limit".equals(reason)) return "응답 구조가 너무 복잡함";
        if ("json_ok_not_true".equals(reason)) return "서버가 정상 응답을 보내지 않음";
        if (reason != null && reason.startsWith("http_")) return "HTTP " + reason.substring(5);
        return "응답을 읽지 못함";
    }

    private String formatRecovery() {
        String status = stateStore.lastRecoveryStatus();
        if (status == null || status.isEmpty()) {
            return "기록 없음";
        }
        String label = "SUCCESS".equals(status) ? "성공" : "실패";
        String detail = stateStore.lastRecoveryDetail();
        String suffix = detail == null || detail.isEmpty() || "post_recovery_health_ok".equals(detail)
                ? ""
                : " · " + detail;
        return label + " · " + formatTime(stateStore.lastRecoveryAt()) + suffix;
    }

    private String formatRecentIncidents(List<String> lines) {
        if (lines.isEmpty()) {
            return "기록 없음";
        }

        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            try {
                JSONObject json = new JSONObject(line);
                String event = json.optString("event", "UNKNOWN");
                String detected = json.isNull("detected_at")
                        ? "-"
                        : formatIsoTime(json.optString("detected_at"));
                int failures = json.optInt("consecutive_failures", 0);
                int attempt = json.optInt("recovery_attempt", 0);
                out.append(event)
                        .append(" · ")
                        .append(detected)
                        .append("\n실패 ")
                        .append(failures)
                        .append("회 · 복구 시도 ")
                        .append(attempt)
                        .append("회");
            } catch (Exception e) {
                out.append(line);
            }
        }
        return out.toString();
    }

    private String formatIsoTime(String iso) {
        try {
            return formatTime(Instant.parse(iso).toEpochMilli());
        } catch (Exception e) {
            return iso;
        }
    }

    private String formatTime(long epochMs) {
        if (epochMs <= 0L) {
            return "기록 없음";
        }
        DateFormat format = DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.MEDIUM,
                Locale.getDefault());
        return format.format(new Date(epochMs));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
