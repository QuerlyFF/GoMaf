-- Reject reuse of an already accepted TOTP counter. V1-V5 remain immutable.
ALTER TABLE admin_mfa_credentials
    ADD COLUMN last_totp_counter BIGINT;

ALTER TABLE admin_mfa_credentials
    ADD CONSTRAINT chk_admin_mfa_last_totp_counter
    CHECK (last_totp_counter IS NULL OR last_totp_counter >= 0);
