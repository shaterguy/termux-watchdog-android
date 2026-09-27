package com.shaterguy.termuxwatchdog;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class StaticSecurityTest {
    @Test
    public void manifestUsesSpecialUseAndKeepsInternalComponentsPrivate() throws Exception {
        String manifest = readUtf8(projectFile("src/main/AndroidManifest.xml"));

        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"));
        assertTrue(manifest.contains("android:foregroundServiceType=\"specialUse\""));
        assertTrue(manifest.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"));
        assertTrue(manifest.contains("android.intent.action.BOOT_COMPLETED"));
        assertTrue(manifest.contains("android.intent.action.LOCKED_BOOT_COMPLETED"));
        assertTrue(manifest.contains("android.intent.action.MY_PACKAGE_REPLACED"));
        assertFalse(manifest.contains("android.permission.SCHEDULE_EXACT_ALARM"));

        int firstInternal = manifest.indexOf("<service");
        assertTrue(firstInternal >= 0);
        assertTrue(manifest.substring(firstInternal).contains("android:exported=\"false\""));
    }

    @Test
    public void runCommandBridgeUsesExplicitFixedComponentWithoutCallbackSurface()
            throws Exception {
        String source = readUtf8(projectFile(
                "src/main/java/com/shaterguy/termuxwatchdog/RunCommandBridge.java"));

        assertTrue(source.contains("new ComponentName("));
        assertTrue(source.contains("RecoveryContract.RUN_COMMAND_SERVICE_CLASS"));
        assertTrue(source.contains("RecoveryCommandSpec.path()"));
        assertTrue(source.contains("RecoveryCommandSpec.arguments()"));
        assertFalse(source.contains("RUN_COMMAND_PENDING_INTENT"));
        assertFalse(source.contains("PendingIntent"));
    }

    @Test
    public void incidentLogRecordsObservedTermuxRecoveryTimestamp() throws Exception {
        String logger = readUtf8(projectFile(
                "src/main/java/com/shaterguy/termuxwatchdog/IncidentLogger.java"));
        String service = readUtf8(projectFile(
                "src/main/java/com/shaterguy/termuxwatchdog/WatchdogService.java"));

        assertTrue(logger.contains("long termuxRecoveryAtMs"));
        assertTrue(logger.contains(
                "json.put(\"termux_recovery_at\", instantOrNull(termuxRecoveryAtMs));"));
        assertTrue(service.contains(
                "\"post_recovery_health_ok\",\n"
                        + "                        wallNow,\n"
                        + "                        wallNow,"));
    }

    private static String readUtf8(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path projectFile(String relative) {
        Path direct = Paths.get(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        return Paths.get("app").resolve(relative);
    }
}
