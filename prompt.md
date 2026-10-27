# MeterLink-Encore — Field-Report Fixes v2 (exact targets, non-breaking)

Repo: `MeterLink-Encore` (Kotlin, Compose, `org.gurux:gurux.dlms:4.0.97`, `usb-serial-for-android:3.8.1`, package `com.utility.dlmsreader`).
Files in scope (all under `app/src/main/java/com/utility/dlmsreader/`):
`dlms/Is15959Formatter.kt`, `dlms/Is15959DataReader.kt`, `dlms/MeterLinkPdfExporter.kt`, `dlms/Is15959Catalog.kt`, `dlms/GuruxPacketExecutor.kt`, `dlms/DlmsMeterEngine.kt`, `transport/OpticalUsbTransport.kt`, `MainActivity.kt`.

The app works on real L&T meters. **Do not break the session.** Every change below was derived from a real hex log + PDF of an L&T 1P2W meter (serial 92717295, firmware TD00.02).

---

## 0. RULES

1. `git checkout -b fix/field-report-v2`. **Never push to `main`**: `.github/workflows/build-apk.yml` builds and ships a release APK on every push to `main`.
2. Baseline first: `./gradlew assembleDebug` must pass before you change anything.
3. One phase at a time. After each phase: build, run tests, commit `phase N: …`. If anything fails, fix or revert that phase before continuing.
4. **Do not change:** HDLC/SNRM/AARQ/authentication code (`DlmsMeterEngine.establishSession`, `createMeterReaderClient`, `PublicClientDiscovery`), USB setup and baud/Mode-E handling in `OpticalUsbTransport`, scaler/unit maths (`formatValue` scaler path, `loadCompanionScalers`, `isEnergyOrPower`). These are verified correct against raw bytes.
5. No retry loops around authentication.
6. Additive changes only: new fields get default values so existing call sites compile. Don't rename public members; if unavoidable, keep the old one as a wrapper.
7. There is **no test directory yet**. Add `testImplementation("junit:junit:4.13.2")` to `app/build.gradle.kts` and create `app/src/test/java/com/utility/dlmsreader/...` JVM unit tests (the formatter and Gurux are plain Java/Kotlin, so no device is needed). Don't add instrumented tests.
8. Before editing a function, list its call sites in your plan. Mark anything uncertain with `// ASSUMPTION:` or `// TODO:` and list them at the end. Don't invent meanings for unknown codes.
9. Output a plan first (files per phase, call sites) and **wait for my OK** before Phase 1.

---

## PHASE 1 — Timestamp skew (+11 h). HIGHEST PRIORITY

### Root cause (verified in Gurux source)
L&T sends datetimes with deviation `0x014A` (+330). IS 15959 / spec for IST is `0xFEB6` (−330). Gurux builds `meterCalendar` with timezone `GMT-5:30` from the meter's value; the app then calls `getLocalCalendar()` (converts to the phone's IST) → **+11 h**.
Evidence: billing/daily rows print `11:00` but raw bytes are `00:00`; last block-load row raw `28-Dec-2023 21:30` printed as `29-12-23 08:30`; RTC raw 21:36:51 printed 08:36:51 next day; event raw 08-Sep-2021 03:35 printed 14:35.

Gurux has a switch for this: `GXDLMSClient.setUseUtc2NormalTime(Boolean)` (also `GXCommon`/`changeType(raw, DataType.DATETIME, useUtc)`). It flips the sign when parsing **and** when encoding outgoing datetimes (range requests).

### Changes
1. **Display (always, regardless of vendor).** Add `object MeterTime` in `Is15959Formatter.kt` (or a new file) with:
   - `fun wallClock(dt: GXDateTime): Calendar`: returns `dt.getMeterCalendar()` (verify this accessor exists in 4.0.97; fallback: parse the raw 12 bytes manually).
   - `fun format(dt: GXDateTime, compact: Boolean)`: format with a `SimpleDateFormat` whose `timeZone = dt.meterCalendar.timeZone`, so the output is the meter's own wall-clock and independent of the phone's timezone.
2. Replace every `getLocalCalendar()` use:
   - `Is15959Formatter.formatDateTime` and `formatCompactDateTime` (both the `GXDateTime` branch and the `ByteArray` branch)
   - `Is15959DataReader.readIdentityAndNameplate` (`meterRtc = …getLocalCalendar()`) and `readMeterRtc`
   - Keep the year sanity check (2000–2200).
3. **Profile cells:** in `formatProfileValue` the check is `raw is GXDateTime || raw is Date || ByteArray(12)`. First log once (temporary INFO line) the runtime class of clock cells in profile buffers. If they are `Date`, the deviation is lost, so make sure clock cells reach the formatter as `GXDateTime`. If that is impossible, format via the 12-byte path. Remove the temporary log afterwards.
4. **Selective access range (Tier 1):**
   - After the first RTC read, detect the inverted convention: raw deviation `== +330` (positive IST offset). Only then call `client.setUseUtc2NormalTime(true)` on the reader's client. Do not enable it for meters that send `−330` or `0x8000`.
   - Change `meterRtc` from `Date?` to a wall-clock holder (`GXDateTime` or `Calendar` in meter tz). In `loadRecentProfile`, build `start`/`end` from the meter wall-clock (end = RTC, start = RTC − rangeDays) as `GXDateTime` objects, not from `Calendar.getInstance()` in the phone's timezone.
   - Add `readRowsByRange(profile, GXDateTime, GXDateTime)` to `GuruxPacketExecutor` if the Gurux overload exists; otherwise keep the `Date` overload but construct the Dates from the meter calendar's instants. Show me which overload you used.
5. The report header `Generated:` is phone time; label it `Generated (phone time):`.

### Unit tests (`MeterTimeTest`)
Build `GXDateTime` via `GXDLMSClient.changeType(bytes, DataType.DATETIME, false)` (and with `true` for the flipped path). Expected wall-clock output regardless of default timezone (run under `Asia/Kolkata`, `UTC`, `America/New_York`):

| Raw 12 bytes | Expected |
|---|---|
| `07 E6 07 05 FF 00 00 00 FF 01 4A 00` | `05-Jul-2022 00:00:00` |
| `07 E7 0C 1C FF 15 24 33 FF 01 4A 00` | `28-Dec-2023 21:36:51` |
| `07 E5 09 08 FF 03 23 00 FF 01 4A 00` | `08-Sep-2021 03:35:00` |
| `07 E6 07 05 FF 00 00 00 FF FE B6 00` (spec-correct) | `05-Jul-2022 00:00:00` |
| deviation `80 00` | wall-clock as given |

---

## PHASE 2 — Event logs (`Is15959DataReader.readTamperProfile`, `TamperEventRow`)

Verified raw layout of the Current Events log (`0.0.99.98.1.255`), capture list: `[clock, 0.0.96.11.1 code, 1.0.94.91.14 current, 1.0.12.7.0 V, 1.0.13.7.0 PF, 1.0.1.8.0 kWh, 1.0.9.8.0 kVAh, 1.0.11.7.0 phase I, 1.0.91.7.0 neutral I]`, with scaler row `[(-2,A),(-2,V),(-2,none),(2,Wh),(2,VAh),(-2,A),(-2,A)]`.
Raw example `[69, 77, 25917, 83, 46638, 57949, 77, 72]` → code 69, I 0.77, V 259.17, **PF 0.83**, kWh 4,663.800, kVAh 5,794.900, I-neutral 0.72. Another row has PF raw `-89` → −0.89.

1. **PF always "N/A":** `pfIndex = objects.indexOfFirst { descriptor(...).label.contains("PF", true) }` never matches because the label is "Signed Power Factor". Match on OBIS `1.0.13.7.0.255` or `descriptor.unit == "PF"`. Verify that the unit-255 "none" scaler path prints 2 decimals and the sign (`-0.89`). If the unitless path suppresses it, fix it **only for this path**.
2. Add `kvah` and `neutralCurrent` to `TamperEventRow` (with defaults `"N/A"`) and two columns in the Section 7 table in `MeterLinkPdfExporter.buildReport`. Neutral I = OBIS `1.0.91.7.0.255`; kVAh = unit contains `VAh` (be careful: the existing `energyIndex` uses `contains("Wh")`, so pick kWh by OBIS `1.0.1.8.0.255`).
3. **Compartments that are empty or unsupported are invisible.** `readTamperEventLogs` swallows everything with `runCatching{…}.getOrDefault(emptyList())`. Observed on this meter: `.0 Voltage`, `.3 Transaction`, `.4 Other`, `.5 Non-rollover` return 0 rows; `.6 Control` returns data-access-result 4 (object-undefined). Add `eventLogStatus: List<Pair<String,String>> = emptyList()` to `MeterDataReport` (update `merge`), fill one line per compartment ("0 entries" / "not supported by this meter" / "read failed: …"), and print it as a small table above/below Section 7. Treat object-undefined as normal (INFO, not ERROR).
4. For logs whose capture list is only `[clock, code]` (Power Fail, 50 rows in the sample), hide the V/I/PF/kWh/kVAh columns for those rows instead of printing N/A.
5. Catalog: add `1.0.94.91.14.255` → "Current (A)", unit `A` (this is **different** from `0.0.94.91.14.255` Cumulative Power-On Duration), and keep both.

---

## PHASE 3 — Table/header/label fixes (PDF layer, `MeterLinkPdfExporter.kt`, `Is15959DataReader.formatProfile`)

1. **Attribute index is dropped.** `formatProfile` builds columns from `profile.captureObjects.map { it.key }`, so attribute 2 (value) and attribute 5 (capture time) of the same extended register get the same label. This produces:
   - Section 3: a second "Maximum Demand - Active (kW)" row whose "value" is a timestamp, printed with unit kW
   - Billing Part 3: headers `MD Active, MD Apparent, MD Apparent` over data that is actually `MD Active time, MD Apparent value, MD Apparent time`
   Fix: add `attributeIndex: Int = 2` to `ProfileColumn` (read it from the capture object's `value.attributeIndex`). When attribute is 5, label = `"<label> — Capture Time"` with an empty unit; when the same OBIS appears with attribute 2 as well, label the value column `"<label> — Value"`. Section 3 then shows two correctly labelled rows. Don't restructure Section 3.
2. **`drawProfileChunks` splits pairs.** Currently `indexes.drop(1).chunked(6)`. Group each value column with its following capture-time column into one unit before chunking, and don't leave a trailing chunk with 1 column (Billing currently ends with a "Part 4" holding only Reset Type). Keep max 7 columns per table.
3. **Zero-MD sentinel:** when an MD value is `0`, the meter sends a capture time that, after Phase 1, reads `01-Jan-2026 00:00` (about 2 years after the meter clock). Print `—` for the paired capture-time cell when the value cell is zero (both in `formatProfile` and `readRegister` capture timestamps).
4. **Billing column label:** the billing profile's `0.0.94.91.14.255` column holds **per-period** minutes (values 43,889 / 39,631 / 44,015 ≈ days×1440). In `drawBillingProfile` only, display it as `Power-On Duration in Period (min)`. Leave the catalog label and the Section 3 cumulative value alone.
5. **Section 1 duplicates:** `buildReport` prints `identity.rows` and then `drawProfile(layout, identity.nameplate)`, repeating Serial, Manufacturer, Firmware and Year. Filter nameplate rows whose OBIS is already in `identity.rows`.
6. **Numbers wrap mid-digit** (e.g. `4,663.80⏎0`): `wrapText → breakWord` splits any word wider than the cell. In `ReportLayout.table`, when a cell value matches the existing numeric regex, never wrap: shrink the font from 8f down to 6f until it fits, and only then fall back to clipping with `…`. Also give the kWh/kVAh columns more width (`widths(headers.size)`), taking it from the Event Description column.
7. **Billing Reset Type:** `255` is the open/current-period row. Add `// TODO: confirm code 65 against L&T manual` and print `65` unchanged. Don't invent a name. Optionally label the last billing row's date cell `Current (open period)` if the status is 255.
8. **Do not "fix"** the missing `fi` in text extracted from the PDF (`Prole`, `Cong ID`). The catalog string is correct (`"L&T Config ID"`); it is a ligature/extraction artifact. Likewise the missing `-`/`:` in dates when text is extracted. Don't touch fonts.

---

## PHASE 4 — Make row trimming visible

Facts: `readDailyLoadSurvey(maxEntries = 35)` and `readBlockLoadSurvey(maxEntries = 48)` call `trimProfileBuffer`, keeping only the latest N rows. The sample meter held 181 daily rows (05-Feb-2022 → 13-Dec-2023) and the PDF showed 35.

1. Add `totalRows: Int = rows.size` to `ProfileSection`; set it in `formatProfile` **before** trimming (record `profile.buffer.size` before `trimProfileBuffer`, pass through).
2. Under each load-survey table print `Showing X of Y entries (oldest shown: <date>)` when X < Y.
3. Move the limits into named constants (`DAILY_LOAD_MAX_ENTRIES`, `BLOCK_LOAD_MAX_ENTRIES`). Set daily to 400 (the full buffer is downloaded anyway, so more rows cost nothing on the wire); keep block load at 48. Mention this decision in the changelog so I can adjust.
4. Add a one-line note under Block Load: `Timestamps are interval-end. Zero rows before a power restoration are meter zero-fill.`

---

## PHASE 5 — Load-survey cascade (`loadRecentProfile`)

Current code: Tier 1 (range) inside `try`, then `catch (_: Exception)` → full buffer. There is **no entry-index tier** in this repo (despite earlier project notes), and the catch swallows every exception type. On the sample meter, Selector 1 returned data-access-result 3 (read-write-denied) → full download (daily ≈16 s, block load several minutes only to keep 48 rows).

1. Catch only `GXDLMSException` for the tier fallbacks (`readDLMSPacket` already rethrows it before its retry loop). `IOException`, timeouts and `CancellationException` must propagate.
2. Add **Tier 2** using the existing `executor.readRowsByEntry(profile, index, count)`: read `entriesInUse` (attribute 7; fall back to `profile.entriesInUse` after reading attribute 7), then request `index = max(1, entriesInUse - maxEntries + 1)`, `count = maxEntries`. This could cut the block-load read from minutes to seconds if the meter allows it.
3. **Tier 1 variants (max 1 extra attempt):** if Tier 1 returns result 3 and Phase 1 did not flip the sign, retry once with deviation `0x8000` (Gurux `DateTimeSkips.DEVIATION` on the `GXDateTime`). No loops.
4. Cascade order: Tier 1 → (one Tier 1 variant) → Tier 2 → Tier 3 (full). Log each step and result as INFO, e.g. `Tier1 range: denied(3)`.
5. Remember denied tiers for the session (fields on `Is15959DataReader`) so Block Load does not repeat a request the meter already refused for Daily. Keep all existing delays (60 ms before GET, 250/90 ms RR delays).
6. After any Tier 2/Tier 1 success, still call `trimProfileBuffer` (it is a no-op when rows ≤ max).

---

## PHASE 6 — Diagnostics (`OpticalUsbTransport.log`, `MainActivity` line ~202, `diagnosticRows`)

Facts: `MainActivity` keeps only 5,000 trace lines (`traceLogs.add(0, log); if (size > 5000) removeLast()`). In the sample TX 471 + RX 4522 + INFO 7 = 5,000 exactly: the log started mid-block-load, and `diagnosticRows` counted only those 5,000 lines.

1. In `OpticalUsbTransport.log(...)` add per-direction counters (`AtomicLong`) that are reset when a new session starts (in `openPort` or the engine's session start) and never decremented.
2. Add a **non-UI export buffer** in the transport: `ArrayDeque<TraceLog>` capped at 100,000 lines, appended in `log` (thread-safe). Keep the UI `traceLogs` list at 5,000 (it uses `add(0)`, so don't grow it). Export the hex log from the larger buffer.
3. `exportBundle`/`exportHexLog`: add optional parameters (defaults keep current call sites compiling) for counters and dropped-line count; print `Counters (full session): TX=…, RX=…, INFO=…, ERROR=…` and `Log lines retained: N (M dropped)` in Section 8 and in the hex-log header. Label hex-log timestamps `(phone time)`.
4. RR delays: in `GuruxPacketExecutor.readDataBlock`, extract `RR_FRAME_DELAY_MS = 250` and `RR_BLOCK_DELAY_MS = 90` as named constants. **Keep the values.** (Measured: the meter answers each RR in median 32 ms, max 48 ms, and the app waits about 256 ms. SNRM-UA shows window size 1, so each frame needs an RR. Lowering to ~100 ms is a field-test option, not part of this change.)

---

## PHASE 7 — Catalog labels (`Is15959Catalog.kt`)

Block-load rows have 6 fields after the clock: `u16, u8, u8, int8, u16, u16`. Today the first column prints as "Load Survey Object" (catalog line for `1.0.12.27.0.255`) and the others as raw `OBIS …`.

1. `1.0.12.27.0.255` → `Average Voltage (V)`, unit `V` (the u16 raw values like 25484 scale to 254.84 V via the −2 scaler from the companion profile).
2. `1.0.1.29.0.255` → `Block Energy - Active Import (kWh)`; `1.0.9.29.0.255` → `Block Energy - Apparent Import (kVAh)`. Do not change scaling; the existing unit-based ÷1000 logic applies.
3. `1.0.128.8.98.255` → `Vendor Value (L&T) [unverified: looks like meter temperature, °C]`. Observed 24–27 in a December evening and 33 on a July night. Add `// TODO: confirm with L&T documentation`. No unit, no scaling.
4. Keep the existing `1.0.1.6.0` / `1.0.9.6.0` MD entries.

---

## ACCEPTANCE CHECKLIST (report each)

- [ ] `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` pass after every phase.
- [ ] `MeterTimeTest` vectors pass in 3 default timezones.
- [ ] Billing/daily dates show `00:00`, the RTC shows the meter wall-clock, and the load-survey range request is anchored to the meter's wall-clock.
- [ ] Event PF is printed (0.83, −0.89 in the sample); kVAh and neutral current are present; empty/unsupported logs are listed with a status line.
- [ ] Section 3 has no timestamp-as-value rows; Billing headers match their data; value/time pairs never split across parts; no 1-column trailing part.
- [ ] No number cell wraps mid-digit.
- [ ] Section 1 has no duplicate rows.
- [ ] Load-survey tables show "Showing X of Y" when trimmed.
- [ ] The cascade logs each tier; only `GXDLMSException` triggers fallback.
- [ ] Counters survive the 5,000-line UI cap; the exported hex log holds up to 100k lines.
- [ ] `git diff` shows no changes to authentication/SNRM/AARQ/USB-open/Mode-E code, and no changes to the delay values.
- [ ] Changelog plus a list of every `ASSUMPTION` and `TODO`.

Start by posting the plan (files and call sites per phase). Wait for my OK before Phase 1.