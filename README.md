# Finora

A private, offline-first personal finance app for Android. Finora records transactions from
bank and UPI SMS, learns your merchants from your own confirmations, splits bills from scanned
receipts, tracks loans and lending with principal and interest kept apart, and values a stock
and mutual-fund portfolio from prices **you** enter every two weeks.

- No account, no server, no cloud sync, no AI, no ads, no analytics SDK.
- Every financial calculation works with no network.
- The network is used only for an optional, user-triggered merchant search and map preview
  (OpenStreetMap; no API key, no paid service).
- Indian Rupees by default; money is stored as integer paise everywhere.

See [PRIVACY.md](PRIVACY.md) for exactly what stays on the phone and what does not.

---

## Build

### Requirements

| Tool | Version |
|---|---|
| JDK | 17 |
| Android SDK | Platform 35, Build-Tools 34/35 |
| Gradle | 8.11.1 (wrapper included) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 (with the Compose compiler plugin) |
| KSP | 2.0.21-1.0.28 |
| minSdk / targetSdk | 26 / 35 |

Android Studio is **not** required. On any machine with JDK 17 and the Android SDK:

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

```bash
./gradlew testDebugUnitTest
```

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Instrumentation tests (need a connected device or emulator):

```bash
./gradlew connectedDebugAndroidTest
```

A release build (`./gradlew assembleRelease`) is minified with R8 but unsigned; add your own
signing config in `app/build.gradle.kts` to install it.

### Rebranding

- App name: `app/src/main/res/values/strings.xml`
- Application id: `finoraApplicationId` at the top of `app/build.gradle.kts`
- Icon: `app/src/main/res/drawable/ic_launcher_*.xml`
- Colours: the two brand ramps at the top of `ui/theme/Theme.kt`

---

## Dependencies

All open source, all stable, versions pinned in `gradle/libs.versions.toml`:

| Library | Version | Used for |
|---|---|---|
| Jetpack Compose BOM | 2024.12.01 | UI (Material 3) |
| Navigation Compose | 2.8.5 | Screen navigation |
| Lifecycle | 2.8.7 | ViewModels, lifecycle-aware state |
| Room | 2.6.1 | Local SQLite database, migrations |
| WorkManager | 2.10.0 | Local reminders |
| Kotlin Coroutines | 1.9.0 | Async work, Flow |
| ML Kit Text Recognition (bundled) | 16.0.1 | On-device receipt OCR; the model ships inside the APK |
| osmdroid | 6.1.20 | OpenStreetMap map preview |
| JUnit 4, Robolectric 4.14.1, Room testing | — | Tests |

No Google Play services, Firebase, analytics, crash reporting or advertising libraries.

---

## Architecture

```
ui/            Jetpack Compose screens + ViewModels (MVVM)
data/db/       Room entities, DAOs, database, migrations
data/repo/     Repositories: every multi-step write runs in one Room transaction
domain/        Pure Kotlin business rules, no Android imports — the unit-tested core
  sms/         Parser (modular rules), classifier, duplicate detection
  merchant/    Normaliser, three-confirmation learning policy, place ranking
  loan/        Interest (simple/compound), payments, EMI schedules
  split/       Bill splitting to the exact paisa
  invest/      Holdings, average cost, gains, valuation history, SIP schedule
  analytics/   Summaries, categories, trends, recurring, unusual-spending rules
  csv/         RFC 4180 CSV and the transaction CSV format
  receipt/     OCR text -> draft receipt
platform/      Android-specific adapters: SMS receiver, location, place search, OCR,
               encryption, document vault, backup, reminders
AppContainer   The composition root (manual dependency injection)
```

### Key rules the code enforces

- **A debit is not automatically an expense; a credit is not automatically income.**
  `TxnType.countsAs` is the single place that decides. Own-account transfers, card-bill
  payments, ATM withdrawals, loan principal, lending and repayments, investments, bill
  settlements and reversals are all *neutral*. A payment to or from a person is held for review
  rather than guessed.
- **Duplicates are impossible at the database level**: each transaction has a deterministic
  fingerprint in a UNIQUE column (by reference, else by message text), and a second check
  catches the same payment announced by both the bank and a UPI app in different words.
- **Three-confirmation merchant learning**: the 1st, 2nd and 3rd payments to a merchant ask for
  confirmation; from the 4th, the learned merchant and category apply automatically with no
  question and no map lookup — only for an exact match. A correction is never counted as
  confirming the mapping it replaced. A name that is only *similar*, or one you marked as shared
  by several businesses, always asks.
- **Prices are manual.** There is no market-data, broker or price API anywhere. A holding with
  no price has no value (not ₹0); every value shows the date of the price behind it; prices
  older than 14 days are flagged stale.
- **Loans keep principal and interest separate**; payments clear accrued interest first, then
  principal; the assumptions are shown on every loan.
- **Bill splits reconcile to the paisa** using largest-remainder allocation; any difference from
  the printed total must be explicitly accepted.

---

## Features

| Section | What it does |
|---|---|
| Home | Spend, income, savings and savings rate for this month / last month / this year; known balances (from SMS or entered, each with its date); category donut; review queue; lending totals and dues; portfolio value with price dates; upcoming reminders; recent activity |
| Transactions | Search by merchant, person, note, category, reference or amount; date, type and category filters; day groups; long-press bulk categorisation (expenses only); detail with type, category, purpose, person, links to loans/bills, documents and the original SMS; manual entry |
| Review | Grouped decisions: new merchants (with optional nearby search), merchants still learning (progress 1/3, 2/3), ambiguous names, payments to/from people (expense, lent, repaid, borrowed, bill settlement, own transfer), unexplained credits; unreadable bank messages |
| Bills & splitting | Receipt scan (camera or gallery, on-device OCR), editable items assigned to people, equal/custom/itemised modes, proportional tax/service/discount, payer, live preview of shares, settlements against the bill (manual or from an SMS credit) |
| Loans & lending | Lent / borrowed / formal loans; none, simple or compound interest; payments, additional amounts, interest-only, waivers; outstanding principal and interest; overdue detection; EMI and reducing-balance schedule; close/reopen; attached documents |
| Investments | Stocks, mutual funds, ETFs, other; buys, SIP instalments, sells, redemptions, dividends, splits, bonus units, unit adjustments; average cost, realised and unrealised gain; allocation; value history at each update; SIP plans vs actual |
| Portfolio Update | Every holding with its last price and age, one field each, one Save; blanks are skipped; full price history per holding |
| Analytics | Monthly income/spending/net/savings rate, 12-month cash flow, categories, top merchants, recorded net worth, recurring payments, rule-based "worth a look" insights with their numbers, 30-day obligations estimate |
| Documents | Encrypted vault for receipts, agreements, statements, bills and warranties; tags, notes, search, preview, links to transactions, people, loans, bills and investments |
| Settings | Theme; SMS capture and inbox import; own UPI ids/names for transfer detection; two daily review reminders (2:30 PM, 9:30 PM) and a 14-day portfolio reminder; location, lookup server and map preview; learned merchants (edit, correct, reset); categories; people; accounts and manual balances; recurring bills; encrypted backup/restore; CSV export/import; privacy |

---

## Known limitations

- **SMS formats vary by bank and change over time.** The parser handles the common Indian
  wordings (see `SmsParserTest`) but will not read every message. Anything that looks financial
  and cannot be read goes to Review instead of being guessed.
- **Google Play** restricts SMS permissions; Finora is designed for personal side-loaded use.
- **Reminder times are targets**: Android's Doze and battery optimisation can delay them.
- **The main database is not encrypted by Finora** (documents and backups are). See PRIVACY.md.
- **Realised gains use the average-cost method**, a tracking aid, not a capital-gains tax
  computation (which uses FIFO in India).
- **Restore replaces all data**; there is no merge restore. CSV import is the merging path.
- **OCR is a draft**: receipts vary widely and the result always needs a look.
- **Current location is not where you paid** for older transactions; Finora says so and asks.
- Currency is INR only.

---

## Verification (what was actually run)

Built on macOS (Apple M1, 8 GB) with JDK 17.0.21, Gradle 8.11.1 and Android SDK 35, from a
clean build:

| Command | Result |
|---|---|
| `testDebugUnitTest` | **110 tests, 0 failures** (JVM + Robolectric, SDK 34) |
| `assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` (63.6 MB, debug-signed) |
| `assembleDebugAndroidTest` | Instrumentation tests compile |

| Suite | Tests | Covers |
|---|---|---|
| SmsParserTest | 19 | UPI/card/NEFT/ATM/refund/reversal/card-bill/fees/GST/balance wordings; OTP, promo, collect-request and failed messages ignored; future dates distrusted; malformed input never throws |
| ClassifierAndDedupTest | 20 | Own-account transfers, salary, people vs merchants, EMI, disbursement, investments, refunds/reversals; fingerprints; cross-format and same-amount duplicate rules |
| MerchantLearningTest | 9 | 1st–3rd ask, 4th automatic; corrections are not confirmations; learned corrections override the future; similar names and shared names always ask; aliases |
| LoanCalculatorTest | 10 | Simple and compound interest, interest-first partial repayments, principal never counted as interest, overpayment, EMI formula, schedule ends at zero |
| BillSplitterTest | 7 | Itemised/equal/custom, proportional tax/service/discount, exact-paisa rounding, accepted rounding, partial settlements |
| PortfolioCalculatorTest | 9 | Units, average cost, realised/unrealised, % gain, stale prices, no-price = no value, splits/bonus, biweekly valuation history, SIP dates |
| AnalyticsTest, CsvTest, ReceiptParserTest, ReminderTimingTest, PlaceRankingTest, MoneyTest | 18 | Neutral types never count, refunds, savings rate; CSV round-trip and formula safety; OCR draft; reminder times; place ranking; money parsing/format/allocation |
| RepositoryIntegrationTest | 16 | Real Room database: duplicate SMS, bank+UPI-app duplicate, reversal, refund linking, own-transfer pairing, the full 3-confirmation flow, corrections, renamed merchants, unreadable messages, loan and bill-settlement links never double counted, CSV re-import, place-lookup failure and cache, offline manual entry |
| MigrationTest | 1 | Real v1 database with rows migrated to v2 and validated against the exported schema |
| UiSmokeTest | 1 | Launches `MainActivity`, imports SMS through the real pipeline, opens every section |

**Not run here:** the instrumentation tests (`connectedDebugAndroidTest`) need a device or
emulator, and none was available. The APK was not installed on a physical phone; SMS
reception, the camera, GPS, the keystore and notifications therefore have not been exercised
on real hardware. `lint` was not run.
