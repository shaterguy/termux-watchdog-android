# Termux Watchdog

Android watchdog app that monitors the local Termux service environment and invokes one fixed recovery entry point when repeated health failures indicate that recovery is required.

Current development version: `0.1.0-dev2`.


The TEST APK uses `com.shaterguy.termuxwatchdog.test`; the original package is preserved.
Health responses are validated as a stream with constant-size token storage. Catalog size
is not capped. A 4-second overall parsing deadline and bounded nesting prevent unlimited
work; these failures are reported as unknown, never as a stopped server. Only repeated
connection-refused results permit automatic recovery. HTTP errors, timeouts, malformed,
truncated, non-OK, and unreadable responses do not trigger recovery.

Host regression: compile HealthJson, HealthClient, HealthStateMachine, RecoveryContract,
and app/src/test/java/com/shaterguy/termuxwatchdog/HealthRegression.java; run the latter's
main with a 32 MiB heap. Full Android verification: `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
The TEST signing certificate must be retained for subsequent TEST updates. Do not run
old and TEST watchdog monitoring concurrently.
