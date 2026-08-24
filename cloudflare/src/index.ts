export interface Env {
  DB: D1Database;
  ADMIN_TOKEN: string;
  HA_READ_TOKEN: string;
  HISTORY_RETENTION_DAYS: string;
}

type UpdatePayload = {
  event_id: string;
  device_id: string;
  person_name?: string;
  latitude: number;
  longitude: number;
  accuracy_m: number;
  speed_mps?: number | null;
  activity: string;
  activity_confidence: number;
  location_source: string;
  wifi_ssid?: string | null;
  wifi_bssid?: string | null;
  battery_pct?: number | null;
  charging?: boolean;
  client_time_ms?: number | null;
};

const ACTIVITIES = new Set(["still", "walking", "running", "cycling", "driving", "unknown"]);
const jsonHeaders = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: jsonHeaders });
}
function bearer(req: Request): string | null {
  const h = req.headers.get("authorization") || "";
  return h.startsWith("Bearer ") ? h.slice(7).trim() : null;
}
function base64url(bytes: Uint8Array): string {
  let bin = "";
  bytes.forEach((b) => (bin += String.fromCharCode(b)));
  return btoa(bin).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}
async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function constantTimeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
function validId(v: unknown): v is string { return typeof v === "string" && /^[A-Za-z0-9._-]{2,80}$/.test(v); }
function validPayload(p: any): p is UpdatePayload {
  return p && validId(p.device_id) && typeof p.event_id === "string" && p.event_id.length >= 8 && p.event_id.length <= 100 &&
    Number.isFinite(p.latitude) && p.latitude >= -90 && p.latitude <= 90 &&
    Number.isFinite(p.longitude) && p.longitude >= -180 && p.longitude <= 180 &&
    Number.isFinite(p.accuracy_m) && p.accuracy_m > 0 && p.accuracy_m <= 10000 &&
    ACTIVITIES.has(p.activity) && Number.isInteger(p.activity_confidence) && p.activity_confidence >= 0 && p.activity_confidence <= 100 &&
    typeof p.location_source === "string" && p.location_source.length <= 64 &&
    (p.speed_mps == null || (Number.isFinite(p.speed_mps) && p.speed_mps >= 0 && p.speed_mps < 150)) &&
    (p.battery_pct == null || (Number.isInteger(p.battery_pct) && p.battery_pct >= -1 && p.battery_pct <= 100));
}

function haversineM(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const r = 6371000;
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) * Math.sin(dLon / 2) ** 2;
  return 2 * r * Math.asin(Math.sqrt(a));
}

async function verifyDevice(env: Env, deviceId: string, token: string | null) {
  if (!token) return null;
  const hash = await sha256Hex(token);
  const row = await env.DB.prepare("SELECT device_id, person_name, label, token_sha256 FROM devices WHERE device_id=? AND enabled=1")
    .bind(deviceId).first<any>();
  if (!row || !constantTimeEqual(row.token_sha256, hash)) return null;
  return row;
}
function verifyReadToken(env: Env, req: Request): boolean {
  const t = bearer(req);
  return !!t && !!env.HA_READ_TOKEN && constantTimeEqual(t, env.HA_READ_TOKEN);
}
function verifyAdmin(env: Env, req: Request): boolean {
  const t = bearer(req);
  return !!t && !!env.ADMIN_TOKEN && constantTimeEqual(t, env.ADMIN_TOKEN);
}

async function parseSmallJson(req: Request): Promise<any> {
  const len = Number(req.headers.get("content-length") || "0");
  if (len > 16_384) throw new Error("payload_too_large");
  return req.json();
}

async function handleUpdate(req: Request, env: Env): Promise<Response> {
  let p: any;
  try { p = await parseSmallJson(req); } catch { return json({ error: "invalid_json" }, 400); }
  if (!validPayload(p)) return json({ error: "invalid_payload" }, 400);
  const dev = await verifyDevice(env, p.device_id, bearer(req));
  if (!dev) return json({ error: "unauthorized" }, 401);

  const now = Date.now();
  const current = await env.DB.prepare("SELECT * FROM device_state WHERE device_id=?").bind(p.device_id).first<any>();
  if (current && now - current.server_time_ms < 2500) return json({ ok: true, ignored: "rate_limited" }, 202);

  const personName = dev.person_name || p.person_name || p.device_id;
  const charging = p.charging ? 1 : 0;
  const meaningful = !current ||
    current.activity !== p.activity || current.location_source !== p.location_source || current.wifi_ssid !== (p.wifi_ssid ?? null) ||
    haversineM(current.latitude, current.longitude, p.latitude, p.longitude) >= Math.max(30, Math.min(150, p.accuracy_m)) ||
    now - current.server_time_ms >= 15 * 60_000;

  const upsert = env.DB.prepare(`
    INSERT INTO device_state(device_id,person_name,latitude,longitude,accuracy_m,speed_mps,activity,activity_confidence,location_source,wifi_ssid,wifi_bssid,battery_pct,charging,client_time_ms,server_time_ms,event_id,updated_at)
    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
    ON CONFLICT(device_id) DO UPDATE SET
      person_name=excluded.person_name, latitude=excluded.latitude, longitude=excluded.longitude, accuracy_m=excluded.accuracy_m,
      speed_mps=excluded.speed_mps, activity=excluded.activity, activity_confidence=excluded.activity_confidence,
      location_source=excluded.location_source, wifi_ssid=excluded.wifi_ssid, wifi_bssid=excluded.wifi_bssid,
      battery_pct=excluded.battery_pct, charging=excluded.charging, client_time_ms=excluded.client_time_ms,
      server_time_ms=excluded.server_time_ms, event_id=excluded.event_id, updated_at=CURRENT_TIMESTAMP
  `).bind(p.device_id, personName, p.latitude, p.longitude, p.accuracy_m, p.speed_mps ?? null, p.activity,
    p.activity_confidence, p.location_source, p.wifi_ssid ?? null, p.wifi_bssid ?? null, p.battery_pct ?? null,
    charging, p.client_time_ms ?? null, now, p.event_id);

  const statements: D1PreparedStatement[] = [upsert];
  if (meaningful) {
    statements.push(env.DB.prepare(`
      INSERT OR IGNORE INTO location_history(event_id,device_id,latitude,longitude,accuracy_m,speed_mps,activity,activity_confidence,location_source,wifi_ssid,wifi_bssid,battery_pct,charging,client_time_ms,server_time_ms)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
    `).bind(p.event_id, p.device_id, p.latitude, p.longitude, p.accuracy_m, p.speed_mps ?? null, p.activity,
      p.activity_confidence, p.location_source, p.wifi_ssid ?? null, p.wifi_bssid ?? null, p.battery_pct ?? null,
      charging, p.client_time_ms ?? null, now));
  }
  await env.DB.batch(statements);
  return json({ ok: true, stored_history: meaningful, server_time_ms: now });
}

async function provisionDevice(req: Request, env: Env): Promise<Response> {
  if (!verifyAdmin(env, req)) return json({ error: "unauthorized" }, 401);
  let body: any;
  try { body = await parseSmallJson(req); } catch { return json({ error: "invalid_json" }, 400); }
  if (!validId(body?.device_id) || typeof body?.person_name !== "string" || body.person_name.trim().length < 1 || body.person_name.length > 100) {
    return json({ error: "invalid_payload" }, 400);
  }
  const raw = new Uint8Array(32); crypto.getRandomValues(raw);
  const token = `ct_${base64url(raw)}`;
  const hash = await sha256Hex(token);
  try {
    await env.DB.prepare(`INSERT INTO devices(device_id,person_name,label,token_sha256,enabled,updated_at) VALUES(?,?,?,?,1,CURRENT_TIMESTAMP)`)
      .bind(body.device_id, body.person_name.trim(), typeof body.label === "string" ? body.label.slice(0, 120) : null, hash).run();
  } catch {
    return json({ error: "device_exists" }, 409);
  }
  return json({ device_id: body.device_id, person_name: body.person_name.trim(), device_token: token, note: "This token is returned only now. Store it in the phone." }, 201);
}

async function states(env: Env): Promise<Response> {
  const res = await env.DB.prepare(`
    SELECT s.*, d.label FROM device_state s JOIN devices d ON d.device_id=s.device_id WHERE d.enabled=1 ORDER BY s.person_name
  `).all<any>();
  return json({ server_time_ms: Date.now(), devices: res.results ?? [] });
}

async function oneState(env: Env, id: string): Promise<Response> {
  const row = await env.DB.prepare(`SELECT s.*, d.label FROM device_state s JOIN devices d ON d.device_id=s.device_id WHERE s.device_id=? AND d.enabled=1`).bind(id).first<any>();
  return row ? json(row) : json({ error: "not_found" }, 404);
}

async function history(req: Request, env: Env, id: string): Promise<Response> {
  const u = new URL(req.url);
  const hours = Math.min(168, Math.max(1, Number(u.searchParams.get("hours") || 24)));
  const limit = Math.min(2000, Math.max(1, Number(u.searchParams.get("limit") || 500)));
  const since = Date.now() - hours * 3600_000;
  const res = await env.DB.prepare(`SELECT * FROM location_history WHERE device_id=? AND server_time_ms>=? ORDER BY server_time_ms DESC LIMIT ?`)
    .bind(id, since, limit).all<any>();
  return json({ device_id: id, hours, points: res.results ?? [] });
}

async function router(req: Request, env: Env): Promise<Response> {
  const u = new URL(req.url);
  const path = u.pathname;
  if (req.method === "GET" && path === "/health") return json({ ok: true, service: "casatrack", version: "0.1.0" });
  if (req.method === "POST" && path === "/v1/update") return handleUpdate(req, env);
  if (req.method === "POST" && path === "/v1/admin/devices") return provisionDevice(req, env);
  if (req.method === "GET" && path === "/v1/states") {
    if (!verifyReadToken(env, req)) return json({ error: "unauthorized" }, 401);
    return states(env);
  }
  const stateMatch = path.match(/^\/v1\/state\/([A-Za-z0-9._-]{2,80})$/);
  if (req.method === "GET" && stateMatch) {
    if (!verifyReadToken(env, req)) return json({ error: "unauthorized" }, 401);
    return oneState(env, stateMatch[1]);
  }
  const histMatch = path.match(/^\/v1\/history\/([A-Za-z0-9._-]{2,80})$/);
  if (req.method === "GET" && histMatch) {
    if (!verifyReadToken(env, req)) return json({ error: "unauthorized" }, 401);
    return history(req, env, histMatch[1]);
  }
  return json({ error: "not_found" }, 404);
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    try { return await router(req, env); }
    catch (err) { console.error(err); return json({ error: "internal_error" }, 500); }
  },
  async scheduled(_event: ScheduledEvent, env: Env): Promise<void> {
    const days = Math.min(365, Math.max(1, Number(env.HISTORY_RETENTION_DAYS || 30)));
    const cutoff = Date.now() - days * 86400_000;
    await env.DB.prepare("DELETE FROM location_history WHERE server_time_ms < ?").bind(cutoff).run();
  }
};
