# CasaTrack prototype

CasaTrack is a low-power family location tracker designed for Android + Cloudflare Workers/D1 + Home Assistant.

## Architecture

```text
Android phones
  | HTTPS (per-device bearer token)
  v
Cloudflare Worker ---- D1 (current state + deduplicated history)
  ^
  | outbound HTTPS polling
Home Assistant custom integration (modern TrackerEntity)
```

No inbound connection to Home Assistant is required. No Tailscale, VPN, public HA port, or Neon database is required.

## What the Android prototype does

- Android 6+ (`minSdk 23`).
- Runs a foreground **tracking coordinator**, but does **not** keep GPS active all the time.
- Uses Google Activity Recognition Transition API for `still`, `walking`, `running`, `cycling`, and `driving`.
- Uses speed as a sanity check / activity fusion signal.
- Trusted Wi-Fi acts as an anchor:
  - on connection, request one fresh final fix;
  - then stop continuous location updates;
  - report the configured anchor coordinates as an inferred location;
  - on disconnect, immediately request a new fix and resume adaptive tracking.
- Optional BSSID lock (leave blank to trust SSID only; comma-separated BSSIDs supported).
- Home geofence around the Wi-Fi anchor as a redundant low-power signal.
- Adaptive location profiles:
  - trusted Wi-Fi: GPS updates stopped;
  - still/unknown: low-power sparse updates;
  - walking/running: moderate updates;
  - bicycle: frequent updates;
  - vehicle: more frequent updates with distance threshold.
- Sends battery %, charging state, speed, accuracy, activity, source, and timestamp.
- Offline/retry upload with WorkManager.
- 15-minute heartbeat even while GPS is sleeping.
- Device token is kept locally and never committed to the repository.

## Backend

Cloudflare Worker + D1:

- `POST /v1/update` — device upload, authenticated per device.
- `GET /v1/states` — all current states, authenticated with HA read token.
- `GET /v1/state/:device_id` — one current state.
- `GET /v1/history/:device_id?hours=24&limit=500` — recent history.
- `POST /v1/admin/devices` — provision a device and return its token once.
- `GET /health` — health check.
- Tokens are stored in D1 only as SHA-256 hashes.
- Current state is always upserted; history is deduplicated to avoid needless D1 writes.
- Daily scheduled cleanup; default history retention is 30 days.

## Home Assistant

The included `custom_components/casatrack` is a config-entry integration using the modern Home Assistant device tracker entity model. It creates:

- `device_tracker.<person>` from coordinates and accuracy.
- battery sensor (separate because battery on TrackerEntity is deprecated upstream).
- activity sensor.
- speed sensor.
- last-seen sensor.
- location-source diagnostic sensor.

HA only makes outbound HTTPS requests to Cloudflare.

### Install with HACS

1. In HACS, open **Custom repositories**.
2. Add `https://github.com/diegocesaretti/CasaTrack` with category **Integration**.
3. Install **CasaTrack**, restart Home Assistant, then add it from **Settings → Devices & services**.

## Quick start

1. Deploy the Cloudflare folder and create its D1 database (see `cloudflare/README.md`).
2. Provision a phone using `/v1/admin/devices`; copy the one-time `device_token`.
3. Build/install the Android app, enter Worker URL + device ID + person name + token.
4. Grant location, background location, activity recognition, and notification permissions.
5. While at home, tap **Capture current Wi-Fi + anchor location**.
6. Start tracking.
7. Install CasaTrack through HACS, restart Home Assistant, and add **CasaTrack** from Integrations.

## Security notes

- Never expose D1 credentials to Android or Home Assistant.
- Keep `ADMIN_TOKEN`, `HA_READ_TOKEN`, and device tokens out of Git.
- The Worker validates device ID/token pairs, coordinate ranges, payload size, and update frequency.
- The HA token is read-only; it cannot submit device locations or provision devices.

## Prototype caveats

Android background execution policies vary by OEM. For reliability, the prototype uses a visible foreground-service notification while tracking is enabled. GPS itself is still shut down when a trusted Wi-Fi anchor is active. On heavily customized Android builds, manually excluding CasaTrack from vendor battery optimization may improve reliability.
