# Cloudflare Worker + D1

## 1. Install

```bash
npm install
npx wrangler login
```

## 2. Create D1

```bash
npx wrangler d1 create casatrack
```

Copy the returned `database_id` into `wrangler.toml`.

## 3. Secrets

Generate two long random tokens locally and store them as Worker secrets:

```bash
npx wrangler secret put ADMIN_TOKEN
npx wrangler secret put HA_READ_TOKEN
```

- `ADMIN_TOKEN`: only for provisioning/managing devices.
- `HA_READ_TOKEN`: read-only token used by Home Assistant.

## 4. Migrate and deploy

```bash
npm run migrate:remote
npm run deploy
```

## 5. Provision a phone

```bash
curl -X POST "https://YOUR-WORKER.workers.dev/v1/admin/devices" \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"device_id":"diego-s23","person_name":"Diego","label":"Galaxy S23"}'
```

The response contains `device_token`. It is shown once; paste it into that phone's CasaTrack settings.

## 6. Test HA read endpoint

```bash
curl "https://YOUR-WORKER.workers.dev/v1/states" \
  -H "Authorization: Bearer YOUR_HA_READ_TOKEN"
```

## Retention and D1 usage

The default scheduled job deletes history older than 30 days. Current state remains. Android also reduces history writes by sending meaningful changes and the Worker deduplicates again server-side.
