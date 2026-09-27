-- MFA runtime removed. V5-V8 remain immutable.
-- Keep the old tables temporarily so the currently deployed V8 application remains a viable rollback target.
DELETE FROM admin_mfa_recovery_codes;
DELETE FROM admin_mfa_credentials;
DELETE FROM security_rate_limit_buckets WHERE bucket_key LIKE 'mfa-ip:%' OR bucket_key LIKE 'mfa-pair:%';
DELETE FROM security_sessions WHERE role_name = 'ADMIN';
