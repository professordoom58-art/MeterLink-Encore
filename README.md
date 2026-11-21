<p align="center">
  <img src="https://github.com/user-attachments/assets/e4146f25-2cd7-442c-b7ca-74dcdf752cfb" alt="MeterLink" width="75%" />
</p>

<h1 align="center">MeterLink</h1>

<p align="center">
  Read your smart meter from your Android phone.<br/>
  No login. No server. Your readings stay with you.
</p>

<p align="center">
  <a href="../../releases/latest"><img alt="Download MeterLink" src="https://img.shields.io/badge/DOWNLOAD-METERLINK-1565C0?style=for-the-badge" /></a>
</p>

---

## Download MeterLink

**The latest MeterLink app is available below.**

1. Open **[Releases](../../releases/latest)**.
2. Download the newest **MeterLink** app file.
3. Open the downloaded file and allow installation when Android asks.
4. Connect your optical cable, open MeterLink, and tap **Scan USB**.

Works on Android 8.0 or newer with a phone that supports a USB cable connection.

---

## What MeterLink does

- Finds your optical cable automatically.
- Reads your meter's identity, energy, billing, load and tamper records over the
  universal **DLMS / COSEM (IEC 62056)** optical interface.
- Handles **net metering (bi-directional / rooftop solar)** meters: export energy,
  export maximum demand and the `import − export` net energy are read and
  reported with standard OBIS codes only, and uni-directional meters simply skip
  the export objects.
- Normalizes base scaler units once (`Wh` → `kWh`, `VAh` → `kVAh`, `W` → `kW`,
  `VA` → `kVA`) so imported files and live reads always report in kWh/kVAh/kW/kVA
  with three-decimal precision in JSON, PDF and Excel.
- Imports meter files from phone storage in several formats: MeterLink JSON
  reports, tagged OBIS CSV exports, and framed binary dumps with an `S$S`
  header.
- Shows every reading clearly on your phone, whether it came from the meter or
  from an imported file.
- Saves each reading on your phone for later, with GZIP-compressed SQLite
  storage and a readable JSON backup in `Documents/MeterLink`.
- Exports a signed PDF or Excel report you can share. Both include the
  **Generated & Verified by MeterLink** signature block and a clear source
  label (`Optical DLMS / COSEM` or `Imported File`).
- Keeps your saved readings safe even if you delete the app.

MeterLink works with meters from L&T/Schneider, Genus, Secure, HPL and Avon, and
with any meter or file export that follows the common DLMS / COSEM (IEC 62056)
OBIS conventions. If your meter brand is not listed, you can enter its password
yourself.

### Importing a meter file

1. Open the **Saved Meter Readings** card.
2. Tap **Import from Storage**.
3. Choose a `.json`, `.csv`, `.txt`, `.dat` or `.bin` file. The picker accepts
   any file type and MeterLink identifies the format from the file's content.
4. The reading is parsed, displayed, and saved in `Documents/MeterLink` just
   like a live optical read.

Tagged CSV files may contain `Name plate details`, `Programmable datas`,
instantaneous, billing, daily-load and block-load OBIS/value/scalar sections
ending in `,END`. Twelve-character hexadecimal OBIS codes are converted to
canonical dotted notation, and `<exponent>|<unit>` scalars are applied while
timestamp columns keep their original text.

---

## Your privacy

MeterLink does not need the internet. Nothing is sent anywhere. Your readings stay on your phone until you choose to share them.

---

## Using MeterLink

1. Connect the optical cable to your phone.
2. Tap **Scan USB**.
3. Place the cable head on the meter's light port.
4. Choose your meter brand.
5. Tap a reading button.

If nothing happens, check that the cable head is sitting flat on the meter and that the meter's display is awake.

---

## Good to know

- MeterLink is a helpful field tool, not a billing instrument.
- Different meters may show different amounts of data.
- If your meter uses a special password, choose the custom password option.

---

## Thanks

MeterLink is made possible by the open-source Gurux library.

---

<p align="center">
  <sub>Made for field engineers who need the meter's numbers, not another dashboard.</sub>
</p>
