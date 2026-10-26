<img width="1678" height="937" alt="readme" src="https://github.com/user-attachments/assets/e4146f25-2cd7-442c-b7ca-74dcdf752cfb" />

<p align="center">
  <img src="docs/banner.png" alt="MeterLink — Android DLMS/COSEM Optical Reader" width="100%" />
</p>

# MeterLink

Production-grade Android application built with **Kotlin** and **Jetpack Compose** for communicating with electricity meters via USB optical probes using the **DLMS/COSEM** protocol (`IEC 62056` / `IS 15959`).

## Features

* **USB Optical Hardware Integration:** Auto-detects CP210x, FTDI, CH340, PL2303, and CDC/ACM probes without root access, asserts `DTR/RTS` to power optical IR LEDs, and supports optional `IEC 62056-21 Mode E` (`300 7E1` wakeup $\rightarrow$ `9600 8N1` HDLC).
* **Resilient DLMS/COSEM Engine:** Handles preemptive `DISC`, `SNRM/UA`, `AARQ/AARE`, multi-frame HDLC (`RR`) and COSEM `DATABLOCK` transfers with 3x automatic CRC/timeout retries.
* **Identity & Metrology Extraction:** Reads Logical Device Name, Serial Number, Manufacturer, Firmware Version, Real-Time Clock, Cumulative Active Energy (`kWh`), Voltage, Current, and Power Factor with automatic Scaler/Unit resolution.
* **Live Hex Diagnostics Terminal:** Real-time color-coded `TX/RX` frame inspection with millisecond timestamps and one-tap clipboard export.

## Tech Stack

* **UI & Concurrency:** Kotlin 2.1+, Jetpack Compose (Material 3), Coroutines & StateFlow
* **DLMS Core:** `org.gurux:gurux.dlms:4.0.97`
* **USB Transport:** `com.github.mik3y:usb-serial-for-android:3.8.1`
* **CI/CD:** Automated APK builds and GitHub Releases via GitHub Actions (`.github/workflows/build-apk.yml`)