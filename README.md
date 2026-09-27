# BTHOME2_decoder

Android app that scans for Bluetooth LE advertisements in the [BTHome v2](https://bthome.io/format/) format,
lists devices with live RSSI, and decodes both raw and (optionally) AES-CCM encrypted BTHome sensor packets.

## Features

- Scans for BLE service data under UUID `0xFCD2` (BTHome).
- Live device list showing MAC address, advertised name, and RSSI.
- Filter the list by MAC address or device name substring.
- Tap a device to see:
  - Raw service-data bytes (hex).
  - The decoded payload (after decryption, if applicable).
  - Every decoded BTHome v2 measurement/binary-sensor/event field, by name, value, and object id.
- Per-device 128-bit AES key storage (persisted with Jetpack DataStore) for decrypting encrypted BTHome
  advertisements (AES-CCM, verified against the [official worked example](https://bthome.io/encryption/)).

## Requirements

- Android 8.0 (API 26) or newer.
- Bluetooth LE hardware.
- Location permission is required by Android on API < 31 for BLE scanning; on API 31+, `BLUETOOTH_SCAN` /
  `BLUETOOTH_CONNECT` runtime permissions are requested instead.

## Building

This repo builds automatically via GitHub Actions on every push to `main` (see
`.github/workflows/build.yml`). After a run completes, download `app-debug-apk` from the workflow run's
**Artifacts** section, unzip it, and sideload `app-debug.apk` to your device (enable "Install unknown apps"
for your browser or file manager first).

To build locally instead, open the project in Android Studio (Iguana or newer) or run:

```
gradle assembleDebug
```

The debug APK is unsigned/debug-signed and intended for sideloading on your own device, not for distribution.

## Notes on the BTHome v2 decoder

- Implements the full BTHome v2 object-id table (sensors, binary sensors, events, device info) per
  <https://bthome.io/format/>.
- Encryption uses AES-CCM with a 13-byte nonce (MAC + service UUID + info byte + 4-byte counter) and a
  4-byte MIC, implemented directly from `AES/ECB/NoPadding` per NIST SP 800-38C (no external crypto
  dependency). The implementation was validated against BTHome's published encryption example before being
  ported to Kotlin.
- Unrecognized object ids stop further parsing of that packet (per spec, since the length of an unknown
  object cannot be determined).
