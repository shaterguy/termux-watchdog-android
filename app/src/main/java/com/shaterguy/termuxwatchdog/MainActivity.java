package com.shaterguy.termuxwatchdog;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private static final long UI_REFRESH_MS = 2_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, UI_REFRESH_MS);
        }
    };

    private WatchdogStateStore stateStore;
    private IncidentLogger incidentLogger;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        stateStore = new WatchdogStateStore(this);
        incidentLogger = new IncidentLogger(this);

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
        if (stateStore != null && stateStore.monitoringEnabled()) {
            AlarmScheduler.scheduleBackup(this);
        }
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
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
        title.setText("Termux Watchdog");
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
        boolean running = WatchdogService.isRunningInProcess();
        HealthStateMachine.State state = stateStore.monitorState();

        watchdogValue.setText(!enabled ? "중지됨" : running ? "실행 중" : "시작 대기");
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
        toggleButton.setText(enabled ? "Watchdog 중지" : "Watchdog 시작");
        incidentsText.setText(formatRecentIncidents(incidentLogger.readRecentLines(8)));
    }

    private String termuxText(HealthStateMachine.State state) {
        switch (state) {
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
