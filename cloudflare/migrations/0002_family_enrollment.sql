ALTER TABLE devices ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS enrollment_invites (
  invite_id TEXT PRIMARY KEY,
  token_sha256 TEXT NOT NULL UNIQUE,
  created_by_device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
  expires_at_ms INTEGER NOT NULL,
  used INTEGER NOT NULL DEFAULT 0,
  used_by_device_id TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  used_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_invites_expiry ON enrollment_invites(expires_at_ms);
CREATE INDEX IF NOT EXISTS idx_devices_admin ON devices(is_admin, enabled);
