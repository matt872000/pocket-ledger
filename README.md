# Pocket Ledger

An English-language Android budgeting app for personal use. [Download the installable APK](https://github.com/matt872000/pocket-ledger/releases/latest). Bank of Communications SMS messages are parsed in Chinese; all app copy and category names are English. The original SMS is retained in transaction details.

## Features in version 0.10.0

- Kotlin / Jetpack Compose interface with Overview, Transactions, Charts, and Settings.
- Local Room database, with no internet permission, analytics, or account.
- Paste a supported expense, refund, or incoming-payment SMS, or grant SMS access to import existing messages. Manual entry is also available.
- Optional automatic import of new supported SMS, disabled initially.
- Exact integer-cent amounts in CNY and duplicate detection.
- Per-transaction categories and optional exact-description rules for future imports; rules can be removed.
- Add and remove English category names in Settings. Removing a category moves its purchases to Uncategorized and removes rules using it; Uncategorized cannot be removed.
- Rename individual purchases in transaction details. The original Chinese SMS and duplicate fingerprint are preserved. Clear the name to restore the English payment label.
- Monthly spending, category totals, and a recurring monthly spending limit. Any category except Uncategorized can be excluded from spending, for example your custom transfer category.
- Bank-reported balances per card on Overview, with amounts matching Total Spending in size and weight. Overview omits payment timestamps and the disclaimer. Balance history appears only in the spending-and-balance line graph. Uses balances already saved from imported SMS.
- Charts tab with a six-cycle spending bar chart, category doughnut, and interactive cumulative spending line with a blue bank-reported balance overlay. Includes card selection, a balance visibility switch, exact day details, and an optional budget reference line.
- Opt-in budget notifications at adjustable percentage levels, and one grouped notification for transactions needing categorization in the current cycle. Older uncategorized records remain in history.
- Transaction deletion and CSV export using Android's document picker.
- Firefly III CSV export with CNY spending, custom purchase names, categories, card account labels, and stable external IDs. No server or API credentials are needed to export. See [FIREFLY_IMPORT.md](FIREFLY_IMPORT.md).
- Android cloud backup disabled.

## Build and install

Requirements: JDK 17 or 21, Android SDK platform 35, and Android SDK Build Tools 35.0.0. Android Studio is optional. The Android Gradle Plugin is pinned to 8.9.1 and the included wrapper downloads Gradle 8.13 with SHA-256 verification.

From this directory run:

```sh
sh ./gradlew testDebugUnitTest assembleDebug
```

On Windows, use `gradlew.bat testDebugUnitTest assembleDebug`. Set `JAVA_HOME` to your JDK and `ANDROID_HOME` to your SDK, or supply `sdk.dir` in an untracked `local.properties`. The wrapper scripts and JAR are included; a separate Gradle installation is unnecessary. Run `gradlew.bat lintDebug` for Android static analysis.

Open this directory in Android Studio, let it sync, connect your Android phone with USB debugging enabled, and select Run. Alternatively install `app/build/outputs/apk/debug/app-debug.apk` with `adb install -r`.

Minimum Android version: 8.0 (API 26). SDK location is supplied by Android Studio in your untracked `local.properties`, or via `ANDROID_HOME`.

Version 0.10.0 is signed with the same local debug key as previous versions. Install the new APK over the existing app to retain your ledger. Do not uninstall first: uninstalling deletes the data. Room schema is version 4. Tested upgrades from versions 1, 2, and 3 preserve records, names, categories, rules, exclusions, and expense fingerprints. Existing records retain Expense type and their reported balances.

The app keeps its interface, date labels, and framework controls in English even when the phone language is Chinese. Original bank messages remain Chinese in transaction details. Android's own permission prompts, keyboard, and document picker follow the phone's system language.

For sideloaded apps, some Android versions or manufacturers may require enabling restricted settings in the app's Android settings before SMS access can be granted. The app never needs to become your default SMS app. Paste import works without SMS access.

## Monthly cycles

Settings → Monthly rollover chooses the start day (1–31), default **21**. Cycles begin at **00:00 in the phone's time zone**, so the default cycle runs from the 21st through the 20th. Days missing from a short month use that month's final day. Choose 1 for calendar months.

The same boundaries apply to Overview, Transactions → By cycle, charts, budget thresholds, and Needs categorization. Date-range labels show both endpoints. Changing the day immediately recalculates views and refreshes alerts; original transaction dates and stored records stay unchanged. The single saved budget applies to each cycle. Balance snapshots still use actual reported times.

Needs categorization includes only Uncategorized transactions in the current cycle, including refunds and incoming payments. At rollover, older records leave the notification/review filter and remain editable through By cycle. A replaceable inexact, non-wakeup alarm refreshes enabled alerts at the next boundary. Android battery saving can defer notification cleanup; opening the app or importing a message also refreshes it. No exact-alarm permission is requested. After reboot or a clock/time-zone change, the next app open or ledger refresh schedules the next boundary. The foreground app also follows the boundary.

## First use

1. Open the app and choose **Import a bank message**. Paste a real numeric message; the placeholders in the original example cannot be parsed.
2. Open the transaction from **Transactions**, enter an optional **Purchase name**, select a category, and save.
3. Optionally select **Use this category for future matching descriptions**. The rule affects future imports only.
4. In **Settings**, manage categories, set your monthly CNY budget, and optionally enable automatic SMS import or import existing bank SMS.
5. Use **Export all transactions to CSV** for a readable ledger or **Export for Firefly III** for the Data Importer format. You can save exports before setting up Firefly III. CSV restore is not implemented.
6. In **Settings → Notifications**, optionally enable budget alerts and/or Needs categorization. Grant Android notification permission when requested. Set custom percentage levels and save them. Background alerts for newly received SMS also require automatic SMS import to be enabled.
7. Open **Charts** to explore monthly spending. Change the selected month or tap a monthly bar, tap a category legend to highlight its share, and tap the running-total graph or move its slider to inspect a day.
8. **Overview → Latest reported balances** shows the latest retained SMS balance for every card, independently of the selected spending month. In **Charts → Spending and balance over time**, select a card and inspect the selected day with the graph or slider.

## Incoming-message compatibility fix in 0.9.1

The parser also recognizes the card credit wording **您尾号…的卡于YYYY年MM月DD日HH:mm网络支付转入…元,交易后余额为…元。** Both comma forms and an optional mask marker are accepted. The explicit year is preserved, amounts remain exact, and the record is an Incoming payment rather than spending. Existing message fingerprints remain stable. Install the update, then paste the failed SMS again or use **Settings → Import existing bank SMS**. Unknown formats still remain rejected.

## Refunds and incoming payments

Version 0.10.0 automatically recognizes the two Bank of Communications formats supplied by the user: card messages ending in merchant + 退款 + amount + 现余额, and 贵账户 + account + explicit timestamp + branch/transfer-in + 转入资金 + amount + 现余额 + counterparty + memo. Account messages display only the last four account digits; full account details remain only in the original SMS. Salary memos receive an English Salary payment fallback label. Expense fingerprints retain their original format; refund/incoming fingerprints include their type.

After updating, use **Settings → Import existing bank SMS** to recover previously skipped supported credits/refunds. Duplicate expense messages remain duplicates. Card messages infer a year from SMS receipt time; incoming messages use their explicit year. Unknown formats are not inferred.

Use **Add transaction** on Overview or Transactions to record an Expense, Refund, or Incoming payment manually. Enter CNY amount, card/account ending digits, date/time in the phone's timezone, category, optional name, and optional reported balance. Blank balance means unknown and never changes the reported-balance chart. A genuine zero balance remains valid. Manual entries are deliberately distinct; avoid also importing the same activity by SMS unless you remove the manual duplicate.

Transaction details can change the type as well as the category/name, without changing the original SMS, amount, balance observation, or fingerprint. Manual records do not create SMS category rules. Transaction rows distinguish positive incoming/refund amounts from outgoing expenses.

Spending equals **expenses minus refunds**, with each refund counted on its own date. Select the original purchase's category to offset the same category; there is no automatic purchase matching or original-month restatement. Refunds can make a day, category, or month negative. Incoming payments are shown separately and do not reduce spending or increase the monthly budget. Category exclusions still apply. Budget alerts use net spending and clear obsolete reached-threshold notifications after a refund. The line and bar charts support negative net totals; doughnut shares use positive category totals after refunds, with net-refund categories listed separately.

## Transfer categories

Open **Settings → Categories**, find your existing transfer category, and enable **Exclude from spending**. This immediately applies to all current transactions in that category and future imports assigned to it, including imports using saved rules. Category names are not guessed; no category is excluded automatically on upgrade.

Excluded transactions stay in Transactions with an **Excluded from spending** label, retain original SMS, amounts, names, and fingerprints, and still supply bank-reported balances. They are omitted from Overview spending, category totals, spending charts, and budget/alert calculations. Even a transfer-only month retains its balance line and card selector. Turn exclusion off or recategorize to a spending category to include them again. Deleting the category moves its transactions to Uncategorized, so they count as spending again. Uncategorized itself cannot be excluded.

Ordinary CSV retains all transactions and adds **Counts as spending** (Yes/No). Firefly III CSV skips excluded categories because a genuine transfer needs a destination asset account, which this app does not store. Changing exclusions does not delete earlier Firefly imports.

Exclusion applies to expenses, refunds, and incoming payments in that category. Incoming payments are always separate from spending. This does not link two accounts or reconstruct balances. BMO integration/file imports remain deferred.

## Appearance

In **Settings → Appearance**, choose **System**, **Light**, or **Dark**. The default follows your phone. Changes apply immediately and persist across app restarts. Cards, dialogs, charts, and system-bar icons adapt to the selected theme. Theme changes preserve the selected tab and financial data. All app UI stays in English.

## Bank-reported balances

The parser already stores the balance after `现余额` in each supported spending SMS. The balance features read those existing fields; no re-import is needed. Overview selects each card's latest balance by parsed SMS payment time, across all months, rather than import order or row ID. No combined balance is calculated across cards, because cards may represent the same account.

These are last-reported SMS snapshots, not a live bank connection. The exact payment time is available in transaction details and the combined line graph, and later deposits, refunds, payments, or messages not imported may change the real account balance. Supported refund and incoming-payment SMS supply their reported balances too. Manual entries only supply a balance when one is explicitly entered; unknown balances never become zero snapshots. Standalone balance formats remain unsupported. Renaming/categorizing preserves balances; deleting the newest retained record makes Overview fall back to the previous retained SMS.

SMS times have minute precision. If the newest minute contains different reported balances for the same card, the app shows all distinct values and marks the order unclear instead of guessing which is latest. Equal balance values in that minute are unambiguous. The combined line graph places conflicting observations at the same time without connecting through them. Dashed guides between other snapshots are visual aids, not estimates of balances between SMS records. Single, zero, and unchanged balances remain valid observations. Dates use the phone's timezone and amounts stay exact in CNY.

## Charts

The **Spending and balance over time** graph overlays blue reported balance observations for one selected card on cumulative spending across all cards. Both use a shared CNY scale. Select a card, or turn **Show reported balance** off to focus the scale on spending. Tap the graph or use the day slider to inspect that day's spending and last reported balance, including its SMS payment time. All observations are plotted at their payment times; days without a message explicitly have no reported balance. Dashed blue guides connect observations, do not estimate intervening balances, and break at conflicting same-minute values. There is no separate balance-history card above the combined graph.

Spending charts omit excluded categories; balance observations retain all local imported records. Amounts are summed in integer cents with BigInteger to prevent overflow, then formatted in English/CNY. Categories include Uncategorized, and dates use the phone's timezone. Renaming a purchase does not affect its amount; categorizing, removing categories, importing, and deleting purchases update charts through the existing Room data flow.

The six bars cover the selected cycle and the five preceding cycles, including empty periods. Labels identify the month in which each cycle starts. Tap a bar to select that cycle. **Show cycle totals** provides exact amounts. The summary compares complete recorded totals with the previous cycle; an in-progress cycle is not extrapolated or adjusted to match elapsed days.

The category doughnut has a tappable legend with exact amounts, shares rounded to one decimal place, and purchase counts. The spending-over-time graph shows the cumulative total and exact per-day details; the dashed budget line uses the current recurring monthly budget. In the current cycle, the line stops at today or the latest imported purchase date if later. Completed cycles include every day in their date range, with no-purchase days carrying the total forward. Empty periods show explanatory messages. Refunds reduce the plotted net spending; incoming totals are separate. No forecast is calculated.

Synthetic supported example, with no real financial information:

```text
【交通银行】您尾号*1234的卡11月01日14:31在支付宝支付宝-消费网上支付28.50元，现余额1,234.56元
```

## Parsing boundaries

Supported Bank of Communications templates are card payment, card refund, and the demonstrated account transfer-in with explicit year, counterparty, and memo. Other credit/debit formats, foreign currencies, other banks, incomplete messages, and unknown suffixes are skipped rather than guessed. Inbox import reports skipped bank messages. Automatic receipt processing saves supported messages and checks enabled alerts; inbox import can recover missed messages.

## Notifications

Both alert types are off by default, including after upgrading. Budget levels default to `80, 100`; enter up to ten whole percentages from 1 to 1000, such as `50, 80, 100, 125`. Levels over 100% warn about overspending. Threshold comparisons use exact integer arithmetic. Alerts exclude categories marked Exclude from spending and use spending in the current cycle in the phone's timezone and require a positive monthly budget.

Each reached level alerts once for that cycle, start-day setting, and budget amount, with sent levels retained across restarts. Crossing multiple levels together sends one alert for the highest and records all reached levels. A newly added level or changed budget amount can alert immediately if already reached. Historical imports only contribute to the cycle of the transaction. Inbox imports refresh alerts once after the batch, and duplicate SMS imports do not generate another alert.

Needs categorization tracks Uncategorized transactions in the current cycle in one notification. Past cycles can remain uncategorized and retain all their records. Tap it to open the filtered Transactions list. The count updates as purchases change and clears when none remain. It includes existing backlogs when enabled and purchases returned to Uncategorized by category removal. Saved rules that already assign a category do not create categorization alerts. Dismissing the notification is respected while you reduce the backlog; new uncategorized purchases can notify again.

Amounts are absent by default. An optional switch includes spending and budget amounts in budget notifications; original SMS, purchase names, and card details are never shown. Both alert types provide a generic public lock-screen version. Android's own notification privacy settings also apply. Settings includes a link to Android notification/channel controls. Notification denial does not prevent imports or other ledger operations.

Alerts are evaluated after imports, purchase/category changes, budget/rollover/alert settings changes, when the app resumes, and by the inexact cycle-boundary refresh. There are no scheduled daily reminders, bank polling, Internet access, or foreground services. Timely background SMS alerts depend on Android allowing SMS delivery and automatic import; see the existing SMS restrictions above.

The parser uses the SMS receipt timestamp to infer the nearest valid year. For pasted messages, that timestamp is the current time, so use inbox import for historical messages. Dates and monthly grouping use the phone's timezone.

Generic Alipay descriptions do not identify actual shops or purchased items. The UI displays an English payment label unless you name the purchase, preserves the raw SMS, and warns that a generic description rule can categorize unrelated purchases together. Category rules affect future categories only; purchase names are individual edits. Splitting purchases and keyword/AI suggestions are not implemented.

Transactions deduplicate using card digits, payment minute, original description, amount, and balance. Two genuinely distinct payments with all those fields identical cannot be distinguished from this SMS format. SMS descriptions and sender labels are not proof of authenticity; this is a personal ledger, not an authoritative account statement.

Data lives in Android app-private storage. Database-level encryption and app biometric lock are not implemented. Uninstalling clears the ledger. SMS permission grants access at Android's scope; the app queries Bank of Communications inbox messages and saves only the supported fields and original bank SMS. Export includes English payment labels, categories, card suffixes, amounts, balances, and dates; treat it as financial data.

## Verification status

Run the actual Java parser without Android dependencies:

```sh
sh ./tests/check-parser.sh
```

**Passed here: 28 parser checks**, including exact currency arithmetic, description extraction, deduplication, year boundaries, malformed values, invalid dates, and the supported credit/refund formats.

Verified on Windows on November 11, 2026: `gradlew.bat testDebugUnitTest assembleDebug lintDebug` succeeded. All **97 Gradle tests passed**, with no failures, errors, or skips. They cover parser/repository/migrations, charts/balances, refunds/income/transfer exclusions, themes, entry/export, cycle date boundaries and short months, and notification integration on Robolectric API 28/33. The standalone **28 parser checks** also passed. Lint reports **0 errors and 24 warnings** (dependency notices, preference style suggestions, immediate sent-alert writes, an API-level simplification, and optional icon improvements). See [BUILD_REPORT.md](BUILD_REPORT.md).

Earlier app versions run on the user's phone. No phone/emulator was connected for this update. Version 0.10.0 rendering, touch interactions, installation/upgrade, real SMS receipt, notification delivery and background rollover timing remain unverified on-device. Live Firefly import awaits a server. Passing JVM/Robolectric tests does not establish those device behaviors.

Before relying on it, build the app and verify on your phone: fresh install, denied and granted SMS permissions, multipart Chinese SMS, background receipt, repeated inbox imports, category persistence across restart, rule deletion, budget persistence, and CSV export. Confirm imported totals against a bank statement.
