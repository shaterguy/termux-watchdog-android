# Termux Watchdog 0.1.0-dev13

Baseline: 2ebbe4e1ca2e244ec41bc714217dd1a193facc79 (0.1.0-dev12).
Request: Termux Watchdog 앱도 디자인 다시 구성해줘 0.1.0 dev12 기준.

## Design and acceptance

- Status, Connection and History navigation: monitoring and server health first; power control on Status; existing authorization and alarm controls on Connection; recent recovery and the same eight incidents on History.
- Neutral surfaces, restrained green accent, system Korean typography, 52dp minimum action targets, persistent navigation and Android system-bar/cutout insets.
- At 600dp, navigation becomes a side rail. At 840dp, the Status service/power sections use two columns. Large fonts revert to stacked content.
- System dark appearance is supported. Selected page survives activity recreation; views remain mounted during status refresh, preserving page scroll.
- Existing app ID, dedicated signer and increasing versionCode continue the dev12 update lineage.
- Monitoring state machine, recovery, power transitions, authorization transport, permissions, refresh intervals and persisted operational data remain unchanged.
- No Termux termination controls or backend changes.

## Scope and verification

AC-01: native presentation redesign and logical navigation.
AC-02: small/large screens, landscape, dark theme, large text and insets.
NR-01: all dev12 controls remain available with the same listeners and prerequisites.
NR-02: unchanged backend source, manifest, dependencies, authorization and power contracts.
REL-01: package/signing lineage, 100013 / 0.1.0-dev13, signed installable APK and remote source preservation.
Actual build/test results and runtime limitations are recorded in the delivery evidence; unobserved device layouts are not reported as runtime verified.

Design guidance: Unslop by Damir Sokolovsky (@heydamir),
https://github.com/heydamir/unslop, CC BY 4.0.

Build compatibility: explicitly enable resValues used by the existing release app name and release unit tests in AGP 9.3.1. No dependency or runtime contract changes.

Two source-wiring assertions in StaticSecurityTest now check the redesigned permission/log section titles. All scheduler, stop-state, permission, Binder and security assertions are retained.

Validated on Galaxy-Tab-S11-Ultra: release JVM tests 24/24, no failures/errors/skips; lintRelease has zero errors; assembleRelease succeeds. No ADB device was connected, so live phone/fold/tablet screenshots and interaction execution remain unverified.
