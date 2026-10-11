# Termux Google Auth runtime 1.0.6-dev1

This component updates the Termux broker and standalone bridge only. The installed Watchdog APK remains 0.1.0-dev9; no APK installation, Google grant reset, new scope, or consent UI is required.

The Android process pool is lazy (maximum four children), waits on input without polling and retires idle children after 300000ms. Each exchange still performs a fresh, single-use Binder nonce/UID/current-signer handshake before credential bytes are read. The existing 60000ms access recheck, 2000ms status cache, refresh rejection and backoff semantics remain unchanged. Java's 8000ms hard deadline and the broker's 9500ms deadline apply per exchange, not to idle lifetime. Errors retire the stream without replay.

Install all runtime text files owner-only (0600), the runtime directory 0700, and bridge.dex **0400 before execution**. Android refuses writable dynamically loaded DEX; the broker now rejects owner-writable DEX before launch as well as checking its fixed path, owner and SHA256. The included DEX is a derived artifact; its exact sources and digest are in build-manifest.json and it can be rebuilt with tools/build_bridge_cli.py using the official Android 36 SDK, javac 21 targeting Java 17, and D8.

`health` is a local diagnostics-only operation. It reports no token or Google ready state and never triggers a native authorization call.

## Rolling deployment
`deploy.py --apply` requires the candidate to be separately staged and validated. A temporary **previous-version** supervised broker is warmed and receives the public Unix-socket route while the original process drains. Only then is the original runit service updated. The new service is warmed on its own path, validated with a read-only Google Drive request, and atomically routed into use. The temporary broker is removed only after that succeeds. In-flight requests finish before retirement. Rollback keeps the previous-version relay available until a supervised previous-version replacement is ready. Atomic symlink routing is supported by the shared consumer and survives backend socket inode changes after restart. No Unix hardlinks are required on Android.

Every transaction writes a redacted receipt under ~/.local/state/auth-power-106. Re-execution requires inspecting the receipt; never blindly repeat a partly completed deployment. The token record is not modified or copied. No refresh/revoke operation is issued by the deployer, and tokens remain in memory for their intended Google request only.

## Verification
- Red/green native reuse regression: two requests previously launched two runtimes; now one.
- 20 Node tests covering cache, concurrency, rejection, backoff, malformed frames, timeouts, idle cleanup and closure.
- 11 Java frame-boundary cases; strict legacy EOF contract retained.
- 5 handoff tests including 200 continuous requests across atomic switches, in-flight completion, bad candidate rejection, restart routing and compare-and-swap protection.
- Existing NativeAuth, BridgeHandshake, AuthBinderSession and AuthLifecycle regression programs.
- Tablet standalone canary: five real native requests, one Android process, zero failures, Google Drive HTTP 200.
- On-device deployment and post-deployment evidence is recorded separately per server. Actual battery discharge improvement is not claimed by source tests.
