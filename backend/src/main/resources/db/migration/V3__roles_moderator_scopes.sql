-- v10 authorization model. The legacy users.role column remains intentionally:
-- ADMIN stays ADMIN; PLAYER and MODERATOR are represented as PLAYER there so a
-- code rollback to v9 can still read every account.

ALTER TABLE users ADD COLUMN IF NOT EXISTS account_role VARCHAR(16);
ALTER TABLE users ADD COLUMN IF NOT EXISTS authorization_version INTEGER;

UPDATE users
SET username_key = translate(btrim(username), 'ABCDEFGHIJKLMNOPQRSTUVWXYZАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ', 'abcdefghijklmnopqrstuvwxyzабвгдеёжзийклмнопрстуфхцчшщъыьэюя')
WHERE username_key IS NULL OR btrim(username_key) = '';

UPDATE users
SET account_role = role
WHERE account_role IS NULL;

UPDATE users
SET credential_version = 0
WHERE credential_version IS NULL;

UPDATE users
SET authorization_version = 0
WHERE authorization_version IS NULL;

DO $$
DECLARE
    admin_count INTEGER;
    admin_id BIGINT;
    blocking_id BIGINT;
    admin_needs_rename BOOLEAN;
BEGIN
    SELECT count(*) INTO admin_count
    FROM users
    WHERE account_role = 'ADMIN';

    IF admin_count > 1 THEN
        RAISE EXCEPTION 'v10 migration refused: more than one ADMIN exists';
    END IF;

    IF admin_count = 1 THEN
        SELECT id, (username <> 'admin' OR username_key <> 'admin')
        INTO admin_id, admin_needs_rename
        FROM users
        WHERE account_role = 'ADMIN';

        SELECT id INTO blocking_id
        FROM users
        WHERE username_key = 'admin' AND id <> admin_id
        LIMIT 1;

        IF blocking_id IS NOT NULL THEN
            RAISE EXCEPTION 'v10 migration refused: username admin is owned by non-ADMIN user id %', blocking_id;
        END IF;

        UPDATE users
        SET username = 'admin',
            username_key = 'admin',
            credential_version = COALESCE(credential_version, 0) + CASE WHEN admin_needs_rename THEN 1 ELSE 0 END,
            authorization_version = COALESCE(authorization_version, 0) + CASE WHEN admin_needs_rename THEN 1 ELSE 0 END
        WHERE id = admin_id;
    ELSIF EXISTS (SELECT 1 FROM users WHERE username_key = 'admin') THEN
        RAISE EXCEPTION 'v10 migration refused: reserved username admin belongs to a non-ADMIN account';
    END IF;
END $$;

ALTER TABLE users ALTER COLUMN username_key SET NOT NULL;
ALTER TABLE users ALTER COLUMN account_role SET DEFAULT 'PLAYER';
ALTER TABLE users ALTER COLUMN account_role SET NOT NULL;
ALTER TABLE users ALTER COLUMN credential_version SET DEFAULT 0;
ALTER TABLE users ALTER COLUMN credential_version SET NOT NULL;
ALTER TABLE users ALTER COLUMN authorization_version SET DEFAULT 0;
ALTER TABLE users ALTER COLUMN authorization_version SET NOT NULL;

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_username_key_canonical;
ALTER TABLE users ADD CONSTRAINT chk_users_username_key_canonical
    CHECK (username_key = translate(btrim(username), 'ABCDEFGHIJKLMNOPQRSTUVWXYZАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ', 'abcdefghijklmnopqrstuvwxyzабвгдеёжзийклмнопрстуфхцчшщъыьэюя'));

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_security_versions_nonnegative;
ALTER TABLE users ADD CONSTRAINT chk_users_security_versions_nonnegative
    CHECK (credential_version >= 0 AND authorization_version >= 0);

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_account_role;
ALTER TABLE users ADD CONSTRAINT chk_users_account_role
    CHECK (account_role IN ('PLAYER', 'MODERATOR', 'ADMIN'));

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_legacy_role;
ALTER TABLE users ADD CONSTRAINT chk_users_legacy_role
    CHECK (role IN ('PLAYER', 'ADMIN'));

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_role_rollback_compatibility;
ALTER TABLE users ADD CONSTRAINT chk_users_role_rollback_compatibility CHECK (
    (account_role = 'ADMIN' AND role = 'ADMIN') OR
    (account_role IN ('PLAYER', 'MODERATOR') AND role = 'PLAYER')
);

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_reserved_admin_identity;
ALTER TABLE users ADD CONSTRAINT chk_users_reserved_admin_identity CHECK (
    (account_role = 'ADMIN' AND username = 'admin' AND username_key = 'admin') OR
    (account_role <> 'ADMIN' AND translate(btrim(username), 'ABCDEFGHIJKLMNOPQRSTUVWXYZАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ', 'abcdefghijklmnopqrstuvwxyzабвгдеёжзийклмнопрстуфхцчшщъыьэюя') <> 'admin' AND username_key <> 'admin')
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_users_single_admin
    ON users(account_role)
    WHERE account_role = 'ADMIN';

CREATE TABLE IF NOT EXISTS moderator_scope_defaults (
    scope VARCHAR(64) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_moderator_scope_defaults_scope CHECK (scope IN (
        'NEWS_MANAGE',
        'RULES_MANAGE',
        'GAMES_MANAGE',
        'SCHEDULE_MANAGE',
        'PLAYER_RATING_MANAGE',
        'PLAYER_USERNAME_MANAGE',
        'PLAYER_AVATAR_RESET',
        'PLAYER_PASSWORD_RESET',
        'PLAYER_DELETE'
    ))
);

INSERT INTO moderator_scope_defaults(scope, enabled) VALUES
    ('NEWS_MANAGE', FALSE),
    ('RULES_MANAGE', FALSE),
    ('GAMES_MANAGE', FALSE),
    ('SCHEDULE_MANAGE', FALSE),
    ('PLAYER_RATING_MANAGE', FALSE),
    ('PLAYER_USERNAME_MANAGE', FALSE),
    ('PLAYER_AVATAR_RESET', FALSE),
    ('PLAYER_PASSWORD_RESET', FALSE),
    ('PLAYER_DELETE', FALSE)
ON CONFLICT (scope) DO NOTHING;

CREATE TABLE IF NOT EXISTS moderator_scope_overrides (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    scope VARCHAR(64) NOT NULL,
    decision VARCHAR(16) NOT NULL DEFAULT 'INHERIT',
    CONSTRAINT fk_moderator_scope_override_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_moderator_scope_override_user_scope UNIQUE (user_id, scope),
    CONSTRAINT chk_moderator_scope_override_scope CHECK (scope IN (
        'NEWS_MANAGE',
        'RULES_MANAGE',
        'GAMES_MANAGE',
        'SCHEDULE_MANAGE',
        'PLAYER_RATING_MANAGE',
        'PLAYER_USERNAME_MANAGE',
        'PLAYER_AVATAR_RESET',
        'PLAYER_PASSWORD_RESET',
        'PLAYER_DELETE'
    )),
    CONSTRAINT chk_moderator_scope_override_decision CHECK (decision IN ('INHERIT', 'ALLOW', 'DENY'))
);

CREATE INDEX IF NOT EXISTS idx_moderator_scope_overrides_user_id
    ON moderator_scope_overrides(user_id);
