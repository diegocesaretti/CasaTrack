ALTER TABLE enrollment_invites ADD COLUMN used_nonce TEXT;
ALTER TABLE enrollment_invites ADD COLUMN used_purpose TEXT;

CREATE INDEX IF NOT EXISTS idx_invites_used_nonce ON enrollment_invites(used_nonce);
CREATE INDEX IF NOT EXISTS idx_invites_used_purpose ON enrollment_invites(used_purpose, expires_at_ms);
