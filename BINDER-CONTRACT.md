# Watchdog Binder contract, dev7 / 100007

Normal build has the adapter enabled. There is no hidden user activation requirement.
Every connection proves current mutual identities and completes a real HELLO before
credential exchange; unsupported API/runtime failures fail closed. The optional
nativeTokenTransportEnabled=false build override is diagnostic-only, not required
configuration for normal use. User opt-in/Google consent remains a separate UI action.

## Bootstrap and identities

Trusted, separately reviewed/hash-pinned standalone DEX under actual Termux UID.
Never bootstrap by executing an unverified installed APK. No additional app package.
The helper first verifies its own current UID/owners/signers and the installed Watchdog
package. Official TermuxAm route: ActivityManager.getService → getIntentSender(type1,
packageName com.termux, one-shot | cancel-current | immutable) → IIntentSender.send.
Explicit component com.shaterguy.termuxwatchdog/.NativeAuthReceiver and action
com.shaterguy.termuxwatchdog.AUTH_BRIDGE_V1. Exactly two extras: callback Binder and
32-byte random lowercase-hex challenge. No token, PFD or claimed UID in the broadcast.

Callback descriptor com.shaterguy.termuxwatchdog.IAuthBootstrap.v1, oneway only:
- code1 OFFER: version int1, challenge String, server endpoint IBinder.
- code2 READY: version int1, challenge String. No result or credential.
The CLI captures Binder.getCallingUid before any identity change, fresh-verifies
Watchdog package/UID/current signer, and checks exact challenge/parcel/trailing bytes.
Watchdog fixed SHA256:31b19b19e934e1d32b570160e4252aef7b27935a45bb3a6ea56cc5f18f395a54.

Endpoint descriptor com.shaterguy.termuxwatchdog.IAuthBridge.v1. Synchronous calls
start with version int1 and challenge String. The server captures actual Binder UID
and fresh-verifies it before reading any credential payload. Every later call must
match the UID verified in this session. Same-UID processes are one Android principal.

## Endpoint transactions

1. HELLO: no extra payload. Reply writeNoException, version int1, challenge String,
   Watchdog UID int, requester UID int, token adapter implemented/enabled int0/1.
   This flag does not claim a valid Google grant. HELLO is allowed exactly once.
2. REQUEST: byte[] containing one exact AuthProtocol frame including LF. Reply
   writeNoException and String: empty means accepted, otherwise safe error code.
   One operation only. Google work is async with incoming Binder identity cleared to the app identity
   for SDK initiation and restored afterward. A ready notification contains only the public challenge.
3. FETCH: no extra payload. Fresh peer check again, one destructive retrieval. Reply
   writeNoException and byte[] frame. Local disable or settled saver prevents token
   delivery even if SDK completion already happened. Result reference is cleared.
4. CLOSE: no extra payload. writeNoException. Used to end a no-token probe or abort.

Parcels <=1024 bytes except REQUEST/FETCH frame parcels <=17408. Byte frame itself
<=16384 bytes including LF. Exact trailing-data rejection. Wrong descriptor/version,
UID, challenge, replay, operation order, deadline or owner/signature is denied.

Receiver admission uses up to4 OS-attributed verified sessions and a separate single
identity-unavailable compatibility slot. API34+ bootstrap sends BroadcastOptions
shareIdentity in IIntentSender.send options; receiver reads getSentFromUid before
goAsync and validates a known UID before allocating a slot. Missing identity does
not bypass Binder checks or permanently disable compatibility. OEM runtime identity
availability is still a device check. Anonymous fallback can suffer denial of service;
it cannot consume the four attributed slots or authorize credential disclosure.
Receiver deadline is7 seconds, with a fresh elapsed-time check before final write,
and finish once on every terminal path.
SDK authorize/clear overall <=5 seconds. New provider flight requires >=5.5 seconds
remaining, including when a different rejection was queued behind an existing flight.
A common delivery monitor linearizes preference changes, provider admission,
revocation epochs, final deadline/policy checks, Parcel credential serialization and
terminal cleanup. Already-ready pre-stop responses cannot survive stop/re-enable.
Every confirmed settled saver observation revokes delivery; pending native SDK
flights are invalidated without cancelling unrelated foreground-only consent.
Coordinator futures complete outside its monitor to avoid a callback/gate inversion.
Late SDK completions cannot revive closed sessions. No FGS, polling, wake lock or alarm.
Client soft failures emit sanitized JSON. At8 seconds it halts itself with exit124,
without a potentially blocking stdout write. Backend maps exit124 to AUTH_TIMEOUT
and does not mark a child drained before its actual exit. No external SIGKILL fallback.

## stdin/stdout

No argv. The helper establishes HELLO before consuming stdin credentials. One ASCII
JSON+LF followed by EOF. Exact operations:
- {"version":1,"op":"status"}
- {"version":1,"op":"access"}
- {"version":1,"op":"refresh","rejectedAccessToken":"<bearer>"}
- Diagnostic-only {"version":1,"op":"probe"}

status/access/refresh request keys are order-independent; duplicate/unknown keys,
invalid encoding, trailing data and extra frames are rejected. Token <=8192 ASCII
bytes, pattern [A-Za-z0-9._~+/-]+=*. No token in argv/environment/files/logs.

Success access/refresh:
{"version":1,"ok":true,"accessToken":"<bearer>","expiryKnown":false}
Success status (no Google call; no token):
{"version":1,"ok":true,"state":"<state>","expiryKnown":false}
States ready, reauth_required, not_ready, unavailable, not_configured. ready means
last SDK success, never an assertion that a grant remains valid forever.
Errors:
{"version":1,"ok":false,"error":{"code":"<safe code>"}}
Codes AUTH_REAUTH_REQUIRED, AUTH_NOT_CONFIGURED, AUTH_UNAVAILABLE, AUTH_TIMEOUT,
BAD_REQUEST, PEER_DENIED. Error replies use exit2; normal success exit0. Every frame
ends LF then process exit. The CLI validates canonical reply shape and ensures a
status operation can never forward a token response. Unknown expiry is not invented.
Probe success has version1,oktrue,transport binder,peerUid,requesterUid,challenge,
tokenTransportEnabled boolean. It proves only this no-credential round trip.

Token stdout is a sensitive private broker pipe, not diagnostic output. MANUAL jobs
must use probe/status only. Never attach token stdout to Results or other reports.
Google consent and actual account requests are not performed by source/build tasks.

## Package trust and build

Both observed Termux current public signers live in auth-trust.properties; every
reported UID owner is exact-known and must share one current signer matching the
same signing family as com.termux. No UID is pinned; all PM metadata is re-read.
Known exact owners: com.termux, com.termux.api, com.termux.boot, com.termux.window,
com.termux.styling, com.termux.tasker, com.termux.widget.
The public GitHub signing key is not publisher authentication; the trust boundary is
the installed Termux UID sandbox. Missing metadata or unsupported hidden API fails.

IPackageManager uses ActivityThread.getPackageManager, getPackagesForUid(int),
getPackageInfo(String,long,int); user ID derives from actual process UID. The SDK36
shape compiled in the dev6 diagnostic; actual SDK36/37 behavior remains a device gate.
Standalone builder uses --android-jar, --d8, --out(empty), and exactly one --javac or
--java JDK compiler-module route. Optional public pin override; defaults match APK.
No Google SDK classes are compiled into the helper. Output manifest records sources
and DEX SHA256. Provenance/hash validation happens before any helper code execution.

Official source basis:
- https://github.com/termux/TermuxAm/blob/master/app/src/main/java/com/termux/termuxam/IActivityManager.java
- https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/app/IActivityManager.aidl
- https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/content/IIntentSender.aidl
- https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/app/ActivityThread.java
- https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/pm/ComputerEngine.java
- https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()

Sender identity sources (API34+; no added permission):
- https://developer.android.com/reference/android/app/BroadcastOptions#setShareIdentityEnabled(boolean)
- https://developer.android.com/reference/android/content/BroadcastReceiver#getSentFromUid()

The standalone manifest marks token_transport_enabled:true / token_adapter_implemented:true
for helper capability, google_sdk_included:false, and supported_operations. Those
capability fields do not assert current server opt-in, account grant or runtime health.
