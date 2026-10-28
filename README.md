<p align="center">
  <img src="https://github.com/user-attachments/assets/e4146f25-2cd7-442c-b7ca-74dcdf752cfb" alt="MeterLink" width="75%" />
</p>

<h1 align="center">MeterLink</h1>

<p align="center">
  Read Indian DLMS/COSEM smart meters (IS 15959) from an Android phone over a USB optical probe.<br/>
  No login. No server. No internet permission. Everything stays on the handset.
</p>

<p align="center">
  <a href="../../releases/latest"><img alt="Download APK" src="https://img.shields.io/badge/DOWNLOAD-APK-1565C0?style=for-the-badge" /></a>
</p>

---

## Get the app

1. Open **[Releases](../../releases/latest)**.
2. Tap the newest `MeterLink.apk` under **Assets**.
3. Open the downloaded file. Android will warn about installing from an unknown source — allow it for your browser or file manager.
4. Launch MeterLink, plug in the optical probe, and tap **Scan USB**.

**Requirements:** Android 8.0 (API 26) or newer, and a phone with USB host (OTG) support. A USB-C or micro-USB OTG adapter is usually needed.

> The APK is a debug build, signed with the standard Android debug key. That is why the "unknown source" warning appears. It is expected.

---

## What it does

Plug an optical probe onto the meter's IR port, point the phone at it, and MeterLink pulls the meter's stored data and produces a report you can hand to a supervisor or file with a utility.

**Reads seven data groups, individually or all at once:**

| Group | What you get |
|---|---|
| Identity | Logical device name, serial number, manufacturer, firmware, meter RTC |
| Energy & MD | Cumulative kWh, kVAh, reactive energy Q1–Q4, maximum demand |
| Instantaneous | Voltage, current, power factor, frequency, per-phase values |
| Billing history | Billing profile with ToD zones 1–8, import and export |
| Block load | 15-minute interval load survey |
| Daily load | Daily load survey |
| Tamper logs | Voltage, current, power-fail, transaction and other event logs |

**Load survey ranges:** Recent (2 days block / 15 days daily), 36 days, 45 days, or 75 days. If the meter refuses selective access, MeterLink falls back through entry-range and full-buffer reads and still filters to your requested window.

**Exports:**
- **PDF** — formatted multi-page report with every section, plus a diagnostic summary.
- **Excel (.xlsx)** — seven worksheets, one per data group, with real numeric cells you can sum and chart.
- **Hex log (.txt)** — the full protocol trace, byte for byte, for when something does not work and you need to show someone why.

All three are shared through the normal Android share sheet — email, WhatsApp, Drive, whatever you use.

---

## Supported hardware

**Optical probes** — any USB-serial probe built on these chips:

- Silicon Labs CP210x (`0x10C4`)
- FTDI FT232R / FT231X (`0x0403`)
- QinHeng CH340 / CH341 (`0x1A86`)
- Prolific PL2303 (`0x067B`)

**Meters** — IS 15959 / IEC 62056 DLMS-COSEM meters using HDLC over the optical port. Vendor password presets are included for L&T / Schneider, Genus Power, Secure Meters, HPL Electric and AVON, plus a field for any custom password.

**Client access** — Public client (16) for identity reads, Meter Reader (32) with LLS authentication for full data. IEC 62056-21 Mode E (300 baud 7E1) handshake is supported for meters that require it.

---

## Using it in the field

1. **Connect the probe.** Plug it into the phone, then tap **Scan USB**. The status card should read *USB Cable Detected*.
2. **Align the probe head** on the meter's IR port. It needs to sit square — a few millimetres off and you get nothing.
3. **Wake the meter** if the display is asleep. Most meters need a button press to power the optical port.
4. **Pick your vendor** and, if needed, tick *Auto-try alternate vendor passwords*.
5. **Tap a read button.** Start with **Read Identity** — it is the quickest way to confirm the link works before pulling a full dump.
6. **Export** when the green "Meter data ready" card appears.

**If nothing happens:** open the live protocol trace at the bottom of the screen. If you see TX bytes and no RX, the probe is not seeing the meter — realign it, or press the meter button. If you see nothing at all, the USB permission was probably denied; tap **Scan USB** again and accept the prompt.

---

## Privacy

- **No internet permission.** The app cannot send anything anywhere. There is no analytics, no telemetry, no crash reporting, no account.
- **No cloud.** Every read, every report, every file stays in the phone's app storage until you share it yourself.
- **Backup disabled.** `allowBackup` is off, so meter data is not swept into Google backups.

The only way data leaves the device is when you tap a share button.

---

## Known limitations

Worth knowing before you take it to a site:

- **Debug build.** Not release-signed. Fine for field use, not for Play Store distribution.
- **USB only.** No Bluetooth, no Wi-Fi, no TCP/IP meter access.
- **One probe at a time.** The app uses the first matching USB serial device it finds.
- **Meter behaviour varies.** Indian meters differ in which OBIS objects they expose and whether they permit selective access. MeterLink falls back automatically, but some meters will return less than others.
- **Vendor passwords are defaults.** If a meter has been re-provisioned with a site-specific password, use the *Custom Password* option.
- **Not a billing instrument.** The report is a convenience export. Verify anything you intend to act on against the meter's own display or your utility's records.

---

## Building from source

```bash
git clone https://github.com/professordoom58-art/MeterLink-Encore.git
cd MeterLink
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

Requires JDK 17 and the Android SDK (compileSdk 35). Built with Kotlin and Jetpack Compose; DLMS parsing is handled by [Gurux DLMS](https://github.com/Gurux/Gurux.DLMS.Java) and USB serial by [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android).

Run the tests with:

```bash
./gradlew testDebugUnitTest
```

---

## Releases

Every push to `main` builds and publishes a new APK automatically. Version numbers follow the CI run number (`v1.0.<run>`), so they always increase and always install over the previous one.

See [Releases](../../releases) for what changed.

---

## Thanks

MeterLink is built on [Gurux DLMS](https://github.com/Gurux/Gurux.DLMS.Java) — open source DLMS/COSEM client code. Thanks to the Gurux team for keeping this information available to everyone.

---

<p align="center">
  <sub>Built for field engineers who need the meter's numbers, not another dashboard.</sub>
</p>
