#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PASS=0
FAIL=0

ok() { printf 'PASS  %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf 'FAIL  %s\n' "$1"; FAIL=$((FAIL+1)); }
require_file() { [[ -f "$ROOT/$1" ]] && ok "$1 exists" || bad "$1 missing"; }
require_grep() { local pattern="$1" file="$2" label="$3"; grep -Eq -- "$pattern" "$ROOT/$file" && ok "$label" || bad "$label"; }
forbid_grep_tree() { local pattern="$1" path="$2" label="$3"; if grep -RIEq --exclude='package-lock.json' -- "$pattern" "$ROOT/$path" 2>/dev/null; then bad "$label"; else ok "$label"; fi; }

for f in \
  backend/pom.xml \
  frontend/package.json \
  frontend/tsconfig.json \
  frontend/vite.config.ts \
  frontend/src/main.tsx \
  backend/src/main/java/com/mafia/classicmafia/api/AuthApiController.java \
  backend/src/main/java/com/mafia/classicmafia/api/ApiExceptionHandler.java \
  backend/src/test/java/com/mafia/classicmafia/ApiFoundationIntegrationTests.java; do
  require_file "$f"
done

require_grep '"strict"[[:space:]]*:[[:space:]]*true' frontend/tsconfig.json 'TypeScript strict enabled'
require_grep '"lint"[[:space:]]*:' frontend/package.json 'frontend lint script exists'
require_grep '"typecheck"[[:space:]]*:' frontend/package.json 'frontend typecheck script exists'
require_grep '"test"[[:space:]]*:' frontend/package.json 'frontend test script exists'
require_grep '"build"[[:space:]]*:' frontend/package.json 'frontend build script exists'
require_grep '"react"[[:space:]]*:' frontend/package.json 'React declared'
require_grep '"@tanstack/react-query"[[:space:]]*:' frontend/package.json 'TanStack Query declared'
require_grep '"react-hook-form"[[:space:]]*:' frontend/package.json 'React Hook Form declared'
require_grep '"zod"[[:space:]]*:' frontend/package.json 'Zod declared'
require_grep '/api/v1/session' backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java 'session API security rule exists'
require_grep '/api/v1/csrf' backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java 'CSRF API security rule exists'
require_grep 'changeSessionId' backend/src/main/java/com/mafia/classicmafia/api/AuthApiController.java 'API login rotates session id'
require_grep 'securityContextRepository.saveContext' backend/src/main/java/com/mafia/classicmafia/api/AuthApiController.java 'API login persists SecurityContext server-side'
require_grep 'session.invalidate' backend/src/main/java/com/mafia/classicmafia/api/AuthApiController.java 'API logout invalidates HttpSession'
require_grep 'SESSION_STALE' backend/src/main/java/com/mafia/classicmafia/security/CredentialVersionFilter.java 'stale API session returns explicit error'
require_grep 'LoginGateService' backend/src/main/java/com/mafia/classicmafia/api/AuthApiController.java 'API login shares login gate'
require_grep 'RegistrationService' backend/src/main/java/com/mafia/classicmafia/api/AuthApiController.java 'API registration shares registration service'
require_grep 'script-src .self.' backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java 'production CSP allows only self-hosted scripts after cutover'

forbid_grep_tree 'localStorage|sessionStorage' frontend/src 'frontend does not store auth in browser storage'
forbid_grep_tree 'Bearer[[:space:]]|jsonwebtoken|jwt-decode|\bJWT\b' frontend 'frontend contains no JWT auth'
forbid_grep_tree 'CorsConfiguration|@CrossOrigin|allowedOrigins' backend/src/main/java 'backend does not introduce CORS'
forbid_grep_tree 'csrf\([^)]*disable|csrf[[:space:]]*\.disable' backend/src/main/java 'CSRF is not disabled'
forbid_grep_tree '<script[^>]+src=["'\'']https?://' frontend 'no third-party runtime script CDN'

MIGRATIONS=(backend/src/main/resources/db/migration/V1__initial_schema.sql backend/src/main/resources/db/migration/V2__production_indexes.sql backend/src/main/resources/db/migration/V3__roles_moderator_scopes.sql backend/src/main/resources/db/migration/V4__schedule_tournaments_profiles_video.sql)
EXPECTED=(
  4a19c886e98bb8b4ee3cc28f9d86bf466582d2d7bd35bfc7e5462205e188de16
  b3875b08740a947f19dd70133ff4081ff0d7f4e6ab2058317008be07c75f1d31
  b9f2be6f8341d78b915b9bf27ab71a229e810f59ea433936b97fa225b8f09ed8
  7b2e2f192ff843d1b9bde533535b0aa24de5b46d319e1852c8c6c0dce99e071d
)
for i in "${!MIGRATIONS[@]}"; do
  actual="$(sha256sum "$ROOT/${MIGRATIONS[$i]}" | awk '{print $1}')"
  [[ "$actual" == "${EXPECTED[$i]}" ]] && ok "${MIGRATIONS[$i]} baseline hash" || bad "${MIGRATIONS[$i]} changed"
done

if [[ -f "$ROOT/frontend/package-lock.json" ]]; then
  ok 'frontend/package-lock.json exists'
else
  bad 'frontend/package-lock.json missing (online bootstrap required)'
fi

printf '\nV11_G1_AUDIT_PASS=%d V11_G1_AUDIT_FAIL=%d\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
