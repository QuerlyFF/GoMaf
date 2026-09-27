#!/usr/bin/env bash
set -u -o pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

pass=0
fail=0
ok() { printf 'PASS %s\n' "$1"; pass=$((pass+1)); }
bad() { printf 'FAIL %s\n' "$1"; fail=$((fail+1)); }
check() { local label="$1"; shift; if "$@"; then ok "$label"; else bad "$label"; fi; }
has() { grep -Eq -- "$2" "$1"; }
lacks() { ! grep -Eq -- "$2" "$1"; }
all_have() { local f="$1"; shift; local p; for p in "$@"; do grep -Eq -- "$p" "$f" || return 1; done; }

# Immutable accepted Flyway baseline.
declare -A flyway=(
  [V1__initial_schema.sql]='4a19c886e98bb8b4ee3cc28f9d86bf466582d2d7bd35bfc7e5462205e188de16'
  [V2__production_indexes.sql]='b3875b08740a947f19dd70133ff4081ff0d7f4e6ab2058317008be07c75f1d31'
  [V3__roles_moderator_scopes.sql]='b9f2be6f8341d78b915b9bf27ab71a229e810f59ea433936b97fa225b8f09ed8'
  [V4__schedule_tournaments_profiles_video.sql]='7b2e2f192ff843d1b9bde533535b0aa24de5b46d319e1852c8c6c0dce99e071d'
  [V5__security_hardening.sql]='6a18f2c314b7a380a16728a04ecfdf12c35df021979bb957a5e49304aa044319'
  [V6__mfa_replay_protection.sql]='133f46eb4e5b2e19a73a98e4b6faa8759fcfd6219f1f86ff73e274615bb02de6'
  [V7__force_admin_mfa_reenrollment.sql]='78bb3ee898a3d5db8db8175a2e8630b9f009b2c865f35bf6b5d20c5348779fea'
  [V8__query_plan_indexes.sql]='7f537b1f4cd7d11560f125ec20a4ede0a65122bf41f992712b69989e11a41326'
  [V9__retire_mfa_runtime.sql]='31f67230dfc8fa4f8ef6a5ce57639834f92a4c545091705f5c8642842e2f665b'
  [V10__persistent_login_tokens.sql]='db3d6e924b8a733b0b9cc9b99ab7c4743b3a2669bd19a1c08a904e106100ab02'
)
for f in "${!flyway[@]}"; do
  path="backend/src/main/resources/db/migration/$f"
  if [[ -f "$path" && "$(sha256sum "$path" | awk '{print $1}')" == "${flyway[$f]}" ]]; then ok "Flyway immutable $f"; else bad "Flyway immutable $f"; fi
done

V5=backend/src/main/resources/db/migration/V5__security_hardening.sql
check 'V5 security migration exists' test -f "$V5"
check 'V5 persistent limiter/MFA/session tables' all_have "$V5" \
  'CREATE TABLE security_rate_limit_buckets' 'CREATE TABLE admin_mfa_credentials' \
  'CREATE TABLE admin_mfa_recovery_codes' 'CREATE TABLE security_sessions'
check 'V5 HTTPS video backstop is fail-closed' all_have "$V5" \
  "RAISE EXCEPTION 'WP11-023 requires all existing game_videos URLs to use HTTPS'" \
  'ADD CONSTRAINT chk_game_videos_https'

V6=backend/src/main/resources/db/migration/V6__mfa_replay_protection.sql
check 'V6 MFA replay migration exists' test -f "$V6"
check 'V6 persists last accepted TOTP counter' all_have "$V6" \
  'ADD COLUMN last_totp_counter BIGINT' 'last_totp_counter IS NULL OR last_totp_counter >= 0'

V7=backend/src/main/resources/db/migration/V7__force_admin_mfa_reenrollment.sql
check 'V7 ADMIN MFA incident reset migration exists' test -f "$V7"
check 'V7 invalidates ADMIN MFA/recovery/sessions and security versions' all_have "$V7" \
  'DELETE FROM admin_mfa_recovery_codes' 'DELETE FROM admin_mfa_credentials' \
  'DELETE FROM security_sessions' 'credential_version = credential_version \+ 1' \
  'authorization_version = authorization_version \+ 1' "account_role = 'ADMIN'"

SEC=backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java
check 'CSRF uses CookieCsrfTokenRepository' has "$SEC" 'CookieCsrfTokenRepository'
check 'CSRF cookie is HttpOnly and production-Secure configurable' all_have "$SEC" \
  '\.httpOnly\(true\)' '\.secure\(secureCookie\)' 'server\.servlet\.session\.cookie\.secure'
check 'CSRF still enabled' lacks "$SEC" 'csrf\([^\n]*disable|csrf\.disable'
check 'CSRF endpoint materialization preserves XOR masking' all_have "$SEC" \
  'XorCsrfTokenRequestAttributeHandler' 'deferredCsrfToken\.get\(\)' '/api/v1/csrf' 'csrfTokenRequestHandler\(csrfTokenRequestHandler\)'
CSRF_TEST=backend/src/test/java/com/mafia/classicmafia/CsrfHttpRoundTripIntegrationTests.java
check 'CSRF real HTTP regression asserts HttpOnly XSRF and no session cookie' all_have "$CSRF_TEST" \
  'XSRF-TOKEN' 'HttpOnly' 'MAFIASESSION' 'assertFalse'
CSRF_API=backend/src/main/java/com/mafia/classicmafia/api/AuthApiController.java
check 'CSRF endpoint exposes materialized token without masked-token repository overwrite' all_have "$CSRF_API" \
  '@GetMapping\("/csrf"\)' 'CsrfToken token'
check 'CSRF endpoint never re-saves request-handler token into repository' lacks "$CSRF_API" 'csrfTokenRepository\.saveToken'
check 'CSRF SPA cookie-header roundtrip regression exists' all_have "$CSRF_TEST" \
  'csrfEndpointUsesSingleRawCookieAndMaskedHeaderToken' 'X-XSRF-TOKEN|headerName' 'maskedToken' 'CAPTCHA_INVALID'

PW=backend/src/main/java/com/mafia/classicmafia/security/PasswordPolicy.java
check 'User password minimum is 6' has "$PW" 'MIN_LENGTH = 6|от 6 до 64'

RL=backend/src/main/java/com/mafia/classicmafia/security/RateLimitService.java
check 'Rate limiter is DB backed and bounded' all_have "$RL" \
  'SecurityRateLimitBucketRepository' 'MAX_COUNTERS = 50_000' 'deleteExpired' 'locks\.lock\("rate-limit-bucket"'
check 'Rate limiter does not use JVM ConcurrentHashMap' lacks "$RL" 'ConcurrentHashMap'
check 'Rate limiter hashes bucket material' has "$RL" 'hashes\.hash\("rate-limit:'
check 'Rate limiter locked consume has transaction boundary' python3 - <<'PYRLTX'
from pathlib import Path
s=Path('backend/src/main/java/com/mafia/classicmafia/security/RateLimitService.java').read_text()
needle='@Transactional\n    boolean consume(String bucketKey, int limit, Duration duration)'
raise SystemExit(0 if needle in s else 1)
PYRLTX
check 'Login limiter has IP and IP/username pair, no global username bucket' bash -c \
  "grep -q 'login-ip' '$RL' && grep -q 'login-pair' '$RL' && ! grep -Eq 'login-(user|username)' '$RL'"

REG=backend/src/main/java/com/mafia/classicmafia/service/RegistrationService.java
check 'Registration preserves 3/hour and 10/day quotas' all_have "$REG" \
  'MAX_REGISTRATIONS_PER_HOUR = 3' 'MAX_REGISTRATIONS_PER_DAY = 10'
check 'Registration serializes username and quota before count/insert' all_have "$REG" \
  'databaseLocks\.lock\("registration-username"' 'databaseLocks\.lock\("registration-quota"' \
  'countByRegistrationIpHashAndCreatedAtAfter' 'users\.saveAndFlush'
check 'Registration transaction covers quota and insert' has "$REG" '@Transactional'

V9=backend/src/main/resources/db/migration/V9__retire_mfa_runtime.sql
check 'V9 retires MFA secrets and runtime state' all_have "$V9" \
  'DELETE FROM admin_mfa_recovery_codes' 'DELETE FROM admin_mfa_credentials' \
  "bucket_key LIKE 'mfa-ip:%'" "bucket_key LIKE 'mfa-pair:%'" 'DELETE FROM security_sessions'
check 'MFA runtime classes and API DTOs are removed' bash -c \
  "test ! -e backend/src/main/java/com/mafia/classicmafia/security/AdminMfaService.java && test ! -e backend/src/main/java/com/mafia/classicmafia/security/MfaCryptoService.java && test ! -d backend/src/main/java/com/mafia/classicmafia/api/dto/mfa"
check 'ADMIN sensitive reauth uses current password only' all_have backend/src/main/java/com/mafia/classicmafia/service/SensitiveActionService.java \
  'allowSensitiveReauth' 'passwordEncoder.matches'
check 'ADMIN sensitive reauth contains no MFA dependency' lacks backend/src/main/java/com/mafia/classicmafia/service/SensitiveActionService.java \
  'AdminMfa|MFA_|mfaCode|allowMfaAttempt|TOTP|recovery'
check 'Auth controller exposes no MFA route or pending state' lacks backend/src/main/java/com/mafia/classicmafia/api/AuthApiController.java \
  'auth/mfa|AdminMfa|mfaPending|MfaCode|TOTP|recovery'
check 'Production runtime config has no MFA secret requirement' bash -c \
  "! grep -RIE 'APP_SECURITY_MFA|mfa-encryption-key' compose.prod.yaml config/production.env.template backend/src/main/resources/application.properties >/dev/null"
check 'host transition preserves legacy MFA key only for v8 rollback compatibility' lacks ops/install-wp11-023-host-security.sh "if k == 'APP_SECURITY_MFA_ENCRYPTION_KEY': continue"

SS=backend/src/main/java/com/mafia/classicmafia/security/SecuritySessionService.java
SF=backend/src/main/java/com/mafia/classicmafia/security/SecuritySessionFilter.java
check 'Absolute session lifetimes are explicit' all_have "$SS" \
  'ADMIN_ABSOLUTE_LIFETIME = Duration\.ofDays\(30\)' 'USER_ABSOLUTE_LIFETIME = Duration\.ofDays\(90\)'
check 'Interactive server session uses 12-hour idle timeout' has backend/src/main/resources/application.properties 'server\.servlet\.session\.timeout=12h'
check 'Session cap is ADMIN 1 / others 3' all_have "$SS" 'role == Role\.ADMIN \? 1 : 3' 'enforceCap\(userId, role\)'
check 'Legacy ADMIN sessions fail closed' has "$SS" 'if \(role == Role\.ADMIN\) return false'
check 'Legacy session adoption serialized before enforcing cap' all_have "$SS" \
  'locks\.lock\("security-session-user"' 'sessions\.existsById\(sessionHash\)'
check 'Tracked session filter is installed' all_have "$SEC" 'SecuritySessionFilter' 'addFilterAfter'
check 'Invalid tracked session returns 401/invalidation' all_have "$SF" 'SESSION_EXPIRED' 'session\.invalidate\(\)'

PL=backend/src/main/java/com/mafia/classicmafia/security/PersistentLoginService.java
PF=backend/src/main/java/com/mafia/classicmafia/security/PersistentLoginFilter.java
PLM=backend/src/main/resources/db/migration/V10__persistent_login_tokens.sql
PLT=backend/src/test/java/com/mafia/classicmafia/PersistentLoginIntegrationTests.java
check 'V10 persistent-login migration exists and is server-revocable' all_have "$PLM" \
  'CREATE TABLE persistent_login_tokens' 'validator_hash VARCHAR\(64\)' 'REFERENCES users\(id\) ON DELETE CASCADE' \
  'credential_version INTEGER NOT NULL' 'authorization_version INTEGER NOT NULL' 'idx_persistent_login_expiry'
check 'Persistent login uses opaque rotated HttpOnly cookie, not JWT' all_have "$PL" \
  'COOKIE_NAME = "GOMAF_REMEMBER"' 'USER_LIFETIME = Duration\.ofDays\(90\)' 'ADMIN_LIFETIME = Duration\.ofDays\(30\)' \
  'HttpOnly' 'SameSite=Lax' 'secureCookie' 'setValidatorHash' 'getValidatorHash' 'MessageDigest\.isEqual' 'tokens\.delete\(token\)' 'create\(user, now\)'
check 'Persistent login token caps remain ADMIN 1 / others 3' all_have "$PL" 'role == Role\.ADMIN \? 1 : 3' 'enforceCap\(user\.id\(\), user\.role\(\)\)'
check 'Persistent login restores only a normal tracked server session' all_have "$PF" \
  'request\.getSession\(true\)' 'request\.changeSessionId\(\)' 'securityContextRepository\.saveContext' 'securitySessions\.register'
check 'Persistent login filter runs before version/session validation' python3 - <<'PYPERSISTFILTER'
from pathlib import Path
s=Path('backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java').read_text()
a=s.index('new PersistentLoginFilter')
b=s.index('new CredentialVersionFilter')
c=s.index('new SecuritySessionFilter')
raise SystemExit(0 if a < b < c else 1)
PYPERSISTFILTER
check 'Persistent login regression covers hashing rotation stale credentials and logout' all_have "$PLT" \
  'loginIssuesHttpOnlyPersistentCookieAndStoresOnlyValidatorHash' 'persistentCookieRestoresServerSessionAndRotatesToken' 'credentialVersionChangeRevokesPersistentLoginFailClosed' 'logoutDeletesPersistentTokenAndClearsCookie'
check 'Browser auth code does not store bearer credentials in Web Storage' bash -c \
  "! grep -RIE 'localStorage|sessionStorage' frontend/src/auth frontend/src/api frontend/src/profile frontend/src/admin >/dev/null"

GAME=backend/src/main/java/com/mafia/classicmafia/service/GameService.java
check 'New video links are HTTPS-only' all_have "$GAME" \
  'scheme\.equalsIgnoreCase\("https"\)' 'uri\.getHost\(\) == null' 'должна использовать HTTPS' 

COMPOSE=compose.prod.yaml
check 'Compose includes separated migration chain' bash -c \
  "python3 - <<'PY'
import yaml
x=yaml.safe_load(open('$COMPOSE'))
s=x.get('services',{})
need=['db','db-role-bootstrap','db-migrate','db-finalize','app','caddy']
assert all(k in s for k in need)
assert s['app'].get('environment',{}).get('SPRING_FLYWAY_ENABLED') in [False,'false','FALSE']
PY"
BOOT=docker/db-role-bootstrap.sh
FINAL=docker/db-finalize-privileges.sh
check 'Runtime DB role loses schema ownership/CREATE' all_have "$FINAL" \
  'REVOKE CREATE ON SCHEMA' 'GRANT USAGE ON SCHEMA' 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES'
check 'Runtime DB role cannot read Flyway history' has "$FINAL" 'REVOKE ALL .*flyway_schema_history'
check 'Migration role is distinct and schema owner' all_have "$BOOT" 'MIGRATION_DB_USER' 'ALTER SCHEMA public OWNER TO \$MIGRATION_DB_USER'
check 'DB bootstrap transfers existing public relations to migration owner before Flyway' all_have "$BOOT" 'ALTER TABLE %I.%I OWNER TO \$MIGRATION_DB_USER' 'ALTER SEQUENCE %I.%I OWNER TO \$MIGRATION_DB_USER'

REHEARSAL=ops/migration-rehearsal.sh
check 'Migration-only startup is lazy in Compose and rehearsal' bash -c "grep -q 'SPRING_MAIN_LAZY_INITIALIZATION.*true' '$COMPOSE' && grep -q 'SPRING_MAIN_LAZY_INITIALIZATION=true' ops/migration-rehearsal.sh"
check 'Migration rehearsal has negative runtime DDL test' all_have "$REHEARSAL" \
  'CREATE TABLE' 'flyway_schema_history' 'APP_DB_RUNTIME_USER|APP_DB_USER'

HOST=ops/install-wp11-023-host-security.sh
check 'mafia is removed from docker and gomaf groups' all_have "$HOST" \
  'for g in docker gomaf' 'gpasswd -d mafia \"\$g\"'
check 'production.env becomes root-only' all_have "$HOST" 'root:root:600' 'chmod 0600|install -m 0600'
check 'opsadmin and mafia require distinct SSH keys' has "$HOST" 'opsadmin and mafia must use distinct SSH keys'
check 'mafia sudo policy exposes only audited no-argument helpers' all_have "$HOST" \
  'mafia ALL=\(root\) NOPASSWD: /usr/local/sbin/gomaf-predeploy-release ""' \
  'mafia ALL=\(root\) NOPASSWD: /usr/local/sbin/gomaf-deploy-release ""'
check 'mafia has no unrestricted sudo rule' bash -c "! grep -Eq '^mafia .*NOPASSWD:[[:space:]]*ALL' '$HOST'"
check 'host installer validates sudoers with visudo' has "$HOST" 'visudo -cf'
check 'app log directory is pre-created for UID 10001' has "$HOST" 'install -d -m 0750 -o 10001 -g 10001 /srv/gomaf-logs/app'

COMMON=ops/root/gomaf-release-common
PRE=ops/root/gomaf-predeploy-release
DEP=ops/root/gomaf-deploy-release
check 'root deployment helpers exist' bash -c "test -f '$COMMON' -a -f '$PRE' -a -f '$DEP'"
check 'candidate archive integrity is verified with safe ZIP and internal SHA-256 manifest' all_have "$COMMON" 'release_validate_zip_structure' 'RELEASE-SHA256SUMS.txt'
check 'candidate release validates expected previous source hash' all_have "$COMMON" 'expected.*source|EXPECTED.*SOURCE|expected_source' 'active_source|current_source'
check 'release helpers reject arguments' all_have "$COMMON" 'release_require_root_no_args' '\[\[ \$# -eq 0 \]\]'
check 'release helper protects against replay/downgrade' bash -c "grep -Eq 'release_sequence|SEQUENCE' '$COMMON' '$PRE' '$DEP'"
check 'release private key is not shipped' bash -c "! find . -type f \( -name '*.key' -o -name '*private*.pem' \) -print -quit | grep -q ."

SETUP=ops/setup-server.sh
check 'fresh setup does not grant mafia docker/gomaf membership' bash -c \
  "! grep -Eq 'usermod .*docker.*mafia|usermod .*gomaf.*mafia|docker,.*gomaf.*mafia' '$SETUP'"
check 'fresh setup keeps production.env root-only' all_have "$SETUP" 'chown root:root /etc/gomaf /etc/gomaf/production.env' 'chmod 0600 /etc/gomaf/production.env'
check 'daily backup unit is root-only and does not grant mafia Docker group' bash -c "grep -q '^User=root$' ops/install-backup-timer.sh && grep -q '^Group=root$' ops/install-backup-timer.sh && grep -q '^UMask=0077$' ops/install-backup-timer.sh && ! grep -q 'SupplementaryGroups=docker' ops/install-backup-timer.sh"
check 'candidate-bound host transition reinstalls hardened backup unit' has "$HOST" 'CANDIDATE_ROOT/ops/install-backup-timer.sh'
check 'SSH TCP and stream-local forwarding are disabled' all_have "$HOST" 'AllowTcpForwarding no' 'AllowStreamLocalForwarding no' 'PermitOpen none' 'PermitListen none'

# Toolchain/supply-chain invariants intentionally fail until the work is actually completed.
PKG=frontend/package-lock.json
check 'frontend lockfile uses Vite 6.4.3' python3 - <<'PYVITE'
import json
p=json.load(open('frontend/package-lock.json', encoding='utf-8'))
raise SystemExit(0 if p['packages']['']['devDependencies'].get('vite')=='6.4.3' and p['packages']['node_modules/vite'].get('version')=='6.4.3' else 1)
PYVITE
check 'frontend lockfile contains esbuild >=0.25.0' python3 - <<'PYESBUILD'
import json
p=json.load(open('frontend/package-lock.json', encoding='utf-8'))
v=p['packages']['node_modules/esbuild']['version']
parts=tuple(int(x) for x in v.split('.')[:3])
raise SystemExit(0 if parts >= (0,25,0) else 1)
PYESBUILD
check 'frontend React Router is at least 7.18.2' python3 - <<'PYROUTER'
import json
pkg=json.load(open('frontend/package.json', encoding='utf-8'))
lock=json.load(open('frontend/package-lock.json', encoding='utf-8'))
def ver(v): return tuple(int(x) for x in v.split('.')[:3])
want=(7,18,2)
assert ver(pkg['dependencies']['react-router-dom']) >= want
assert ver(lock['packages']['node_modules/react-router-dom']['version']) >= want
assert ver(lock['packages']['node_modules/react-router']['version']) >= want
PYROUTER
check 'Tomcat embed core is pinned to patched 11.0.25' has backend/pom.xml '<tomcat.version>11\.0\.25</tomcat.version>'
check 'Dockerfile records exact Temurin 21.0.12.1 CSPU' has Dockerfile '21\.0\.12\.1'
check 'Docker FROM images are digest pinned' bash -c \
  "awk '/^FROM /{if(\$2 !~ /@sha256:[0-9a-f]{64}$/) bad=1} END{exit bad}' Dockerfile"
check 'WP11-023 predeploy gate exists' test -x ops/wp11-023-predeploy.sh
check 'SBOM gate exists' bash -c "test -x ops/generate-sbom.sh || test -x ops/supply-chain-local.sh"
check 'online vulnerability-intelligence gate exists' test -x ops/online-vulnerability-scan.sh
check 'Trivy keeps fresh DB intelligence while avoiding package-manager API lookups' all_have ops/online-vulnerability-scan.sh '--offline-scan' 'vuln,misconfig,secret' 'image --offline-scan'
check 'Trivy vulnerability gate never skips DB updates' lacks ops/online-vulnerability-scan.sh '--skip-db-update|--skip-java-db-update'
check 'bootstrap PREDEPLOY gate exists and shadows production runtime state' all_have ops/bootstrap-wp11-023-predeploy.sh 'runtime-shadow' 'predeploy.env' 'BOOTSTRAP PREDEPLOY PASS'
check 'host-security transition requires candidate-bound bootstrap PASS' all_have "$HOST" 'bootstrap-predeploy-evidence' 'ARCHIVE_SHA256' 'RELEASE_SEQUENCE' 'EXPECTED_CURRENT_SOURCE_HASH' 'No host mutation happened above this line'
check 'release deploy runs production audit before anti-replay commit' all_have ops/root/gomaf-deploy-release 'production-audit.sh' 'PRODUCTION AUDIT PASS' 'last_sequence'
check 'predeploy and deploy evidence are downloadable by restricted deployment identity' bash -c "grep -q 'final-predeploy-evidence.tar.gz' ops/root/gomaf-predeploy-release && grep -q 'deployment-evidence.tar.gz' ops/root/gomaf-deploy-release"
check 'PREDEPLOY and deploy are bound to the exact tested production image ID' python3 - <<'PYEXACTIMAGESEC'
from pathlib import Path
pre=Path('ops/wp11-023-predeploy.sh').read_text()
pre_root=Path('ops/root/gomaf-predeploy-release').read_text()
deploy_root=Path('ops/root/gomaf-deploy-release').read_text()
deploy=Path('ops/deploy.sh').read_text()
for token in ('CANDIDATE_SOURCE_HASH=$SOURCE_HASH','CANDIDATE_IMAGE=$IMAGE','CANDIDATE_IMAGE_ID=$IMAGE_ID','KEEP_IMAGE=1'):
    assert token in pre
for token in ('candidate_image_id=','computed_source_hash=','predeploy exact candidate image missing or image id changed'):
    assert token in pre_root
for token in ('GOMAF_APPROVED_IMAGE_ID="$candidate_image_id"','GOMAF_APPROVED_SOURCE_HASH="$candidate_source_hash"'):
    assert token in deploy_root
for token in ('GOMAF_APPROVED_IMAGE_ID','docker image tag "$APPROVED_IMAGE_ID" "$NEW_IMAGE"','Production tag does not point to exact PREDEPLOY image id.'):
    assert token in deploy
assert deploy.count('docker build --pull') == 0
PYEXACTIMAGESEC
check 'SBOM and vulnerability scanner runtimes are resource bounded' python3 - <<'PYBOUNDEDSCANSEC'
from pathlib import Path
import re
sbom=Path('ops/generate-sbom.sh').read_text()
vuln=Path('ops/online-vulnerability-scan.sh').read_text()
for text in (sbom,vuln):
    assert 'gomaf-safe-test-run' in text
    assert text.count('GOMAXPROCS=1') >= 2
    assert not re.search(r'(?m)^\s*docker\s+run', text)
assert '"$SOURCE_C" 512 0.50' in sbom and '"$IMAGE_C" 512 0.50' in sbom
assert '"$SOURCE_C" 768 0.50' in vuln and '"$IMAGE_C" 768 0.50' in vuln
PYBOUNDEDSCANSEC
check 'heavy PREDEPLOY phases require recovered resource gate before launch' python3 - <<'PYINTERPHASESEC'
from pathlib import Path
pre=Path('ops/wp11-023-predeploy.sh').read_text()
reh=Path('ops/migration-rehearsal.sh').read_text()
preflight=Path('ops/root/gomaf-test-preflight').read_text()
assert '--resources-only' in preflight
for label in ('backend test image build','exact production image build','SBOM scan','vulnerability scan','migration rehearsal','browser E2E backend','browser E2E runner'):
    assert label in pre
for label in ('PostgreSQL concurrency gate','production snapshot copy','Flyway candidate migration','candidate runtime','rollback runtime'):
    assert label in reh
assert pre.count('wait_resources ') >= 7
assert reh.count('wait_resources ') >= 5
PYINTERPHASESEC
check 'PREDEPLOY/deploy heavy validation is resource bounded' python3 - <<'PYBOUNDEDSEC'
from pathlib import Path
import re
pre=Path('ops/wp11-023-predeploy.sh').read_text()
deploy=Path('ops/deploy.sh').read_text()
reh=Path('ops/migration-rehearsal.sh').read_text()
for token in (
    '"$SAFE_BUILD" "gomaf-predeploy-backend-build-$TOKEN" 1024 0.75',
    '"$SAFE_BUILD" "gomaf-predeploy-app-build-$TOKEN" 1024 0.75',
    '"$SAFE_RUN" "$E2E_BACKEND" 512 0.50',
    '"$SAFE_RUN" "$BROWSER_CONTAINER" 768 0.75',
):
    assert token in pre
assert not re.search(r'(?m)^\s*docker\s+(?:build|run)', pre)
for token in ('gomaf-deploy-caddy-validate-$SOURCE_HASH','gomaf-deploy-backend-build-$RELEASE_ID'):
    assert token in deploy
assert not re.search(r'(?m)^\s*docker\s+(?:build|run)', deploy)
for token in ('bounded_small()','bounded_db()','bounded_app()','bounded_maven()','--memory=768m','--cpus=0.75'):
    assert token in reh
PYBOUNDEDSEC
check 'migration rehearsal consumes signed digest-pinned Postgres image' all_have ops/migration-rehearsal.sh '.wp11-023-security-tools.env' 'POSTGRES_IMAGE' '@sha256:'
check 'PREDEPLOY requires PostgreSQL participation concurrency gate' bash -c "grep -q 'TEST_IMAGE' ops/wp11-023-predeploy.sh && grep -q 'migration-rehearsal.sh \"\$IMAGE\" \"\$ROOT\" \"\" \"\$TEST_IMAGE\"' ops/wp11-023-predeploy.sh && grep -q 'PostgresParticipationConcurrencyIntegrationTests' ops/migration-rehearsal.sh && grep -q 'gomaf.postgres.concurrency=true' ops/migration-rehearsal.sh && grep -q 'gomaf_concurrency' ops/migration-rehearsal.sh && grep -q 'PostgreSQL concurrency gate PASS' ops/migration-rehearsal.sh"

check 'API route contract matrix covers exact 57-route surface' python3 - <<'PYAPIMATRIX'
from pathlib import Path
p=Path('backend/src/test/resources/api-route-contract-matrix.tsv')
rows=[line.split('\t') for line in p.read_text().splitlines()[1:] if line]
assert len(rows)==57 and len({r[0] for r in rows})==57
assert sum('json-body' in r[2].split(';') for r in rows)==21
assert sum('authn-boundary' in r[2].split(';') for r in rows)==40
assert sum('query-boundary' in r[2].split(';') for r in rows)==8
t=Path('backend/src/test/java/com/mafia/classicmafia/ApiRouteContractMatrixIntegrationTests.java').read_text()
assert 'RequestMappingHandlerMapping' in t and 'everyAuthnBoundaryRowRejectsAnonymousRequests' in t
PYAPIMATRIX
check 'authenticated browser quality gate covers ADMIN console and cross-browser a11y' all_have frontend/e2e/authenticated-quality.spec.ts 'AxeBuilder' 'loginCredentials' 'assertKeyboardFocus' '390, 844' '1440, 900' '/admin/moderators' 'browserName'
check 'PREDEPLOY screenshot evidence is complete, checksummed and approval-bound' bash -c "grep -q 'expected 215 Playwright screenshots' ops/wp11-023-predeploy.sh && grep -q 'SCREENSHOT-SHA256SUMS.txt' ops/wp11-023-predeploy.sh && grep -q 'SCREENSHOT_MANIFEST_SHA256' ops/wp11-023-predeploy.sh && grep -q 'predeploy screenshot evidence mismatch' ops/root/gomaf-predeploy-release && grep -q 'SCREENSHOT_COUNT=215' ops/root/gomaf-predeploy-release"
check 'production deploy consumes verified immutable image lock' all_have ops/deploy.sh '.wp11-023-security-tools.env' 'POSTGRES_IMAGE' 'CADDY_IMAGE' 'RUNTIME_BASE_IMAGE' 'MAVEN_IMAGE' 'NODE_IMAGE'
if [[ -f .wp11-023-security-tools.env ]]; then
  check 'verified immutable image lock pins all required external tools/images' python3 - <<'PYLOCK'
from pathlib import Path
import re
vals={}
for line in Path('.wp11-023-security-tools.env').read_text().splitlines():
    if '=' in line: k,v=line.split('=',1); vals[k]=v
for k in ('NODE_IMAGE','MAVEN_IMAGE','RUNTIME_BASE_IMAGE','POSTGRES_IMAGE','CADDY_IMAGE','PLAYWRIGHT_IMAGE','SYFT_IMAGE','TRIVY_IMAGE'):
    assert re.search(r'@sha256:[0-9a-f]{64}$',vals.get(k,'')), k
assert vals.get('ADOPTIUM_RELEASE')=='jdk-21.0.12.1+1'
assert re.fullmatch(r'[0-9a-f]{64}',vals.get('TEMURIN_JDK_SHA256',''))
assert re.fullmatch(r'[0-9a-f]{64}',vals.get('TEMURIN_JRE_SHA256',''))
PYLOCK
  check 'production Compose infrastructure images are digest-pinned' python3 - <<'PYCOMPOSEPINS'
from pathlib import Path
import re
refs=[]
for line in Path('compose.prod.yaml').read_text().splitlines():
    m=re.match(r'\s*image:\s*(postgres(?:[:@][^\s]+)?|caddy(?:[:@][^\s]+)?)\s*$', line)
    if m:
        refs.append(m.group(1))
assert len(refs)==4, refs
assert sum(r.startswith('postgres') for r in refs)==3, refs
assert sum(r.startswith('caddy') for r in refs)==1, refs
for r in refs:
    assert re.search(r'@sha256:[0-9a-f]{64}$', r), r
PYCOMPOSEPINS
else
  bad 'verified immutable image lock pins all required external tools/images'
  bad 'production Compose infrastructure images are digest-pinned'
fi

printf 'WP11_023_SECURITY_AUDIT_PASS=%d\n' "$pass"
printf 'WP11_023_SECURITY_AUDIT_FAIL=%d\n' "$fail"
if (( fail != 0 )); then
  printf 'WP11_023_SECURITY_AUDIT_STATUS=FAIL\n'
  exit 1
fi
printf 'WP11_023_SECURITY_AUDIT_STATUS=PASS\n'
