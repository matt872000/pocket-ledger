# Pocket Ledger handoff

## Current status: 0.10.0, November 11, 2026

Version code 12. Deliverables parent deliverables/0.10.0; prior releases preserved. Build/test/lint successful: 97 Gradle tests and 28 standalone parser checks; lint 0 errors/24 warnings. Same signing key. No connected device/emulator; never claim current on-device testing.

## Current-cycle review and configurable rollover

BudgetCycle is the shared source for period membership, local-midnight boundaries, date-range labels, and short-month clamping. Preferences default to day21 (applies to upgraded installations too); Settings → Monthly rollover allows 1–31. Cycle IDs are the YearMonth in which the cycle starts, so the default November period is Nov21–Dec20. UI, chart grouping/daily series, budget alerts, and categorization reminders all use this helper. Original transaction dates remain unchanged. Pure helper defaults1 preserve calendar-month API/test callers; all app entry points pass the saved setting explicitly.

Needs categorization and its notification include only Uncategorized transactions in the current cycle. Past records are retained and editable through By cycle; no delete/bulk-category action. Pending signatures reset on period/start-day changes so a dismissed old backlog cannot suppress the new one. Budget sent markers include start day (legacy day1 IDs retained). Changing the start day refreshes alerts and may produce newly reached notifications for the new context.

MonthReviewRollover replaces one inexact AlarmManager.RTC alarm when either alert type is enabled; explicit non-exported receiver refreshes under an 8s goAsync timeout. No exact-alarm/boot/new permission. Android can defer notification refresh for battery saving; opening app/next SMS also refreshes. Reboot/clock-zone changes recover on next open/ledger refresh. MainActivity observes lifecycle and delays asynchronously to the next boundary, following current-cycle selection while preserving deliberately viewed older cycles. Settings changes select the new current cycle. Daily chart grouping uses LocalDate keys to avoid merging identical day numbers across clamped periods.

New tests cover calendar-only review, smaller new backlogs, timezone/year/local midnight, all days1–31 through three years including leap February, cross-month chart/spending/alert agreement with refunds/income/exclusions, clamped-cycle repeated day numbers, default/persisted settings, custom alarm replacement, threshold retention across calendar month changes, and setting changes retaining records. Room stays schema4; no dependency changes. Manifest adds only a non-exported rollover receiver.

User authorized publishing source and APK to a NEW PUBLIC repository matt872000/pocket-ledger (public supersedes earlier private choice). Never publish local tools/caches, local.properties, private signing keys, or personal financial data. Source fixtures are synthetic.

## Latest compatibility fix

The user reported a failed card-network credit SMS with explicit year and **网络支付转入…元,交易后余额为…元。** Added the strict NETWORK_INCOME pattern. It returns INCOME, card suffix, exact positive amount/current balance, and explicit timestamp. Both comma forms, optional mask marker, and final full stop are normalized for duplicate identity. New keys are INCOME|CARD|suffix|timestamp|description|amount|balance; existing expense/refund/account-income keys remain unchanged. No schema, dependency, permission, or UI changes. Three parser regression tests and one repository import/balance/spending test were added using synthetic values; a local verification also checked the exact reported message. Tell the user to retry paste import or rescan bank SMS after installing0.9.1.

## Previous refund/payment feature

The user asked for refunds/payments and supplied two Bank of Communications SMS templates: a merchant refund ending in 退款<amount>元，现余额<balance>元, and an account transfer-in beginning 贵账户<account>于<full year/date/time>在<branch>转入资金<amount>元，现余额<balance>元，对方户名：<payer>，附言：<memo>. Do not add real personal identifiers/employer names to source/docs/tests; use synthetic examples.

Implemented automatic recognition for both. Existing expense parser fingerprints stay unchanged; refund/income fingerprints include kind. Incoming messages use their explicit year, display the last four account digits, and retain counterparty/memo only in raw data/description. Card messages retain nearest-year inference from receipt time. BankSmsParser is standalone Java with nested Kind enum; repository maps to Kotlin TransactionKind. Unknown templates/placeholders/malformed numbers/dates remain rejected. Income label is Salary payment for 工资 memo, otherwise Incoming payment; refunds have English payment-channel refund labels.

TransactionKind EXPENSE/REFUND/INCOME is persisted. Amounts remain positive magnitudes; SpendingPolicy.delta yields expense positive/refund negative/income zero with BigInteger. Category exclusions apply independently. Refunds reduce spending on refund date/category and can produce negative totals. Income totals are separate, not offsets or extra budget. Overview and spending charts/alerts share this policy. Six-month bars and cumulative lines support negative values and previous cumulative peaks. Doughnut shares only positive category net values; all net category values remain listed. No original-purchase link or original-month restatement.

Manual Add transaction is available on Overview/Transactions: type, CNY amount, suffix, local date/time, category, optional name/reported balance. Blank balance has hasReportedBalance=false; never invent a zero snapshot. Explicit zero remains valid. Manual UUID fingerprints start manual:, source SMS is blank, and manual entries do not generate SMS rules. They are intentionally distinct and may duplicate a later SMS import; warn in docs. Details can change type/category/name while retaining original amount/SMS/fingerprint/balance. RecordedBalances filters absent observations.

New types work in automatic receipt, paste, and inbox imports. Tell the user to rescan existing bank SMS after upgrade to recover skipped credits. All types participate in Uncategorized reminders (wording now transactions). Refunds clear budget alerts whose thresholds are no longer met; sent monthly markers remain to avoid repeats.

Ordinary CSV includes Type, Spending effect CNY, Counts as spending, and blank unknown balances; Amount CNY is positive magnitude. Firefly CSV keeps columns/IDs, uses negative expenses and positive refunds/income, skips excluded categories, and uses Unknown merchant/Unknown income placeholders. Firefly refund classification may differ from Pocket Ledger; preview carefully. No live server test or synchronization.

## Data and retained behavior

Room schema 4: MIGRATION_3_4 adds transactions.kind TEXT NOT NULL DEFAULT 'EXPENSE' and hasReportedBalance INTEGER NOT NULL DEFAULT 1. Register migrations 1→2→3→4; tests cover old versions and preserve rules/names/exclusion flags/records. No destructive fallback. Category flags from schema3 remain.

- All app UI/copy/notifications English; retain Chinese originals. No INTERNET, account, analytics, ads, app-driven external publication, or paid services.
- Overview balance and Total Spending amounts share displaySmall/Bold. No balance payment-time/disclaimer rows; heading/titleMedium, labels/labelSmall, compact padding. Same-minute conflicting balances remain explicit. Per-card/account-suffix observations selected by parsed time, never row ID/import order; never sum cards.
- Four tabs: Overview0, Transactions1, Charts2, Settings3; notification routes unchanged. Charts has monthly summary/bars, category doughnut, and combined Spending and balance over time; no separate balance-history card. Card selection, shared CNY scale, balance visibility, day slider, budget reference. No reconstructed/missing balance assumptions. Tied conflicting times break guides.
- Settings → Appearance: persisted System(default), Light, Dark, with palettes/charts/system bars adapted. Theme tests cover settings/contrast.
- Settings → Categories: Exclude from spending on every category except protected Uncategorized. User enables it explicitly on their existing transfer category. Applies retroactively/future rules; deletion moves entries to Uncategorized and includes again; recreation defaults included. Excluded entries remain visible and retain balances, omitted from spending and Firefly; ordinary CSV keeps all. No full account-to-account transfer matching.
- Existing names/categories/exact-description future rules, SMS deduplication, inbox/paste/optional background import, budgets, adjustable opt-in alerts (up to10 levels,1–1000%,default80/100), privacy defaults and backup exclusions remain.
- BMO Canada/file-import integration was deferred at user request. No bank connections, FX, standalone balance parsing, generic template guessing, or forecasts.

## Build, signing, verification

Future version codes must exceed 12. Key parent .build-tools/android-user/debug.keystore; preserve it, never log/bundle it. Install over existing app; uninstall deletes data. Parent build-android.ps1 sets local SDK/JDK/cache/key; package-deliverables.ps1 defaults0.10.0 and excludes tools/caches/outputs/local.properties/keys. Source includes Gradle wrapper/JAR/checksum/docs.

Tools: JDK21.0.13+9, wrapper Gradle8.13, AGP8.9.1, Kotlin/Compose2.1.20, KSP2.1.20-1.0.32, Room2.7.0, SDK/Build Tools35.0.0, Java17 target, min26/target35, Robolectric4.14.1 test-only. Local tools/cache/key under parent .build-tools.

Previous 0.9.1 baseline had 83 tests: theme3, balances7, balance-overlay5, spending-charts8, transfer-spending3, transaction-entry6, parser11, CSV9, repository/migrations12, alert-policy5, notification integration14. Standalone ParserChecks passes28 checks; it was updated for new types.

Remaining device checks: current upgrade/persistence, real new SMS receipt/inbox recovery, manual entry and type correction, negative/refund-only/income-only/mixed-card charts, all notification flows, themes/large-font layouts, exports. Live Firefly import awaits a server. Read README/BUILD_REPORT/FIREFLY_IMPORT for behavior and limits.
