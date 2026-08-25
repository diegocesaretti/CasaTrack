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

function randomToken(prefix: string, bytes = 32): string {
  const raw = new Uint8Array(bytes);
  crypto.getRandomValues(raw);
  return `${prefix}${base64url(raw)}`;
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

function validId(v: unknown): v is string {
  return typeof v === "string" && /^[A-Za-z0-9._-]{2,80}$/.test(v);
}

function validHumanName(v: unknown): v is string {
  return typeof v === "string" && v.trim().length >= 1 && v.trim().length <= 100;
}

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
  const row = await env.DB.prepare(
    "SELECT device_id, person_name, label, token_sha256, enabled, is_admin FROM devices WHERE device_id=? AND enabled=1"
  ).bind(deviceId).first<any>();
  if (!row || !constantTimeEqual(row.token_sha256, hash)) return null;
  return row;
}

async function authenticatedDevice(req: Request, env: Env) {
  const deviceId = req.headers.get("x-casatrack-device-id")?.trim() || "";
  if (!validId(deviceId)) return null;
  return verifyDevice(env, deviceId, bearer(req));
}

async function authenticatedAdminDevice(req: Request, env: Env) {
  const dev = await authenticatedDevice(req, env);
  return dev?.is_admin === 1 ? dev : null;
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

async function me(req: Request, env: Env): Promise<Response> {
  const dev = await authenticatedDevice(req, env);
  if (!dev) return json({ error: "unauthorized" }, 401);
  const admins = await env.DB.prepare("SELECT COUNT(*) AS n FROM devices WHERE enabled=1 AND is_admin=1").first<any>();
  const adminCount = Number(admins?.n || 0);
  return json({
    device_id: dev.device_id,
    person_name: dev.person_name,
    label: dev.label,
    is_admin: dev.is_admin === 1,
    can_claim_admin: adminCount === 0,
  });
}

async function claimAdmin(req: Request, env: Env): Promise<Response> {
  const dev = await authenticatedDevice(req, env);
  if (!dev) return json({ error: "unauthorized" }, 401);
  if (dev.is_admin === 1) return json({ ok: true, is_admin: true });

  await env.DB.prepare(`
    UPDATE devices SET is_admin=1, updated_at=CURRENT_TIMESTAMP
    WHERE device_id=? AND enabled=1
      AND NOT EXISTS (SELECT 1 FROM devices WHERE enabled=1 AND is_admin=1)
  `).bind(dev.device_id).run();

  const row = await env.DB.prepare("SELECT is_admin FROM devices WHERE device_id=?").bind(dev.device_id).first<any>();
  if (row?.is_admin === 1) return json({ ok: true, is_admin: true });
  return json({ error: "admin_already_exists" }, 409);
}

async function createInvite(req: Request, env: Env): Promise<Response> {
  const admin = await authenticatedAdminDevice(req, env);
  if (!admin) return json({ error: "admin_required" }, 403);

  const inviteToken = randomToken("cti_", 24);
  const inviteId = randomToken("inv_", 12);
  const hash = await sha256Hex(inviteToken);
  const expiresAtMs = Date.now() + 10 * 60_000;

  await env.DB.prepare(`
    INSERT INTO enrollment_invites(invite_id, token_sha256, created_by_device_id, expires_at_ms, used)
    VALUES(?,?,?,?,0)
  `).bind(inviteId, hash, admin.device_id, expiresAtMs).run();

  return json({ invite_token: inviteToken, expires_at_ms: expiresAtMs, expires_in_seconds: 600 }, 201);
}

async function loadInvite(env: Env, inviteToken: string) {
  const hash = await sha256Hex(inviteToken.trim());
  return env.DB.prepare(`
    SELECT invite_id, created_by_device_id, expires_at_ms
    FROM enrollment_invites
    WHERE token_sha256=? AND used=0 AND expires_at_ms>?
  `).bind(hash, Date.now()).first<any>();
}

async function inviteChoices(env: Env, inviteToken: string): Promise<Response> {
  const invite = await loadInvite(env, inviteToken);
  if (!invite) return json({ error: "invite_invalid_or_expired" }, 401);

  const devices = await env.DB.prepare(`
    SELECT d.device_id, d.person_name, d.label, d.is_admin,
           s.server_time_ms, s.battery_pct, s.activity
    FROM devices d
    LEFT JOIN device_state s ON s.device_id=d.device_id
    WHERE d.enabled=1
    ORDER BY d.is_admin DESC, d.person_name, d.label
  `).all<any>();

  return json({
    action: "inspect",
    created_by_device_id: invite.created_by_device_id,
    expires_at_ms: invite.expires_at_ms,
    devices: devices.results ?? [],
  });
}

async function transferExisting(env: Env, inviteToken: string, targetId: string, requestedLabel: unknown): Promise<Response> {
  if (!validId(targetId)) return json({ error: "invalid_target_device" }, 400);
  const invite = await loadInvite(env, inviteToken);
  if (!invite) return json({ error: "invite_invalid_or_expired" }, 401);

  const target = await env.DB.prepare(`
    SELECT device_id, person_name, label, is_admin FROM devices WHERE device_id=? AND enabled=1
  `).bind(targetId).first<any>();
  if (!target) return json({ error: "target_not_found" }, 404);

  const deviceToken = randomToken("ct_", 32);
  const deviceHash = await sha256Hex(deviceToken);
  const nonce = randomToken("use_", 12);
  const newLabel = typeof requestedLabel === "string" && requestedLabel.trim()
    ? requestedLabel.trim().slice(0, 120)
    : null;

  await env.DB.batch([
    env.DB.prepare(`
      UPDATE enrollment_invites
      SET used=1, used_by_device_id=?, used_at=CURRENT_TIMESTAMP, used_nonce=?
      WHERE invite_id=? AND used=0 AND expires_at_ms>?
    `).bind(targetId, nonce, invite.invite_id, Date.now()),
    env.DB.prepare(`
      UPDATE devices
      SET token_sha256=?, label=COALESCE(?, label), updated_at=CURRENT_TIMESTAMP
      WHERE device_id=? AND enabled=1
        AND EXISTS (
          SELECT 1 FROM enrollment_invites
          WHERE invite_id=? AND used_nonce=? AND used=1
        )
    `).bind(deviceHash, newLabel, targetId, invite.invite_id, nonce),
  ]);

  const verified = await env.DB.prepare(`
    SELECT d.device_id, d.person_name, d.label, d.is_admin, d.token_sha256, i.used_nonce
    FROM devices d JOIN enrollment_invites i ON i.invite_id=?
    WHERE d.device_id=?
  `).bind(invite.invite_id, targetId).first<any>();

  if (!verified || verified.used_nonce !== nonce || !constantTimeEqual(verified.token_sha256, deviceHash)) {
    return json({ error: "invite_already_used" }, 409);
  }

  return json({
    action: "transfer",
    device_id: verified.device_id,
    person_name: verified.person_name,
    label: verified.label,
    is_admin: verified.is_admin === 1,
    device_token: deviceToken,
    preserved_identity: true,
  }, 201);
}

async function enrollNew(env: Env, inviteToken: string, personName: string, requestedLabel: unknown): Promise<Response> {
  const invite = await loadInvite(env, inviteToken);
  if (!invite) return json({ error: "invite_invalid_or_expired" }, 401);

  const deviceId = randomToken("ctd_", 12);
  const deviceToken = randomToken("ct_", 32);
  const deviceHash = await sha256Hex(deviceToken);
  const nonce = randomToken("use_", 12);
  const label = typeof requestedLabel === "string" && requestedLabel.trim()
    ? requestedLabel.trim().slice(0, 120)
    : "Android";

  await env.DB.batch([
    env.DB.prepare(`
      UPDATE enrollment_invites
      SET used=1, used_by_device_id=?, used_at=CURRENT_TIMESTAMP, used_nonce=?
      WHERE invite_id=? AND used=0 AND expires_at_ms>?
    `).bind(deviceId, nonce, invite.invite_id, Date.now()),
    env.DB.prepare(`
      INSERT INTO devices(device_id,person_name,label,token_sha256,enabled,is_admin,updated_at)
      SELECT ?,?,?,?,1,0,CURRENT_TIMESTAMP
      WHERE EXISTS (
        SELECT 1 FROM enrollment_invites
        WHERE invite_id=? AND used_nonce=? AND used=1
      )
    `).bind(deviceId, personName.trim(), label, deviceHash, invite.invite_id, nonce),
  ]);

  const verified = await env.DB.prepare(`
    SELECT device_id, person_name, label, is_admin, token_sha256
    FROM devices WHERE device_id=?
  `).bind(deviceId).first<any>();

  if (!verified || !constantTimeEqual(verified.token_sha256, deviceHash)) {
    return json({ error: "invite_already_used" }, 409);
  }

  return json({
    action: "enroll",
    device_id: verified.device_id,
    person_name: verified.person_name,
    label: verified.label,
    is_admin: false,
    device_token: deviceToken,
  }, 201);
}

async function enroll(req: Request, env: Env): Promise<Response> {
  let body: any;
  try { body = await parseSmallJson(req); } catch { return json({ error: "invalid_json" }, 400); }
  if (typeof body?.invite_token !== "string" || body.invite_token.trim().length < 8) {
    return json({ error: "invalid_payload" }, 400);
  }

  const action = typeof body.action === "string" ? body.action : "enroll";
  if (action === "inspect") return inviteChoices(env, body.invite_token);
  if (action === "transfer") return transferExisting(env, body.invite_token, body.target_device_id, body.label);
  if (action !== "enroll") return json({ error: "invalid_action" }, 400);
  if (!validHumanName(body.person_name)) return json({ error: "invalid_payload" }, 400);
  return enrollNew(env, body.invite_token, body.person_name, body.label);
}

async function familyDevices(req: Request, env: Env): Promise<Response> {
  const admin = await authenticatedAdminDevice(req, env);
  if (!admin) return json({ error: "admin_required" }, 403);
  const res = await env.DB.prepare(`
    SELECT d.device_id, d.person_name, d.label, d.is_admin, d.enabled, d.created_at,
           s.server_time_ms, s.battery_pct, s.charging, s.activity, s.wifi_ssid
    FROM devices d
    LEFT JOIN device_state s ON s.device_id=d.device_id
    WHERE d.enabled=1
    ORDER BY d.is_admin DESC, d.person_name, d.label
  `).all<any>();
  return json({ current_device_id: admin.device_id, devices: res.results ?? [] });
}

async function deleteFamilyDevice(req: Request, env: Env, targetId: string): Promise<Response> {
  const admin = await authenticatedAdminDevice(req, env);
  if (!admin) return json({ error: "admin_required" }, 403);
  if (admin.device_id === targetId) return json({ error: "cannot_delete_current_admin" }, 409);

  const target = await env.DB.prepare("SELECT device_id FROM devices WHERE device_id=? AND enabled=1").bind(targetId).first<any>();
  if (!target) return json({ error: "not_found" }, 404);

  await env.DB.batch([
    env.DB.prepare("DELETE FROM location_history WHERE device_id=?").bind(targetId),
    env.DB.prepare("DELETE FROM device_state WHERE device_id=?").bind(targetId),
    env.DB.prepare("DELETE FROM enrollment_invites WHERE created_by_device_id=?").bind(targetId),
    env.DB.prepare("DELETE FROM devices WHERE device_id=?").bind(targetId),
  ]);
  return json({ ok: true, deleted_device_id: targetId });
}

async function provisionDevice(req: Request, env: Env): Promise<Response> {
  if (!verifyAdmin(env, req)) return json({ error: "unauthorized" }, 401);
  let body: any;
  try { body = await parseSmallJson(req); } catch { return json({ error: "invalid_json" }, 400); }
  if (!validId(body?.device_id) || !validHumanName(body?.person_name)) return json({ error: "invalid_payload" }, 400);

  const token = randomToken("ct_", 32);
  const hash = await sha256Hex(token);
  const isAdmin = body?.is_admin === true ? 1 : 0;
  try {
    await env.DB.prepare(`
      INSERT INTO devices(device_id,person_name,label,token_sha256,enabled,is_admin,updated_at)
      VALUES(?,?,?,?,1,?,CURRENT_TIMESTAMP)
    `).bind(body.device_id, body.person_name.trim(), typeof body.label === "string" ? body.label.slice(0, 120) : null, hash, isAdmin).run();
  } catch {
    return json({ error: "device_exists" }, 409);
  }
  return json({ device_id: body.device_id, person_name: body.person_name.trim(), device_token: token, is_admin: isAdmin === 1 }, 201);
}

async function states(env: Env): Promise<Response> {
  const res = await env.DB.prepare(`
    SELECT s.*, d.label FROM device_state s JOIN devices d ON d.device_id=s.device_id WHERE d.enabled=1 ORDER BY s.person_name
  `).all<any>();
  return json({ server_time_ms: Date.now(), devices: res.results ?? [] });
}

async function oneState(env: Env, id: string): Promise<Response> {
  const row = await env.DB.prepare(`
    SELECT s.*, d.label FROM device_state s JOIN devices d ON d.device_id=s.device_id
    WHERE s.device_id=? AND d.enabled=1
  `).bind(id).first<any>();
  return row ? json(row) : json({ error: "not_found" }, 404);
}

async function history(req: Request, env: Env, id: string): Promise<Response> {
  const u = new URL(req.url);
  const hours = Math.min(168, Math.max(1, Number(u.searchParams.get("hours") || 24)));
  const limit = Math.min(2000, Math.max(1, Number(u.searchParams.get("limit") || 500)));
  const since = Date.now() - hours * 3600_000;
  const res = await env.DB.prepare(`
    SELECT * FROM location_history WHERE device_id=? AND server_time_ms>=? ORDER BY server_time_ms DESC LIMIT ?
  `).bind(id, since, limit).all<any>();
  return json({ device_id: id, hours, points: res.results ?? [] });
}

async function router(req: Request, env: Env): Promise<Response> {
  const u = new URL(req.url);
  const path = u.pathname;

  if (req.method === "GET" && path === "/health") return json({ ok: true, service: "casatrack", version: "0.3.0" });
  if (req.method === "POST" && path === "/v1/update") return handleUpdate(req, env);

  if (req.method === "GET" && path === "/v1/me") return me(req, env);
  if (req.method === "POST" && path === "/v1/admin/claim") return claimAdmin(req, env);
  if (req.method === "POST" && path === "/v1/invites") return createInvite(req, env);
  if (req.method === "POST" && path === "/v1/enroll") return enroll(req, env);
  if (req.method === "GET" && path === "/v1/devices") return familyDevices(req, env);

  const deviceDeleteMatch = path.match(/^\/v1\/devices\/([A-Za-z0-9._-]{2,80})$/);
  if (req.method === "DELETE" && deviceDeleteMatch) return deleteFamilyDevice(req, env, deviceDeleteMatch[1]);

  // Legacy/recovery provisioning endpoint. ADMIN_TOKEN remains server-side and is never shipped in the app.
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
    const now = Date.now();
    await env.DB.batch([
      env.DB.prepare("DELETE FROM location_history WHERE server_time_ms < ?").bind(cutoff),
      env.DB.prepare("DELETE FROM enrollment_invites WHERE used=1 OR expires_at_ms < ?").bind(now),
    ]);
  }
};
