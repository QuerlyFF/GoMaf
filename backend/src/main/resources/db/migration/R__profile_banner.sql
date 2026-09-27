-- Repeatable, rollback-compatible profile banner storage. The latest versioned
-- migration remains V10 so the existing v10 rollback contract stays valid.
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_banner_data BYTEA;
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_banner_content_type VARCHAR(32);
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_banner_safety_version INTEGER;
