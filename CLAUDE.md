# pulse-check

Android OBD-II gauge app (Kotlin, framework-only, no library dependencies).
Built by GitHub Actions on every push; the APK is published to the `latest` release.
The owner works from an iPhone and has no local Android tooling. Default branch is `master`.

## Target head unit

The app runs on an aftermarket head unit in a 2015 Toyota 4Runner.

| Field | Value |
| --- | --- |
| Brand | Dasaita (4Runner-specific, 10.2" landscape) |
| Platform | HCT **MTCH** family (MCU prefix `MTCHS`) |
| SoC | Qualcomm QCM6125 (Snapdragon 665 class): 4x Cortex-A73 @ 2.0 GHz + 4x Cortex-A53 @ 1.8 GHz |
| RAM | 8 GB (7822 MB reported) |
| Android | 13 |
| Kernel | 4.14.190 #305, built Oct 31 2025 |
| Build number | `QCM6125 13 S10A_123 eng.hct.20251031.121031 NA-V2.1` |
| MCU version | `MTCHS_HA4Z_V4.16f_1`, Jul 30 2025 |
| SIM | none |

MCU firmware is manufacturer-specific: never suggest flashing an MCU image from another brand.

## Connectivity notes

- The owner's iPhone uses **wireless CarPlay** (ZLink), which holds a Bluetooth link to the phone plus a Wi-Fi link.
- Reports on MTCH/Dasaita units are mixed about pairing non-phone Bluetooth devices (OBD adapters):
  - Some Dasaita models (e.g. G13) are documented as phone-only Bluetooth; Dasaita said the G13 doesn't support the OBDLink MX+.
  - Common fixes that work on some units: set the head unit Bluetooth PIN to 1234, pair via the head unit's own BT app rather than Settings, and disable CarPlay/Android Auto in factory settings.
  - USB ELM327 adapters with an FTDI FT232 chip (drivers built in), or the OBDLink SX, reportedly work on QCM6125 Dasaita units. The app does not support USB yet.
- Avoid Wi-Fi OBD adapters, since they'd compete with wireless CarPlay.
- Quick check on the unit: tap **Connect** in the app; if the paired iPhone is listed, the app can see the Bluetooth stack.
