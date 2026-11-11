# Pocket Ledger 0.10.0 build report

Built November 11, 2026 on Windows. Version 0.10.0, version code 12; minSDK26, targetSDK35. Deliverables: parent deliverables/0.10.0. Earlier releases preserved.

## Changes

Settings → Monthly rollover now accepts days1–31, default21 at local midnight. Overview totals, transaction browsing, charts, budget thresholds, and categorization review use the same monthly cycle. Short months clamp to their last day; date-range labels show both endpoints. Changing the day recalculates views and refreshes alerts without changing transaction dates or deleting history.

The categorization notification and review filter include only the current cycle's Uncategorized transactions. Older records remain editable in By cycle. Old dismissed-backlog state resets at rollover, so smaller new backlogs still appear. One replaceable inexact non-wakeup alarm refreshes enabled alerts at the next boundary; the foreground app follows the boundary and app-open/ledger changes refresh notifications too. Android may defer background notification cleanup for battery saving. Reboot/clock-zone changes recover on next app open or ledger refresh. No exact-alarm or boot permission.

Daily graph aggregation spans the true date range and keeps repeated day numbers in different months separate. Refunds, income, exclusions, bank-reported balance observations, dark mode, English UI, and existing export/import behavior remain. Room remains schema4 with existing tested migrations; signing key retained.

## Verification

`gradlew.bat testDebugUnitTest assembleDebug lintDebug` succeeded.

| Check | Result |
| --- | --- |
| Gradle unit/Robolectric tests | 97 passed; 0 failures/errors/skips |
| Standalone Java parser | 28 checks passed |
| Lint | 0 errors, 24 warnings |
| APK | com.pocketledger; code12 / 0.10.0; min26 / target35 |
| Signature | Verified; same certificate as 0.9.1 |
| Connected device/emulator | None |

Cycle regressions cover local midnight/timezones, year/leap/short-month boundaries, all possible start days, cross-month net spending agreement across charts/alerts/overview, refunds/income/exclusions, alarm replacement, notification backlog rollover, setting changes retaining records, and once-per-cycle threshold state. Existing parser/repository/migration/theme/entry/export/balance tests continue to pass.

Lint warnings are seven dependency notices, thirteen Kotlin preference/helper suggestions, two intentional immediate sent-alert writes, one API-level simplification, and one optional monochrome icon improvement. No build errors.

## Install and publication

Install the APK over the existing app; do not uninstall first, because uninstalling deletes its ledger. The saved budget and data remain; the default reporting window changes to the21st–20th. Set Monthly rollover to1 if calendar months are desired. No current physical-device install, rendered UI, real SMS, background timing, or live Firefly test was possible here.

Public source repository: https://github.com/matt872000/pocket-ledger. Source/tests use synthetic fixtures. Local caches, SDK/JDK, local.properties, private keys, and personal financial data are excluded. APK/source hashes are in SHA256SUMS.txt. The published APK uses the existing local debug signing key so it can update earlier builds; cloning and building with a different debug key produces a different signer.

Certificate SHA-256: fcaf7cf82a4369513007fc3b682581d216d8c5f1f884ca802167e05a87018769. Keep the private key locally for future updates; never publish it.

Toolchain: JDK21.0.13+9, Gradle8.13, AGP8.9.1, Kotlin/Compose2.1.20, Room2.7.0, SDK/BuildTools35.0.0. Parent build-android.ps1 configures local tools; package-deliverables.ps1 excludes outputs/caches/local settings/keys.
