-- Incident response: invalidate the previously exposed ADMIN MFA material.
-- Existing ADMIN must enroll a fresh second factor after this migration.
-- Fresh installs with no ADMIN row yet are unaffected.

DELETE FROM admin_mfa_recovery_codes
WHERE user_id IN (SELECT id FROM users WHERE account_role = 'ADMIN');

DELETE FROM admin_mfa_credentials
WHERE user_id IN (SELECT id FROM users WHERE account_role = 'ADMIN');

DELETE FROM security_sessions
WHERE user_id IN (SELECT id FROM users WHERE account_role = 'ADMIN');

UPDATE users
SET credential_version = credential_version + 1,
    authorization_version = authorization_version + 1
WHERE account_role = 'ADMIN';
