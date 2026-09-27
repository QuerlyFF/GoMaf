#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
pass=0
fail=0
ok(){ printf 'PASS  %s\n' "$1"; pass=$((pass+1)); }
bad(){ printf 'FAIL  %s\n' "$1"; fail=$((fail+1)); }
check_file(){ [[ -f "$1" ]] && ok "$1 exists" || bad "$1 exists"; }
check_grep(){ local label="$1" pattern="$2" file="$3"; grep -Eq "$pattern" "$file" && ok "$label" || bad "$label"; }
check_absent(){ local label="$1" pattern="$2" file="$3"; if grep -Eq "$pattern" "$file"; then bad "$label"; else ok "$label"; fi; }

# WP11-007 administration API.
for file in \
  backend/src/main/java/com/mafia/classicmafia/api/PlayerAdministrationApiController.java \
  backend/src/main/java/com/mafia/classicmafia/api/dto/PlayerAdministrationCapabilitiesResponse.java \
  backend/src/main/java/com/mafia/classicmafia/service/PlayerAdministrationService.java \
  backend/src/test/java/com/mafia/classicmafia/PlayerAdministrationApiIntegrationTests.java; do
  check_file "$file"
done
check_grep 'rating mutation endpoint exists' '@PatchMapping\("/\{id\}/rating"\)' backend/src/main/java/com/mafia/classicmafia/api/PlayerAdministrationApiController.java
check_grep 'username mutation endpoint exists' '@PatchMapping\("/\{id\}/username"\)' backend/src/main/java/com/mafia/classicmafia/api/PlayerAdministrationApiController.java
check_grep 'password mutation endpoint exists' '@PutMapping\("/\{id\}/password"\)' backend/src/main/java/com/mafia/classicmafia/api/PlayerAdministrationApiController.java
check_grep 'avatar reset endpoint exists' '@DeleteMapping\("/\{id\}/avatar"\)' backend/src/main/java/com/mafia/classicmafia/api/PlayerAdministrationApiController.java
check_grep 'profile admin endpoint exists' '@PatchMapping\("/\{id\}/profile"\)' backend/src/main/java/com/mafia/classicmafia/api/PlayerAdministrationApiController.java
check_grep 'account deletion endpoint exists' '@DeleteMapping\("/\{id\}"\)' backend/src/main/java/com/mafia/classicmafia/api/PlayerAdministrationApiController.java
check_grep 'capabilities endpoint exists' '@GetMapping\("/\{id\}/administration"\)' backend/src/main/java/com/mafia/classicmafia/api/PlayerAdministrationApiController.java
check_grep 'sensitive API actions use shared reauthentication service' 'sensitiveActions\.verify' backend/src/main/java/com/mafia/classicmafia/api/PlayerAdministrationApiController.java
check_grep 'moderator scopes enforced server-side' 'permissions\.isAllowed' backend/src/main/java/com/mafia/classicmafia/api/PlayerAdministrationApiController.java
check_grep 'target restrictions remain server-side' 'requireManageableTarget' backend/src/main/java/com/mafia/classicmafia/service/PlayerAdministrationService.java
check_grep 'profile edit stays ADMIN only' 'operator\.getRole\(\) != Role\.ADMIN' backend/src/main/java/com/mafia/classicmafia/service/PlayerAdministrationService.java
check_grep 'username change invalidates target sessions' 'incrementCredentialVersion' backend/src/main/java/com/mafia/classicmafia/service/PlayerAdministrationService.java
check_grep 'administration API emits audit success events' 'audit\.success' backend/src/main/java/com/mafia/classicmafia/api/PlayerAdministrationApiController.java
check_grep 'administration API emits denied audit events' 'audit\.denied' backend/src/main/java/com/mafia/classicmafia/api/PlayerAdministrationApiController.java
check_absent 'capabilities DTO does not disclose target role' 'role|Role' backend/src/main/java/com/mafia/classicmafia/api/dto/PlayerAdministrationCapabilitiesResponse.java
check_grep 'administration tests cover CSRF' 'administrationMutationRequiresCsrf' backend/src/test/java/com/mafia/classicmafia/PlayerAdministrationApiIntegrationTests.java
check_grep 'administration tests cover reauth' 'usernameUpdateRequiresCurrentOperatorPassword' backend/src/test/java/com/mafia/classicmafia/PlayerAdministrationApiIntegrationTests.java
check_grep 'administration tests cover moderator target denial' 'NeverSelfOrAnotherModerator|neverSelfOrAnotherModerator|moderatorScopeCanManageOrdinaryPlayerButNeverSelfOrAnotherModerator' backend/src/test/java/com/mafia/classicmafia/PlayerAdministrationApiIntegrationTests.java
check_grep 'administration tests isolate sensitive reauth rate-limit IP' 'isolatedClientIp|setRemoteAddr' backend/src/test/java/com/mafia/classicmafia/PlayerAdministrationApiIntegrationTests.java

# WP11-008 React Players UI.
for file in frontend/src/players/PlayersPage.tsx frontend/src/players/PlayerProfilePage.tsx frontend/src/players/PlayerAdministrationPanel.tsx frontend/src/auth/LoginPage.tsx frontend/src/api/players.ts; do
  check_file "$file"
done
check_grep 'players list state is URL-backed' 'useSearchParams' frontend/src/players/PlayersPage.tsx
check_grep 'players UI uses TanStack Query' 'useQuery' frontend/src/players/PlayersPage.tsx
check_grep 'player mutations use TanStack Query' 'useMutation' frontend/src/players/PlayerAdministrationPanel.tsx
check_grep 'admin forms use React Hook Form' 'useForm' frontend/src/players/PlayerAdministrationPanel.tsx
check_grep 'admin forms use Zod validation' 'zodResolver' frontend/src/players/PlayerAdministrationPanel.tsx
check_grep 'UI reads backend-owned capabilities' 'getPlayerAdministration' frontend/src/players/PlayerProfilePage.tsx
check_grep 'long usernames have explicit wrap protection' 'overflow-wrap: anywhere' frontend/src/app/app.css
check_grep 'mobile responsive breakpoint exists' '@media \(max-width: 430px\)' frontend/src/app/app.css
check_grep 'tablet responsive breakpoint exists' '@media \(max-width: 900px\)' frontend/src/app/app.css
check_absent 'frontend auth does not use localStorage' 'localStorage|sessionStorage' frontend/src/api/client.ts
check_absent 'normal CRUD does not force page reload' 'window\.location|location\.reload' frontend/src/players/PlayerProfilePage.tsx

# WP11-009 Playwright browser E2E.
check_file frontend/playwright.config.ts
check_file frontend/e2e/players.spec.ts
check_file backend/src/test/java/com/mafia/classicmafia/e2e/E2eCaptchaService.java
check_file backend/src/test/java/com/mafia/classicmafia/e2e/E2eDataInitializer.java
check_file backend/src/test/resources/application-e2e.properties
check_grep 'Playwright dependency is pinned' '"@playwright/test": "1\.55\.0"' frontend/package.json
check_grep 'Playwright script exists' '"e2e": "playwright test"' frontend/package.json
check_grep 'Vitest excludes Playwright E2E suite' "exclude: \\[\.\.\.configDefaults\.exclude, 'e2e/\*\*'\\]" frontend/vitest.config.ts
check_grep 'E2E covers anonymous player list/profile' 'anonymous player list and profile' frontend/e2e/players.spec.ts
check_grep 'E2E covers real login UI' 'login and admin edit' frontend/e2e/players.spec.ts
check_grep 'E2E login assertion is scoped to visible session banner user' "getByRole\('banner'\).*locator\('\.session-user:visible'\).*toHaveText\(username" frontend/e2e/players.spec.ts
check_grep 'E2E covers moderator allowed operation' 'moderator allowed operation' frontend/e2e/players.spec.ts
check_grep 'E2E performs denied API operation' 'PLAYER_ADMIN_FORBIDDEN' frontend/e2e/players.spec.ts
check_grep 'E2E covers 390/1024/1440 widths' '\[390, 1024, 1440\]' frontend/e2e/players.spec.ts
check_grep 'E2E asserts document overflow' 'scrollWidth - document\.documentElement\.clientWidth' frontend/e2e/players.spec.ts
check_grep 'fixed CAPTCHA exists only under test profile' '@Profile\("e2e"\)' backend/src/test/java/com/mafia/classicmafia/e2e/E2eCaptchaService.java
if find backend/src/main -type f -print0 | xargs -0 grep -Eq 'FIXED_CODE|e2e-admin-password|E2eCaptchaService'; then bad 'production sources contain no E2E credential/captcha bypass'; else ok 'production sources contain no E2E credential/captcha bypass'; fi

# Immutable DB baseline remains unchanged.
declare -A expected=(
  [backend/src/main/resources/db/migration/V1__initial_schema.sql]='4a19c886e98bb8b4ee3cc28f9d86bf466582d2d7bd35bfc7e5462205e188de16'
  [backend/src/main/resources/db/migration/V2__production_indexes.sql]='b3875b08740a947f19dd70133ff4081ff0d7f4e6ab2058317008be07c75f1d31'
  [backend/src/main/resources/db/migration/V3__roles_moderator_scopes.sql]='b9f2be6f8341d78b915b9bf27ab71a229e810f59ea433936b97fa225b8f09ed8'
  [backend/src/main/resources/db/migration/V4__schedule_tournaments_profiles_video.sql]='7b2e2f192ff843d1b9bde533535b0aa24de5b46d319e1852c8c6c0dce99e071d'
)
for file in "${!expected[@]}"; do
  actual="$(sha256sum "$file" | awk '{print $1}')"
  [[ "$actual" == "${expected[$file]}" ]] && ok "$file baseline hash" || bad "$file baseline hash"
done

printf '\nV11_G2_AUDIT_PASS=%d V11_G2_AUDIT_FAIL=%d\n' "$pass" "$fail"
(( fail == 0 ))
