# Termux Watchdog

Android watchdog app that monitors the local Termux service environment and invokes one fixed recovery entry point when repeated health failures indicate that recovery is required.

Current development version: `0.1.0-dev3`.

This development APK updates the existing `com.shaterguy.termuxwatchdog` app.
Successive development versions retain the original package and dedicated signing
certificate so Android can preserve the existing app data and granted permissions.
Do not uninstall the existing app to apply this update.

Health responses are validated as a stream with constant-size token storage. Catalog size
is not capped. A 4-second overall parsing deadline and bounded nesting prevent unlimited
work; these failures are reported as unknown, never as a stopped server. Only repeated
connection-refused results permit automatic recovery. HTTP errors, timeouts, malformed,
truncated, non-OK, and unreadable responses do not trigger recovery.

Host regression: compile HealthJson, HealthClient, HealthStateMachine, RecoveryContract,
and app/src/test/java/com/shaterguy/termuxwatchdog/HealthRegression.java; run the latter's
main with a 32 MiB heap. Full Android verification: `gradle :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease`.
The release packaging variant is used for this non-debuggable development APK. Sign it
with the existing dedicated development key on its original device; do not create a key,
use an Android Debug key, or transfer private signing material.
