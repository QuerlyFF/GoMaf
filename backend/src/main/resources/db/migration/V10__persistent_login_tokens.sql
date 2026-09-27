-- Persistent-login tokens for long-lived sign-in without exposing bearer JWTs to JavaScript.
-- V1-V9 remain immutable. V10 is additive so a rollback to V9 can ignore this table safely.
CREATE TABLE persistent_login_tokens (
    selector VARCHAR(64) PRIMARY KEY,
    validator_hash VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_name VARCHAR(16) NOT NULL,
    credential_version INTEGER NOT NULL,
    authorization_version INTEGER NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_persistent_login_role CHECK (role_name IN ('PLAYER','MODERATOR','ADMIN')),
    CONSTRAINT chk_persistent_login_credential_version CHECK (credential_version >= 0),
    CONSTRAINT chk_persistent_login_authorization_version CHECK (authorization_version >= 0)
);
CREATE INDEX idx_persistent_login_user_created ON persistent_login_tokens(user_id, created_at);
CREATE INDEX idx_persistent_login_expiry ON persistent_login_tokens(expires_at);
