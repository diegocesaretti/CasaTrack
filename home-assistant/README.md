# Home Assistant custom integration

Recommended installation: add `https://github.com/diegocesaretti/CasaTrack` to HACS as a custom **Integration** repository and install CasaTrack from there.

For a manual installation, copy:

```text
custom_components/casatrack/
```

to:

```text
/config/custom_components/casatrack/
```

Restart Home Assistant, then go to **Settings → Devices & services → Add integration → CasaTrack**.

Enter:

- Worker URL: `https://YOUR-WORKER.workers.dev`
- HA read token: the value stored as Worker secret `HA_READ_TOKEN`
- Poll interval: 30 seconds is a good default.

Each provisioned phone becomes a modern GPS `device_tracker` plus separate sensors for activity, battery, speed, last seen, and location source. Assign the tracker to the appropriate Home Assistant Person entity.

No inbound route, port forwarding, webhook, Tailscale, or public Home Assistant URL is required.
