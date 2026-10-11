# Auth process reuse implementation plan

Goal: lower phone/tablet Termux authentication overhead without loss of Google authorization or failed requests during deployment.
Architecture: keep existing Binder identity checks and one-shot sessions; reuse only Android runtime processes. Prewarm supervised alternate brokers before atomic local socket routing changes.
Tech stack: Termux native Node 24, Java 21 compiler / Java 17 target, official Android 36 SDK and D8.
Spec: user-approved battery proposal in 2026-10-11 conversation plus explicit requirement to preserve authentication on both servers.

## Constraints and scope
- No APK replacement, force-stop, consent reset, revocation, scope expansion or credential-file modification.
- Keep 60-second access recheck, rejected-token invalidation, request-specific refresh deduplication and current peer signature checks.
- Node timeout 9500ms, Java hard deadline 8000ms per exchange; no deadline while idle.
- Reuse at most four native children, idle exit after 300000ms; no heartbeat/wake lock.
- Work only in dedicated Git worktree; source baseline a3e452ff7569df3f7f3109099387a46f5d509b49 plus exact installed broker hash 9af448fcd155914752a56463e3a0250dc2a3a47413cc6be57331fe1482ef6e6a.
- Other conversations are modifying Drive Worker/SelfRun; do not patch/restart those services or overwrite their work.
- Ruling: status-only cosmetic polling is not present in inspected collector, so do not invent a caller or extend the status cache; preserve semantics.
- Ruling: standby polling change is deferred because worker is concurrently being changed and restarting it conflicts with continuity priority.
- PR-003: reconcile runtime version, DEX pin and build/test identity together. APK dev9 identity unchanged.
- Independent reviewer CLI is not installed; do not label inline review as independent.

## Review focus
Framing resynchronization after early handshake failure; hung/dead children; refresh replay; race during socket routing replacement; source/build pin integrity.

## Task 1: persistent transport
[x] Reproduce two sequential native requests launching two processes using a fake Android runtime; baseline fails with 2 != 1.
[ ] Add bounded pool tests for reuse/concurrency/timeout/bad frame/idle/closure.
[ ] Refactor BridgeCli to --persistent B1 marker mode; every exchange repeats nonce/UID/signature handshake before reading credential bytes; legacy mode remains.
[ ] Replace per-call spawn with pool while preserving validation/cache rules.
[ ] Build standalone DEX; run native tests and existing relevant Java/Python tests.

## Task 2: auth-continuous deployment
[ ] Test atomic UDS routing with in-flight requests and invalid candidate; old route remains until new readiness passes.
[ ] Stage versioned files and supervised temporary broker; validate actual Google Drive read without printing credentials.
[ ] Route to ready staged broker atomically, drain old connections, update original service run to unique socket and retire drained old PID without its path-unlink handler.
[ ] Validate original service's new broker, route atomically, drain and remove only this patch's temporary service.
[ ] Keep known-good files and transactional receipt/rollback route; abort before mutation if preconditions fail.

## Task 3: rollout and source preservation
[ ] Commit and preserve exact tested source in non-default version branch on GitHub; read remote SHA.
[ ] Apply phone first then tablet. Record checksums, process reuse, continuity request totals and Google API statuses.
[ ] Report operational evidence; actual discharge percentage remains unmeasured.

Ruling: Termux Python/coreutils do not provide a usable Unix-socket hardlink here. Deployment therefore uses a supervised previous-version relay and atomic symlink routing, not a hardlink backup or a permission workaround.
