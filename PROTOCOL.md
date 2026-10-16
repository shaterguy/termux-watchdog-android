# Lifecycle v1 protocol

Protocol for the dev10/dev11 APK and matching lifecycle-v1 Termux helper. Isolated
fixtures and release compilation are separate from live shutdown/start acceptance.

External fixed entry: /data/data/com.termux/files/home/bin/termux-watchdog-lifecycle

Arguments: v1 ACTION UUID GENERATION EXPECTED_UID. ACTION is exactly prepare-stop,
commit-stop, prepare-start, or check-start. No shell text, paths, signal numbers,
or target process IDs are accepted. GENERATION is 1..9007199254740991. UUID is a
lowercase canonical UUID. EXPECTED_UID comes freshly from Android PackageManager;
the helper also independently resolves com.termux for its current Android user.

Exactly 16 response fields, ASCII JSON plus LF, at most 4096 bytes:

schema, action, command_id, command_sequence, state, boot_id, termux_uid,
observed_processes, supervisor_count, enabled_registrations,
running_registrations, registration_errors, login_observed,
final_stop_confirmed, error_code, observed_at_ms.

schema = termux-watchdog-lifecycle-v1. Counts are nonnegative integers.
final_stop_confirmed is always false. This channel never proves full termination.
boot_id is a lowercase kernel UUID; it may be empty only for REJECTED.
termux_uid may be zero only for REJECTED. observed_at_ms is a nonnegative integer.

States: STOP_PREPARED, STOP_COMMITTED, READY_TO_LAUNCH, START_OBSERVED,
START_PARTIAL, REJECTED.

Errors: BAD_COMMAND, PROTOCOL_MISMATCH, UID_MISMATCH, PACKAGE_UID_UNAVAILABLE,
BOOT_ID_UNAVAILABLE, STATE_INVALID, STATE_IO, BUSY, STALE_COMMAND, SAVER_ACTIVE,
SAVER_STATE_UNAVAILABLE, PREPARE_EXPIRED, PIDFD_UNAVAILABLE, PROC_UNREADABLE,
SERVICE_STOP_UNAVAILABLE, SERVICE_STOP_FAILED, PARENT_EXIT_TIMEOUT, STOP_TIMEOUT,
START_TIMEOUT, LOGIN_NOT_OBSERVED, DUPLICATE_SUPERVISORS, ORPHAN_SUPERVISORS,
SUPERVISOR_UNAVAILABLE, REGISTRATION_ERRORS, RESUME_FENCE_FAILED, INTERNAL.

Stop: Android persists its same-boot off gate before prepare-stop. It sends
commit-stop only after accepting the current STOP_PREPARED PendingIntent result.
Both actions use the same command_id and command_sequence. A detached child
waits for the commit process to exit before doing any stop work. No late callback
is required or interpreted as successful full termination. Android retains its
off gate and UNKNOWN/OFF_REQUESTED display after STOP_COMMITTED or timeout.

Start: prepare-start must finish before Android launches Termux Activity. This
user-triggered bootstrap saves a resume fence and changes the Termux-side latch
to STARTING without running login or startup. Activity supplies ordinary login.
check-start uses the same command ID/generation and observes a new login's first
prompt, a single standard supervisor, and current registrations. It never runs
boot-only scripts, restores a previous service list, or deletes down files.

prepare-stop and prepare-start both reject non-normal/transitioning saver state.
The saver request guard and lifecycle admission share the existing mode lock.
Execution, startup serialization, and state files use distinct bounded locks.

Budgets: prepare 8 seconds (Android 12); prepared stop eligibility 20 seconds;
commit-parent exit 2 seconds; detached stop 20 seconds; startup observation window
20 seconds; one check-start waits at most 12 seconds (Android 16). Callback loss
does not extend a deadline or count as success. Boot identity failure is closed.

Independent limitations: the final local receipt is pre-final, not an assertion
that no process remains; reading it is allowed only during explicit start.
External apps or user-direct launches are not OS-blocked. A shell reaching its
first prompt does not prove every arbitrary user startup command succeeded.
Registration runtime counts and first-prompt evidence remain separate.

## dev11 compatible clock correction

dev11 changes only the boot-elapsed time supplier from a /proc/uptime read to
time.clock_gettime(time.CLOCK_BOOTTIME). The proc argument is retained for caller
compatibility but is not read or dereferenced by boot_time. Missing function or
constant, OSError, ValueError, nonfinite values and negative values remain closed
with BOOT_ID_UNAVAILABLE. No CLOCK_MONOTONIC or wall-clock fallback is allowed.

CLOCK_BOOTTIME includes suspended time. Existing started_monotonic values already
represent boot-elapsed seconds, despite the historical field name, so no timestamp
conversion or state migration is introduced. The existing 20-second boundaries
and shell start_ticks/SC_CLK_TCK comparison remain unchanged. The finer clock
precision does not relax the rule that a new shell must be later than the stored
preparation tick. The independently read kernel boot_id still governs physical
reboot expiration; a clock reset or failure cannot clear the intentional-stop gate.

The sixteen-key schema, action/state/error enums, pidfd rules and startup/saver
behavior are unchanged. Isolated clock fixtures model suspend and timestamp/tick
boundaries; they do not substitute for physical device suspend or live lifecycle
acceptance tests. Python's clock semantics are documented at
https://docs.python.org/3/library/time.html#time.CLOCK_BOOTTIME .
