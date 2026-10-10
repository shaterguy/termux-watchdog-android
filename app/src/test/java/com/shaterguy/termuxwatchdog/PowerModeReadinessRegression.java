package com.shaterguy.termuxwatchdog;

/** No Android, Google, health telemetry or supervisor prerequisites. */
public final class PowerModeReadinessRegression {
    private static int checks;
    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        checks = 0;
        PowerModeReadiness ready = new PowerModeReadiness();
        check(!ready.canEnable(100, false), "unknown controller must not enable saver");
        ready.observed(100);
        check(ready.canEnable(100, false), "actual valid status permits enable immediately");
        check(ready.canEnable(30_099, false), "status is fresh within thirty seconds");
        check(!ready.canEnable(30_100, false), "thirty-second boundary requires new status");
        check(!ready.canEnable(99, false), "monotonic clock reset cannot reuse old readiness");
        check(!ready.canEnable(101, true), "latest transport failure blocks prior success");
        ready.invalidate();
        check(!ready.canEnable(102, false), "failed or incompatible response clears readiness");
        ready.observed(103);
        check(ready.canEnable(104, false), "fresh valid response recovers readiness");
        check(!new PowerModeReadiness().canEnable(104, false), "process recreation requires a real response");
        ready.observed(-1);
        check(!ready.canEnable(104, false), "missing request time is not recent evidence");
        ready.observed(0);
        check(ready.canEnable(0, false), "zero monotonic start is legitimate");
        check(!PowerModeText.error("MODE_BACKEND_NOT_READY", false).contains("잠금"),
                "unready enable does not claim recovery was paused");
        check(PowerModeText.error("MODE_BACKEND_NOT_READY", false).contains("상태 확인"),
                "unready label directs user to state verification");
        System.out.println("POWER_MODE_READINESS_PASS checks=" + checks);
    }
}
