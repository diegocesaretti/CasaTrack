CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  person_name TEXT NOT NULL,
  label TEXT,
  token_sha256 TEXT NOT NULL UNIQUE,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS device_state (
  device_id TEXT PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
  person_name TEXT NOT NULL,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  accuracy_m REAL NOT NULL,
  speed_mps REAL,
  activity TEXT NOT NULL,
  activity_confidence INTEGER NOT NULL,
  location_source TEXT NOT NULL,
  wifi_ssid TEXT,
  wifi_bssid TEXT,
  battery_pct INTEGER,
  charging INTEGER NOT NULL DEFAULT 0,
  client_time_ms INTEGER,
  server_time_ms INTEGER NOT NULL,
  event_id TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS location_history (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL UNIQUE,
  device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  accuracy_m REAL NOT NULL,
  speed_mps REAL,
  activity TEXT NOT NULL,
  activity_confidence INTEGER NOT NULL,
  location_source TEXT NOT NULL,
  wifi_ssid TEXT,
  wifi_bssid TEXT,
  battery_pct INTEGER,
  charging INTEGER NOT NULL DEFAULT 0,
  client_time_ms INTEGER,
  server_time_ms INTEGER NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_history_device_time ON location_history(device_id, server_time_ms DESC);
CREATE INDEX IF NOT EXISTS idx_history_time ON location_history(server_time_ms);
