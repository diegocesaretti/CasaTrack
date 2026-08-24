# Android client

## Requirements

- Android Studio with SDK 35.
- Android 6.0+ device with Google Play services.

## Build

Open `android/` in Android Studio and build/install the `app` module.

The app deliberately uses a foreground tracking coordinator for reliability. This does **not** mean GPS is permanently on: when a trusted Wi-Fi anchor is active, continuous fused-location requests are removed.

### Permissions

For best behavior on Android 10+ grant:

- precise location;
- location **all the time**;
- physical activity / activity recognition;
- notifications (Android 13+).

On OEMs with aggressive battery management, manually exempt CasaTrack from battery optimization if tracking stops unexpectedly.

## Wi-Fi anchor

At home:

1. Open CasaTrack.
2. Tap **Capture current Wi-Fi + anchor location**.
3. If you have mesh/repeaters and BSSID changes, clear the BSSID field to trust the SSID only, or enter multiple BSSIDs separated by commas.

When the trusted Wi-Fi is detected the client takes a final fresh GPS fix, publishes it, then stops continuous GPS updates and publishes the fixed anchor coordinates as `wifi_anchor`. On disconnect it wakes location immediately.
