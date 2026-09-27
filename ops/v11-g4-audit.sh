#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
pass=0
fail=0
ok(){ printf 'PASS  %s\n' "$1"; pass=$((pass+1)); }
bad(){ printf 'FAIL  %s\n' "$1" >&2; fail=$((fail+1)); }
req(){ local label="$1"; shift; if "$@"; then ok "$label"; else bad "$label"; fi; }
grepq(){ local pattern="$1" file="$2" label="$3"; if grep -Eq "$pattern" "$file"; then ok "$label"; else bad "$label"; fi; }

req 'frontend/src/auth/RegisterPage.tsx exists' test -f frontend/src/auth/RegisterPage.tsx
req 'backend SpaController exists' test -f backend/src/main/java/com/mafia/classicmafia/controller/SpaController.java
req 'backend InfrastructureController exists' test -f backend/src/main/java/com/mafia/classicmafia/controller/InfrastructureController.java
req 'backend SpaWebConfig exists' test -f backend/src/main/java/com/mafia/classicmafia/config/SpaWebConfig.java
req 'API fallback controller exists' test -f backend/src/main/java/com/mafia/classicmafia/api/ApiFallbackController.java
req 'production SPA integration tests exist' test -f backend/src/test/java/com/mafia/classicmafia/ProductionSpaIntegrationTests.java
req 'G4 Playwright suite exists' test -f frontend/e2e/g4.spec.ts

req 'SPA toggle property retired' bash -c '! grep -q "app.spa.enabled" backend/src/main/resources/application.properties'
req 'production SPA toggle env retired' bash -c '! grep -q "APP_SPA_ENABLED" compose.prod.yaml'
req 'SPA controller unconditional' bash -c '! grep -q "ConditionalOnProperty" backend/src/main/java/com/mafia/classicmafia/controller/SpaController.java'
req 'SPA fallback contains no API namespace' bash -c '! grep -q "/api" backend/src/main/java/com/mafia/classicmafia/controller/SpaController.java'
grepq 'classpath:/static/index[.]html' backend/src/main/java/com/mafia/classicmafia/controller/SpaController.java 'SPA controller defaults to embedded static index'
grepq 'CacheControl[.]noCache' backend/src/main/java/com/mafia/classicmafia/controller/SpaController.java 'SPA index is not immutable cached'
grepq 'Duration[.]ofDays\(365\).*immutable' backend/src/main/java/com/mafia/classicmafia/config/SpaWebConfig.java 'fingerprinted assets use immutable one-year cache'

req 'legacy browser controllers retired' python3 - <<'PY'
from pathlib import Path
names=['PageController','AuthController','GameController','ScheduleController','TournamentController','ProfileController','SectionAdminController','ModeratorAdminController','AdminController']
base=Path('backend/src/main/java/com/mafia/classicmafia/controller')
raise SystemExit(0 if all(not (base/f'{n}.java').exists() for n in names) else 1)
PY
req 'Thymeleaf dependency retired' bash -c '! grep -q "spring-boot-starter-thymeleaf" backend/pom.xml'
req 'Thymeleaf templates retired' bash -c 'test ! -d backend/src/main/resources/templates'
req 'legacy form-login CAPTCHA filter retired' test ! -f backend/src/main/java/com/mafia/classicmafia/security/CaptchaLoginFilter.java
grepq '@GetMapping\("/healthz"\)' backend/src/main/java/com/mafia/classicmafia/controller/InfrastructureController.java 'healthz remains independent from React'
grepq '@GetMapping\("/avatar/\{id\}"\)' backend/src/main/java/com/mafia/classicmafia/controller/InfrastructureController.java 'legacy avatar compatibility endpoint remains independent'
grepq '@RequestMapping\("/forbidden"\)' backend/src/main/java/com/mafia/classicmafia/controller/InfrastructureController.java '403 browser endpoint remains independent'

grepq 'script-src .self.' backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java 'CSP permits self-hosted compiled scripts'
req 'CSP has no unsafe script directives' bash -c '! grep -Eq "unsafe-inline|unsafe-eval" backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java'
grepq 'img-src .self. data:' backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java 'CSP permits only self/data images'
grepq "script-src .self." backend/src/test/java/com/mafia/classicmafia/SecurityIntegrationTests.java 'security integration test expects v11 self-hosted script CSP'
req 'security integration test no longer expects legacy script-none CSP' bash -c '! grep -F "script-src '"'"'none'"'"'" backend/src/test/java/com/mafia/classicmafia/SecurityIntegrationTests.java >/dev/null'
grepq "script-src .self." SECURITY_AUDIT.md 'security audit documents v11 self-hosted script CSP'
req 'security audit documents no unsafe script directives' bash -c '! grep -Eq "CSP.*unsafe-inline.*разреш|CSP.*unsafe-eval.*разреш" SECURITY_AUDIT.md'
grepq '"/assets/[*][*]"' backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java 'static assets are anonymously readable'

grepq '@RequestMapping\("/\{[*]path\}"\)' backend/src/main/java/com/mafia/classicmafia/api/ApiFallbackController.java 'unknown v1 API has explicit fallback'
grepq 'API_NOT_FOUND' backend/src/main/java/com/mafia/classicmafia/api/ApiFallbackController.java 'API fallback emits canonical not-found code'
grepq 'authenticatedUnknownApiPathReturnsJson404AndNeverSpa' backend/src/test/java/com/mafia/classicmafia/ProductionSpaIntegrationTests.java 'integration test covers authenticated API 404 vs SPA'
req 'production-like E2E cannot be shadowed by test static index' test ! -f backend/src/test/resources/static/index.html

grepq 'registerAccount' frontend/src/api/client.ts 'React registration calls API client'
grepq 'path="/register"' frontend/src/app/App.tsx 'React router exposes registration route'
grepq 'minLength=\{6\}' frontend/src/auth/RegisterPage.tsx 'React registration preserves 6-char password minimum'
grepq '/captcha/register' frontend/src/auth/RegisterPage.tsx 'React registration uses registration CAPTCHA'
req 'frontend auth still avoids browser token storage' bash -c '! grep -RIE "localStorage|sessionStorage|Bearer[[:space:]]" frontend/src >/dev/null'
req 'frontend still contains no JWT auth' bash -c '! grep -RIE "jwt|jsonwebtoken" frontend/src frontend/package.json >/dev/null'

grepq '^FROM node:22[.]23[.]2-bookworm-slim AS frontend-build$' Dockerfile 'Docker pins Node 22.23.2 frontend stage'
grepq '^RUN npm ci$' Dockerfile 'Docker uses deterministic npm ci'
req 'Docker context excludes local frontend/backend build artifacts' bash -c 'grep -qx "frontend/node_modules" .dockerignore && grep -qx "frontend/dist" .dockerignore && grep -qx "backend/target" .dockerignore'
req 'Docker runs all mandatory frontend gates' python3 - <<'PY'
from pathlib import Path
s=Path('Dockerfile').read_text()
for token in ('npm run lint','npm run typecheck','npm run test','npm run build'):
    if token not in s: raise SystemExit(1)
PY
grepq "find dist -type f -name '[*][.]map'" Dockerfile 'Docker rejects production source maps'
grepq 'generated-resources/spa' backend/pom.xml 'Maven includes generated SPA resources'
grepq '<targetPath>static</targetPath>' backend/pom.xml 'Maven places Vite output under classpath static'
grepq 'BOOT-INF/classes/static/index[.]html' Dockerfile 'Docker proves SPA index is embedded in jar'
grepq 'BOOT-INF/classes/static/assets/.*js' Dockerfile 'Docker proves SPA JS asset is embedded in jar'
grepq 'mvn -B verify' Dockerfile 'Docker backend stage requires mvn verify'
grepq '^FROM eclipse-temurin:21[.]0[.]12_8-jre-noble$' Dockerfile 'final runtime remains pinned Java-only image'
grepq '^USER 10001$' Dockerfile 'final runtime remains non-root'

grepq 'source-hash[.]sh" "[$]STAGING"' ops/deploy.sh 'deploy source hash uses canonical deployment-critical release identity'
grepq 'docker pull node:22[.]23[.]2-bookworm-slim' ops/deploy.sh 'deploy pre-pulls pinned Node image'
grepq 'internal_spa_html=' ops/deploy.sh 'candidate smoke checks embedded SPA index'
grepq 'internal_asset=' ops/deploy.sh 'candidate smoke checks fingerprinted asset'
grepq '/api/v1/session' ops/deploy.sh 'candidate smoke checks API session endpoint'
grepq '/api/v1/auth/register' ops/deploy.sh 'candidate CSRF smoke uses API mutation'
grepq "script-src .self." ops/deploy.sh 'candidate smoke enforces production CSP'
req 'migration rehearsal no longer injects retired SPA toggle' bash -c '! grep -q "APP_SPA_ENABLED" ops/migration-rehearsal.sh'
grepq 'retired APP_SPA_ENABLED toggle still present' ops/production-audit.sh 'production audit rejects retired SPA toggle'
grepq 'fingerprinted JS asset' ops/smoke-test.sh 'HTTPS smoke validates embedded SPA asset'
grepq 'Unknown API path' ops/smoke-test.sh 'HTTPS smoke rejects SPA fallback for API namespace'
grepq 'unsafe-inline|unsafe-eval' ops/smoke-test.sh 'HTTPS smoke rejects unsafe CSP directives'

grepq 'process[.]env[.]PLAYWRIGHT_BASE_URL' frontend/playwright.config.ts 'Playwright base URL can target Spring production server'
grepq 'Spring serves the embedded SPA' frontend/e2e/g4.spec.ts 'G4 E2E covers Spring-served embedded SPA'
grepq 'registration is a React route' frontend/e2e/g4.spec.ts 'G4 E2E covers registration route'
grepq 'unknown API route never falls back to SPA' frontend/e2e/g4.spec.ts 'G4 E2E covers API fallback isolation'

grepq 'VERSION="[$]\{1:-v11\}"' ops/package-release.sh 'release packaging defaults to v11'
grepq 'npm ci/lint/typecheck/test/build' ops/package-release.sh 'release handoff documents frontend gates'

for spec in \
  backend/src/main/resources/db/migration/V1__initial_schema.sql \
  backend/src/main/resources/db/migration/V2__production_indexes.sql \
  backend/src/main/resources/db/migration/V3__roles_moderator_scopes.sql \
  backend/src/main/resources/db/migration/V4__schedule_tournaments_profiles_video.sql; do
  case "$spec" in
    *V1*) expected=4a19c886e98bb8b4ee3cc28f9d86bf466582d2d7bd35bfc7e5462205e188de16 ;;
    *V2*) expected=b3875b08740a947f19dd70133ff4081ff0d7f4e6ab2058317008be07c75f1d31 ;;
    *V3*) expected=b9f2be6f8341d78b915b9bf27ab71a229e810f59ea433936b97fa225b8f09ed8 ;;
    *V4*) expected=7b2e2f192ff843d1b9bde533535b0aa24de5b46d319e1852c8c6c0dce99e071d ;;
  esac
  actual="$(sha256sum "$spec" | awk '{print $1}')"
  if [[ "$actual" == "$expected" ]]; then ok "$spec baseline hash"; else bad "$spec baseline hash"; fi
done

printf '\nV11_G4_AUDIT_PASS=%d V11_G4_AUDIT_FAIL=%d\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
