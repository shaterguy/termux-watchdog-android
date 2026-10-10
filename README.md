# Termux Watchdog

Android watchdog app that monitors the local Termux service environment and invokes one fixed recovery entry point when repeated health failures indicate that recovery is required.

Current development version: `0.1.0-dev8`.

This development APK updates the existing `com.shaterguy.termuxwatchdog` app.
Successive development versions retain the original package and dedicated signing
certificate so Android can preserve the existing app data and granted permissions.
Do not uninstall the existing app to apply this update.

The **Google 인증** button obtains the existing full Google Drive authorization scope
in Google's own consent UI. Play services owns the grant and token cache. Watchdog
requests access on demand; it does not save refresh tokens, access-token files, or
credential logs. Reauthorization only opens after an explicit foreground button tap.
The exact Google Cloud Android package and signing-certificate registration must
exist; this source change does not create that configuration or grant access.

## Native authorization transport

The normal build implements on-demand Binder status/access/refresh by default.
There is no separate activation setting required after the explicit Google consent
button. Each connection must complete real mutual UID/package/current-signer
verification and HELLO before credential bytes are exchanged. Unsupported platform
APIs, failed identity checks or missing consent fail closed with safe distinct errors.
There is no native auth foreground service, direct cross-app Unix listener, TCP token
endpoint, token file, refresh-token storage, periodic refresh loop or new permission.

The standalone `BridgeCli` must run only from the reviewed, hash-pinned Termux-private
DEX built by `tools/build_bridge_cli.py`. It never executes the currently installed
Watchdog APK as unverified code. The callback carries only a Binder offer or a public
nonce readiness notice. The authenticated client fetches one response through a
separate freshly verified Binder call; tokens never go into broadcast extras or a
callback. Credentials use private local stdin/stdout pipes only, not argv, environment,
files or diagnostic logs. Full contract: `BINDER-CONTRACT.md`.

The public PHONE and TABLET Termux SHA-256 observations are fixed in
`auth-trust.properties`; APK and CLI builds use the same bounded set. Every reported
owner of a UID must be in the exact known seven-package list, and all must have one
current signer matching the same allowed signing family as com.termux. Mixed families,
missing metadata, unknown owners and wrong/reassigned UIDs fail closed. No static UID
pin or trust-on-first-use occurs. Termux GitHub signing keys are public: this verifies
the installed Android sandbox principal, not publisher authenticity.

The **절전모드** button drains accepted work, then stops the Termux token broker last.
Enabling requires a correlated, accepted v2 controller status response received in
this app process, with less than 30 seconds since its dispatch and no newer transport
error. An absent, incompatible or stale controller leaves normal monitoring unchanged
and shows that saver integration needs a status check. UI and command registration
both enforce this prerequisite. Restore from a blocked state does not require this
readiness evidence; the existing single-flight pending-command rule still applies.
Google consent, health telemetry and optional supervisors are not readiness conditions.
A managed keeper is cooperatively stopped by its own work adapter; runsvdir is retained.
The backend restores only previously enabled services and checks the signed native
bridge before restoring a previously running broker. A broker that was already
stopped does not force a new Google grant. Google's grant does not require a resident
Watchdog service. Verified settled saver blocks native credential delivery too.

Mode wire v2 has 15 exact fields. It records Android auth ownership, actual broker
liveness and shared_wakelock_state:'ownership_unknown_no_release'. The shared Termux
wake-lock owner cannot be established, so saver does not arbitrarily release it.
Previously enabled services may reacquire it through their existing startup paths.
This is not a claim of zero wake locks, battery improvement, or force-stop equivalence.
Old v1 or incomplete v2 observations cannot release the recovery gate. Old persisted
saver intent migrates conservatively; a process alone never proves a valid Google grant.

Frames are exact JSON plus LF and EOF, <=16,384 ASCII bytes including LF. Bearer values
are <=8,192 bytes. Status is passive and contains no token. Expiry is unknown, not
fabricated. Refresh clears the rejected token before one authorize; coalesced stale
rejections cannot clear a newer issued value. SDK completion is bounded to 5 seconds,
receiver lifetime to 7 seconds, and the CLI exits itself by 8 seconds (exit124 on a
hard deadline). A queued operation cannot start another SDK flight after its remaining
receiver budget expires. Stop/pause and final token reply share an epoch-based delivery gate. A pre-stop
result cannot be fetched after re-enabling. Late callbacks cannot revive a closed session.

Development verification has exercised host seams and source wiring. SDK compilation,
on-device Binder round trip and account authorization are separate evidence; source
success does not establish them. MANUAL diagnostic jobs must use probe/status only.
Never run access/refresh through diagnostic jobs whose stdout could become a result
file. Real token use is only the user's app-to-local-broker path.

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
