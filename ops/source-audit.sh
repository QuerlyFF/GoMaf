#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
FAIL=0
PASS=0
ok() { printf 'PASS %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf 'FAIL %s\n' "$1" >&2; FAIL=$((FAIL+1)); }
assert_cmd() { local label="$1"; shift; if "$@"; then ok "$label"; else bad "$label"; fi; }

assert_cmd "backend/pom.xml is XML" python3 -c 'import xml.etree.ElementTree as E; E.parse("backend/pom.xml")'
assert_cmd "Spring Boot 4.1.1" grep -q '<version>4.1.1</version>' backend/pom.xml
assert_cmd "Java 21" grep -q '<java.version>21</java.version>' backend/pom.xml
assert_cmd "frontend package-lock is present" test -f frontend/package-lock.json
assert_cmd "frontend TypeScript strict enabled" grep -q '"strict": true' frontend/tsconfig.json
assert_cmd "frontend Playwright dependency pinned" grep -q '"@playwright/test": "1.55.0"' frontend/package.json
assert_cmd "Docker frontend stage uses approved Node 22.23.2 baseline or finalized immutable digest" grep -Eq '^FROM (node:22\.23\.2-bookworm-slim(@sha256:[0-9a-f]{64})?|node@sha256:[0-9a-f]{64}) AS frontend-build$' Dockerfile
assert_cmd "Docker frontend build uses deterministic npm ci" grep -Eq '^RUN npm ci( --no-audit --no-fund)?$' Dockerfile
assert_cmd "Docker frontend gate runs lint typecheck tests build" python3 - <<'PYDOCKERFRONT'
from pathlib import Path
s=Path('Dockerfile').read_text()
required=['npm run lint','npm run typecheck','npm run test','npm run build']
raise SystemExit(0 if all(x in s for x in required) else 1)
PYDOCKERFRONT
assert_cmd "Docker rejects production source maps" grep -Fq "find dist -type f -name '*.map'" Dockerfile
assert_cmd "backend packages generated SPA resources" grep -Fq '${project.build.directory}/generated-resources/spa' backend/pom.xml
assert_cmd "Docker copies verified SPA into backend generated resources" grep -Fq 'target/generated-resources/spa' Dockerfile
assert_cmd "Docker build runs mvn verify" grep -q 'mvn -B verify' Dockerfile
assert_cmd "runtime container is non-root" grep -q '^USER 10001$' Dockerfile
assert_cmd "application runtime enforces private log umask" bash -c 'grep -q "umask 027" docker/app-entrypoint.sh && grep -q "COPY --chmod=0555 docker/app-entrypoint.sh" Dockerfile && grep -q "ENTRYPOINT \[\"/app/entrypoint.sh\"\]" Dockerfile'
assert_cmd "runtime base is approved Temurin noble baseline or finalized immutable digest" grep -Eq '^FROM (eclipse-temurin:21\.0\.12_8-jre-noble(@sha256:[0-9a-f]{64})?|eclipse-temurin@sha256:[0-9a-f]{64})( AS runtime)?$' Dockerfile
assert_cmd "Flyway dependency enabled" grep -q 'spring-boot-starter-flyway' backend/pom.xml
assert_cmd "Hibernate production schema validation" grep -q 'SPRING_JPA_HIBERNATE_DDL_AUTO: validate' compose.prod.yaml
assert_cmd "app filesystem read-only" bash -c 'grep -A40 "^  app:" compose.prod.yaml | grep "read_only: true" >/dev/null'
assert_cmd "app drops Linux capabilities" bash -c 'grep -A45 "^  app:" compose.prod.yaml | grep "cap_drop:" >/dev/null'
assert_cmd "backend Docker network internal" bash -c 'grep -A4 "^  backend:" compose.prod.yaml | grep "internal: true" >/dev/null'
assert_cmd "Caddy is only service with host ports" python3 - <<'PYPORTS'
from pathlib import Path
text=Path('compose.prod.yaml').read_text(encoding='utf-8').splitlines()
current=None
with_ports=set()
for line in text:
    if line.startswith('  ') and not line.startswith('    ') and line.rstrip().endswith(':'):
        current=line.strip()[:-1]
    elif current and line.startswith('    ports:'):
        with_ports.add(current)
raise SystemExit(0 if with_ports == {'caddy'} else 1)
PYPORTS
assert_cmd "Caddy request body limit" grep -q 'max_size 6MB' caddy/Caddyfile
assert_cmd "Caddy strips Server header" grep -q -- '-Server' caddy/Caddyfile
assert_cmd "Caddy overwrites X-Forwarded-For" grep -q 'header_up X-Forwarded-For {remote_host}' caddy/Caddyfile
assert_cmd "Caddy overwrites X-Forwarded-Proto" grep -q 'header_up X-Forwarded-Proto {scheme}' caddy/Caddyfile
assert_cmd "Caddy blocks common secret/CMS probes before Spring" bash -c 'grep -q "@blocked_probes path" caddy/Caddyfile && grep -q "/.env" caddy/Caddyfile && grep -q "/.git" caddy/Caddyfile && grep -q "/actuator" caddy/Caddyfile && grep -q "/wp-admin" caddy/Caddyfile && grep -q "/phpmyadmin" caddy/Caddyfile && grep -q "respond @blocked_probes 404" caddy/Caddyfile'
assert_cmd "production enables native forwarded headers" grep -q 'SERVER_FORWARD_HEADERS_STRATEGY: NATIVE' compose.prod.yaml
assert_cmd "Tomcat early context redirect disabled" grep -q 'server.tomcat.redirect-context-root=false' backend/src/main/resources/application.properties
assert_cmd "production config template has domain" grep -q '^DOMAIN=gomaf.online$' config/production.env.template
assert_cmd "production admin password template quoted" grep -q "^APP_ADMIN_PASSWORD='CHANGE_ME_MINIMUM_12_CHARACTERS'$" config/production.env.template
assert_cmd "no real production.env in source" test ! -e production.env
assert_cmd "no .env in source" test ! -e .env

if find . -type f -iname '*.bat' -print -quit | grep -q .; then bad "Windows BAT files absent"; else ok "Windows BAT files absent"; fi
if grep -RIlE 'PowerShell|FIRST_SETUP_WINDOWS|START_SITE\.bat|WSL 2|Docker Desktop' . --exclude-dir=.deploy --exclude-dir=.audit --exclude='*.zip' --exclude='source-audit.sh' | grep -q .; then
  bad "Windows-only references absent"
else
  ok "Windows-only references absent"
fi

for f in ops/*.sh; do
  if bash -n "$f"; then ok "bash syntax $f"; else bad "bash syntax $f"; fi
done
for f in   ops/root/gomaf-export-logs   ops/root/gomaf-v10-admin-switch   ops/root/gomaf-aide-check   ops/root/gomaf-security-check   ops/root/gomaf-test-memory-guard   ops/root/gomaf-test-preflight   ops/root/gomaf-safe-test-run   ops/root/gomaf-safe-build   ops/root/gomaf-ops-alert   ops/root/gomaf-ops-health-check; do
  if bash -n "$f"; then ok "bash syntax $f"; else bad "bash syntax $f"; fi
done
if bash -n client/gomaf-deploy-update.sh.in; then ok "bash syntax client/gomaf-deploy-update.sh.in"; else bad "bash syntax client/gomaf-deploy-update.sh.in"; fi
if sh -n docker/db-prepare.sh; then ok "sh syntax docker/db-prepare.sh"; else bad "sh syntax docker/db-prepare.sh"; fi

if python3 - <<'PYPYTHON'
from pathlib import Path
path=Path('ops/root/gomaf-storage-maintenance')
compile(path.read_text(encoding='utf-8'), str(path), 'exec')
PYPYTHON
then ok "python syntax ops/root/gomaf-storage-maintenance"; else bad "python syntax ops/root/gomaf-storage-maintenance"; fi

if find . -path './.audit' -prune -o -type d \( -name '__pycache__' -o -name node_modules -o -name dist -o -name playwright-report -o -name test-results \) -print -quit | grep -q . || \
   find . -path './.audit' -prune -o -type f \( -name '*.pyc' -o -name '*.class' -o -name '*.jar' -o -name '*.dump' -o -name '*.bak' -o -name '*.map' -o -name '*~' \) -print -quit | grep -q .; then
  bad "source tree contains no generated/binary/backup artifacts"
else
  ok "source tree contains no generated/binary/backup artifacts"
fi
assert_cmd "compose.prod.yaml has required Compose structure (stdlib-only smoke)" python3 - <<'PYCOMPOSE'
from pathlib import Path
text=Path('compose.prod.yaml').read_text(encoding='utf-8')
lines=text.splitlines()
if not text.endswith('\n') or any('\t' in line for line in lines):
    raise SystemExit(1)
try:
    start=next(i for i,line in enumerate(lines) if line == 'services:')
except StopIteration:
    raise SystemExit(1)
services=set()
for line in lines[start+1:]:
    if line and not line.startswith(' '):
        break
    if line.startswith('  ') and not line.startswith('    ') and line.rstrip().endswith(':'):
        services.add(line.strip()[:-1])
required={'db','db-role-bootstrap','db-migrate','db-finalize','app','caddy'}
if not required.issubset(services):
    raise SystemExit(1)
for top in ('name: gomaf','networks:','volumes:'):
    if top not in lines:
        raise SystemExit(1)
PYCOMPOSE
assert_cmd "Thymeleaf dependency retired" bash -c '! grep -q "spring-boot-starter-thymeleaf" backend/pom.xml'
assert_cmd "legacy templates retired" bash -c 'test ! -d backend/src/main/resources/templates || ! find backend/src/main/resources/templates -type f -print -quit | grep -q .'
assert_cmd "legacy MVC controllers retired" python3 - <<'PYLEGACYREMOVED'
from pathlib import Path
names=['PageController','AuthController','GameController','ScheduleController','TournamentController','ProfileController','SectionAdminController','ModeratorAdminController','AdminController']
base=Path('backend/src/main/java/com/mafia/classicmafia/controller')
raise SystemExit(0 if all(not (base/f'{n}.java').exists() for n in names) else 1)
PYLEGACYREMOVED
assert_cmd "legacy form-login CAPTCHA filter retired" test ! -f backend/src/main/java/com/mafia/classicmafia/security/CaptchaLoginFilter.java
assert_cmd "SPA is unconditional after retirement" bash -c 'test -f backend/src/main/java/com/mafia/classicmafia/controller/SpaController.java && ! grep -q "ConditionalOnProperty" backend/src/main/java/com/mafia/classicmafia/controller/SpaController.java'

if grep -RInE 'createNativeQuery|JdbcTemplate|createStatement\(|prepareCall\(' backend/src/main/java \
    --exclude='DatabaseLockService.java' >/dev/null; then
  bad "direct/native SQL limited to audited advisory-lock service"
elif ! grep -qF 'connection.prepareStatement("select pg_advisory_xact_lock(?)")' backend/src/main/java/com/mafia/classicmafia/security/DatabaseLockService.java; then
  bad "direct/native SQL limited to audited advisory-lock service"
else
  ok "direct/native SQL limited to audited advisory-lock service"
fi
assert_cmd "CSP permits only self-hosted scripts" grep -q "script-src 'self'" backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java
assert_cmd "CSP has no unsafe-inline or unsafe-eval" bash -c '! grep -E "unsafe-inline|unsafe-eval" backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java >/dev/null'
assert_cmd "retired SPA toggle absent from production Compose" bash -c '! grep -q "APP_SPA_ENABLED" compose.prod.yaml'
assert_cmd "SPA fallback is explicitly allow-listed" bash -c 'grep -q "class SpaController" backend/src/main/java/com/mafia/classicmafia/controller/SpaController.java && ! grep -q "{path:\.\*}" backend/src/main/java/com/mafia/classicmafia/controller/SpaController.java'
assert_cmd "API namespace is not mapped by SPA fallback" python3 - <<'PYSPAMAP'
from pathlib import Path
s = Path('backend/src/main/java/com/mafia/classicmafia/controller/SpaController.java').read_text()
start = s.index('@GetMapping({')
end = s.index('})', start)
mapping = s[start:end]
assert '/api' not in mapping
PYSPAMAP
assert_cmd "health endpoint is independent from SPA" grep -q '@GetMapping("/healthz")' backend/src/main/java/com/mafia/classicmafia/controller/InfrastructureController.java
assert_cmd "CSRF remains enabled" bash -c '! grep -R "csrf.*disable\|disable.*csrf" backend/src/main/java/com/mafia/classicmafia/config >/dev/null'
assert_cmd "BCrypt cost 12" grep -q 'new BCryptPasswordEncoder(12)' backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java
assert_cmd "avatar re-encoding present" grep -q 'ImageIO.write' backend/src/main/java/com/mafia/classicmafia/service/AvatarService.java
assert_cmd "public player reads use scalar projections instead of media-heavy User entities" bash -c 'grep -q "findPublicPlayerList" backend/src/main/java/com/mafia/classicmafia/repository/UserRepository.java && grep -q "findPublicPlayerProfile" backend/src/main/java/com/mafia/classicmafia/repository/UserRepository.java && grep -q "PlayerListProjection" backend/src/main/java/com/mafia/classicmafia/service/PlayerReadService.java'
assert_cmd "rating reads scalar public game rows instead of entity graphs" bash -c 'grep -q "findPublicRankingRows" backend/src/main/java/com/mafia/classicmafia/repository/GamePlayerRepository.java && grep -q "RankingSourceProjection" backend/src/main/java/com/mafia/classicmafia/repository/GamePlayerRepository.java && ! grep -q "findAllDetailed" backend/src/main/java/com/mafia/classicmafia/repository/GamePlayerRepository.java'
assert_cmd "tournament dates are lazy by default and fetched explicitly for tournament views" bash -c 'grep -q "fetch = FetchType.LAZY" backend/src/main/java/com/mafia/classicmafia/entity/Tournament.java && grep -q "left join fetch t.dates" backend/src/main/java/com/mafia/classicmafia/repository/TournamentRepository.java'
assert_cmd "moderator administration lists use scalar user summaries" bash -c 'grep -q "AdminUserSummaryProjection" backend/src/main/java/com/mafia/classicmafia/api/ModeratorAdminApiController.java && grep -q "findAdminUserSummaries" backend/src/main/java/com/mafia/classicmafia/repository/UserRepository.java && grep -q "searchAdminUserSummaries" backend/src/main/java/com/mafia/classicmafia/repository/UserRepository.java'
assert_cmd "public media reads isolate avatar and banner payload columns" bash -c 'grep -q "findPublicAvatarMedia" backend/src/main/java/com/mafia/classicmafia/repository/UserRepository.java && grep -q "findPublicBannerMedia" backend/src/main/java/com/mafia/classicmafia/repository/UserRepository.java'
assert_cmd "public media supports validator caching and compact list avatars" bash -c 'grep -q "CacheControl.noCache().cachePublic()" backend/src/main/java/com/mafia/classicmafia/api/PlayersApiController.java && grep -q "checkNotModified" backend/src/main/java/com/mafia/classicmafia/api/PlayersApiController.java && grep -q "PUBLIC_LIST_SIDE = 128" backend/src/main/java/com/mafia/classicmafia/service/AvatarService.java'
assert_cmd "public profile media exposes bounded responsive variants" bash -c 'grep -q "PUBLIC_PROFILE_SIDE = 256" backend/src/main/java/com/mafia/classicmafia/service/AvatarService.java && grep -q "PUBLIC_WIDTHS = Set.of(640, 768, 960, 1280, 1920)" backend/src/main/java/com/mafia/classicmafia/service/ProfileBannerService.java && grep -q "BANNER_WIDTH_INVALID" backend/src/main/java/com/mafia/classicmafia/api/PlayersApiController.java'
assert_cmd "player list defers off-screen avatar decoding" grep -q 'loading="lazy" decoding="async"' frontend/src/players/PlayersPage.tsx
assert_cmd "fingerprinted Inter font uses immutable static cache policy" bash -c 'grep -q "/fonts/InterVariable-693b77d4.woff2" frontend/src/app/app.css && test -f frontend/public/fonts/InterVariable-693b77d4.woff2 && grep -q "addResourceHandler(\"/fonts/\*\*\")" backend/src/main/java/com/mafia/classicmafia/config/SpaWebConfig.java && grep -q "Duration.ofDays(365)).cachePublic().immutable()" backend/src/main/java/com/mafia/classicmafia/config/SpaWebConfig.java'
assert_cmd "route components are code split with React lazy" bash -c 'grep -q "lazy(() => import" frontend/src/app/App.tsx && grep -q "<Suspense" frontend/src/app/App.tsx'
assert_cmd "SEO discovery endpoints are public and sitemap uses absolute canonical origin" bash -c 'grep -q "@GetMapping(value = \"/robots.txt\"" backend/src/main/java/com/mafia/classicmafia/controller/SeoController.java && grep -q "@GetMapping(value = \"/sitemap.xml\"" backend/src/main/java/com/mafia/classicmafia/controller/SeoController.java && grep -q "Sitemap: %s/sitemap.xml" backend/src/main/java/com/mafia/classicmafia/controller/SeoController.java && grep -q '"/robots.txt"' backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java'
assert_cmd "robots permits crawlable noindex auth/editor pages" python3 - <<'PYROBOTS'
from pathlib import Path
s=Path('backend/src/main/java/com/mafia/classicmafia/controller/SeoController.java').read_text()
robots=s[s.index('User-agent: *'):s.index('Sitemap: %s/sitemap.xml')]
assert 'Disallow: /admin/' in robots and 'Disallow: /profile' in robots
for path in ('/login','/register','/games/new','/tournaments/new'):
    assert f'Disallow: {path}' not in robots
PYROBOTS
assert_cmd "player profile HTML preloads its responsive LCP banner" python3 - <<'PYPRELOAD'
from pathlib import Path
s = Path('backend/src/main/java/com/mafia/classicmafia/controller/SpaController.java').read_text()
assert 'profileBannerPreload' in s
assert 'rel=\\"preload\\"' in s
assert 'imagesrcset=\\"' in s
assert '640 640w' in s and '768 768w' in s and '960 960w' in s and '1280 1280w' in s
assert 'imagesizes=\\"(max-width: 640px) calc(100vw - 26px), (max-width: 720px) calc(100vw - 42px), (max-width: 1024px) 960px, 1280px\\"' in s
assert 'fetchpriority=\\"high\\"' in s
PYPRELOAD
assert_cmd "SPA emits route-specific canonical and noindex metadata" python3 - <<'PYSEO'
from pathlib import Path
s = Path('backend/src/main/java/com/mafia/classicmafia/controller/SpaController.java').read_text()
assert 'rel=\\"canonical\\"' in s
assert 'noindex,nofollow' in s
assert 'Игроки — GoMaf' in s
PYSEO
assert_cmd "dynamic SPA SEO is existence-aware and fail-closed for hidden games" python3 - <<'PYDYNAMICSEO'
from pathlib import Path
spa = Path('backend/src/main/java/com/mafia/classicmafia/controller/SpaController.java').read_text()
not_found = Path('backend/src/main/java/com/mafia/classicmafia/controller/NotFoundController.java').read_text()
for token in (
    'findPublicPlayerProfile',
    'findDetailProjectionById',
    'countVisibilityUser',
    'tournaments.existsById',
    'NotFoundController.response()',
    'X-Robots-Tag',
):
    assert token in spa, token
assert 'viewer.role() != Role.ADMIN' in spa
assert 'X-Robots-Tag' in not_found and 'noindex, nofollow' in not_found
PYDYNAMICSEO
assert_cmd "public internal-link crawl guards against dead navigation" grep -q 'public internal links resolve without dead destinations' frontend/e2e/quality-gates.spec.ts
assert_cmd "unknown browser URLs leave the login flow and terminate at a real 404" python3 - <<'PY404'
from pathlib import Path
security = Path('backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java').read_text()
not_found = Path('backend/src/main/java/com/mafia/classicmafia/controller/NotFoundController.java').read_text()
app = Path('frontend/src/app/App.tsx').read_text()
assert 'NotFoundController.write(response);' in security
assert 'ResponseEntity.status(404)' in not_found
assert 'noindex,nofollow' in not_found
assert "window.location.replace('/not-found')" in app
PY404
assert_cmd "production has exactly one configured ADMIN guard" grep -q 'ровно один ADMIN' backend/src/main/java/com/mafia/classicmafia/config/ProductionSecurityValidator.java

assert_cmd "historical/custom admin identity is hidden from public game surfaces" python3 - <<'PYADMINHIDE'
from pathlib import Path
gp=Path('backend/src/main/java/com/mafia/classicmafia/entity/GamePlayer.java').read_text()
gs=Path('backend/src/main/java/com/mafia/classicmafia/service/GameService.java').read_text()
required=[
    '"admin".equals(User.canonicalUsername(displayName)) ? "Скрытый игрок"',
    'if ("admin".equals(User.canonicalUsername(name)))',
    'Ник admin зарезервирован системой и не может использоваться как участник игры.',
]
raise SystemExit(0 if required[0] in gp and all(x in gs for x in required[1:]) else 1)
PYADMINHIDE
assert_cmd "ADMIN identity remains excluded from public players API" grep -q "wp11006_hidden_admin" backend/src/test/java/com/mafia/classicmafia/PlayersReadApiIntegrationTests.java
assert_cmd "no browser ADMIN password-change controller remains" bash -c '! grep -R "changeOwnPassword" backend/src/main/java >/dev/null'
assert_cmd "reserved ADMIN username enforced in registration" grep -q '"admin".equals(User.canonicalUsername(normalizedUsername))' backend/src/main/java/com/mafia/classicmafia/service/RegistrationService.java
assert_cmd "local ADMIN username default is canonical admin" grep -q 'app.admin.username=${APP_ADMIN_USERNAME:admin}' backend/src/main/resources/application.properties
assert_cmd "session identity uses immutable user id through scalar security projection" grep -q 'users.findSecurityAccountById(principal.userId())' backend/src/main/java/com/mafia/classicmafia/security/CredentialVersionFilter.java

assert_cmd "authenticated principal erases password hash from session credentials" bash -c 'grep -q "implements UserDetails, CredentialsContainer" backend/src/main/java/com/mafia/classicmafia/security/MafiaUserPrincipal.java && grep -q "eraseCredentials()" backend/src/main/java/com/mafia/classicmafia/security/MafiaUserPrincipal.java'
assert_cmd "session invalidation checks authorization version" grep -q 'authorizationVersion() == principal.authorizationVersion()' backend/src/main/java/com/mafia/classicmafia/security/CredentialVersionFilter.java

assert_cmd "moderator player-management scopes cannot target self or other moderators" python3 - <<'PYMODTARGET'
from pathlib import Path
s = Path('backend/src/main/java/com/mafia/classicmafia/service/PlayerAdministrationService.java').read_text()
required = [
    'operator.getId().equals(target.getId())',
    'target.getRole() != Role.PLAYER',
    'operator.getRole() != Role.MODERATOR',
]
raise SystemExit(0 if all(item in s for item in required) else 1)
PYMODTARGET
assert_cmd "moderator cannot target moderator/self through API" grep -q "moderatorScopeCanManageOrdinaryPlayerButNeverSelfOrAnotherModerator" backend/src/test/java/com/mafia/classicmafia/PlayerAdministrationApiIntegrationTests.java
assert_cmd "403 endpoint is template-independent" bash -c 'grep -q "ResponseEntity<Map<String, String>> forbidden" backend/src/main/java/com/mafia/classicmafia/controller/InfrastructureController.java'
assert_cmd "authorization failures are audit logged" grep -q 'auditLogService.denied' backend/src/main/java/com/mafia/classicmafia/security/AuditingAccessDeniedHandler.java
assert_cmd "audit logger has dedicated file" grep -q '${LOG_DIR}/audit.log' backend/src/main/resources/logback-spring.xml
assert_cmd "application logger has dedicated file" grep -q '${LOG_DIR}/application.log' backend/src/main/resources/logback-spring.xml
assert_cmd "moderator defaults are deny by migration" python3 - <<'PYMOD'
from pathlib import Path
import re
text=Path('backend/src/main/resources/db/migration/V3__roles_moderator_scopes.sql').read_text()
block=re.search(r'INSERT INTO moderator_scope_defaults\(scope, enabled\) VALUES(.*?)ON CONFLICT', text, re.S)
if not block:
    raise SystemExit(1)
rows=re.findall(r"\('([A-Z_]+)',\s*(TRUE|FALSE)\)", block.group(1))
raise SystemExit(0 if len(rows)==9 and all(v=='FALSE' for _,v in rows) and len({k for k,_ in rows})==9 else 1)
PYMOD
assert_cmd "V3 enforces one ADMIN and rollback-compatible legacy role" bash -c 'grep -q "ux_users_single_admin" backend/src/main/resources/db/migration/V3__roles_moderator_scopes.sql && grep -q "chk_users_reserved_admin_identity" backend/src/main/resources/db/migration/V3__roles_moderator_scopes.sql && grep -q "chk_users_role_rollback_compatibility" backend/src/main/resources/db/migration/V3__roles_moderator_scopes.sql'
assert_cmd "V3 username canonicalization is locale-independent for allowed Latin/Cyrillic alphabet" python3 - <<'PYUSERCANON'
from pathlib import Path
s = Path('backend/src/main/resources/db/migration/V3__roles_moderator_scopes.sql').read_text()
upper = 'ABCDEFGHIJKLMNOPQRSTUVWXYZАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ'
lower = 'abcdefghijklmnopqrstuvwxyzабвгдеёжзийклмнопрстуфхцчшщъыьэюя'
expr = f"translate(btrim(username), '{upper}', '{lower}')"
required = [
    f'SET username_key = {expr}',
    f'CHECK (username_key = {expr})',
    f"{expr} <> 'admin'",
]
raise SystemExit(0 if all(item in s for item in required) and 'lower(btrim(username))' not in s else 1)
PYUSERCANON
assert_cmd "production PostgreSQL enables measured I/O timing observability" python3 - <<'PYPGIOTIMING'
from pathlib import Path
compose = Path('compose.prod.yaml').read_text()
audit = Path('ops/production-audit.sh').read_text()
assert 'track_io_timing=on' in compose
assert 'PostgreSQL track_io_timing enabled' in audit
PYPGIOTIMING
assert_cmd "deploy uses immutable release snapshots" grep -q 'release snapshot' ops/deploy.sh
assert_cmd "release and candidate packaging exclude internal audit evidence" python3 - <<'PYAUDITEXCLUDE'
from pathlib import Path
checks={
    'ops/deploy.sh': ["--exclude '.audit/'"],
    'ops/package-release.sh': ["--exclude='./.audit'", "'.audit'"],
    'ops/release/package-candidate.sh': ["--exclude='./.audit'"],
    '.dockerignore': ['.audit'],
}
for path,tokens in checks.items():
    text=Path(path).read_text()
    for token in tokens:
        assert token in text, (path,token)
PYAUDITEXCLUDE
assert_cmd "all source transfer paths exclude internal audit evidence" python3 - <<'PYTRANSFERAUDIT'
from pathlib import Path
checks={
    'ops/setup-server.sh': ["--exclude '.audit/'"],
    'ops/push-and-deploy.sh': ["--exclude '.audit/'"],
    'ops/root/gomaf-deploy-release': ["--exclude '.deploy/' --exclude '.audit/'"],
}
for path,tokens in checks.items():
    text=Path(path).read_text()
    for token in tokens:
        assert token in text, (path,token)
PYTRANSFERAUDIT
assert_cmd "deploy source hash uses canonical staging-snapshot identity" python3 - <<'PYSOURCEHASH'
from pathlib import Path
deploy=Path('ops/deploy.sh').read_text()
helper=Path('ops/source-hash.sh').read_text()
required_deploy=[
    'bash "$STAGING/ops/source-hash.sh" "$STAGING"',
    '[[ "$SOURCE_HASH" =~ ^[0-9a-f]{12}$ ]]',
]
required_helper=[
    'required_dirs=(backend frontend ops config client caddy docker)',
    'required_files=(Dockerfile .dockerignore compose.prod.yaml .wp11-023-security-tools.env)',
    'LC_ALL=C sort -z',
    "awk '{print substr($1,1,12)}'",
]
raise SystemExit(0 if all(x in deploy for x in required_deploy) and all(x in helper for x in required_helper) else 1)
PYSOURCEHASH
assert_cmd "release source hash is path-independent and binds deployment-critical bytes" bash -c '
set -euo pipefail
tmp="$(mktemp -d)"
trap "rm -rf -- "$tmp"" EXIT
for root in a b; do
  base="$tmp/$root"
  mkdir -p "$base"/{backend,frontend,ops,config,client,caddy,docker}
  for d in backend frontend config client caddy docker; do
    printf "probe-%s
" "$d" > "$base/$d/probe.txt"
  done
  cp ops/source-hash.sh "$base/ops/source-hash.sh"
  printf "ops-probe
" > "$base/ops/probe.txt"
  printf "Dockerfile
" > "$base/Dockerfile"
  printf "dockerignore
" > "$base/.dockerignore"
  printf "compose
" > "$base/compose.prod.yaml"
  printf "lock
" > "$base/.wp11-023-security-tools.env"
done
h1="$(bash ops/source-hash.sh "$tmp/a")"
h2="$(bash ops/source-hash.sh "$tmp/b")"
[[ "$h1" == "$h2" ]]
printf "changed
" >> "$tmp/b/ops/probe.txt"
h3="$(bash ops/source-hash.sh "$tmp/b")"
[[ "$h3" != "$h1" ]]
printf "documentation-only
" > "$tmp/a/README.md"
h4="$(bash ops/source-hash.sh "$tmp/a")"
[[ "$h4" == "$h1" ]]
'
assert_cmd "deploy makes immutable release snapshot filesystem read-only" python3 - <<'PYIMMUTABLE'
from pathlib import Path
deploy = Path('ops/deploy.sh').read_text()
audit = Path('ops/production-audit.sh').read_text()
raise SystemExit(0 if 'chmod -R a-w "$RELEASE"' in deploy and 'active release snapshot is filesystem read-only' in audit else 1)
PYIMMUTABLE
assert_cmd "read-only release lifecycle thaws only immediately before deletion" python3 - <<'PYRELEASEDELETE'
from pathlib import Path
deploy = Path('ops/deploy.sh').read_text()
cleanup = Path('ops/cleanup-releases.sh').read_text()
checks = [
    'chmod -R u+w -- "$STAGING"' in deploy,
    'rm -rf -- "$STAGING"' in deploy,
    'chmod -R u+w -- "$dir"' in cleanup,
    'rm -rf -- "$dir"' in cleanup,
]
raise SystemExit(0 if all(checks) else 1)
PYRELEASEDELETE
assert_cmd "deploy executes audited helpers from immutable release snapshot" python3 - <<'PYIMMUTABLEHELPERS'
from pathlib import Path
s = Path('ops/deploy.sh').read_text()
required = [
    'GOMAF_RUNTIME_DIR="$RUNTIME_DIR" "$RELEASE/ops/migration-rehearsal.sh"',
    'GOMAF_RUNTIME_DIR="$RUNTIME_DIR" "$RELEASE/ops/backup.sh" predeploy',
    'GOMAF_RUNTIME_DIR="$RUNTIME_DIR" "$RELEASE/ops/cleanup-releases.sh"',
]
forbidden = [
    '"$PROJECT_ROOT/ops/migration-rehearsal.sh"',
    '"$PROJECT_ROOT/ops/backup.sh" predeploy',
    '"$PROJECT_ROOT/ops/cleanup-releases.sh"',
]
lib = Path('ops/lib.sh').read_text()
ok = all(item in s for item in required) and not any(item in s for item in forbidden)
ok = ok and 'RUNTIME_DIR="${GOMAF_RUNTIME_DIR:-$PROJECT_ROOT/.deploy}"' in lib
raise SystemExit(0 if ok else 1)
PYIMMUTABLEHELPERS
assert_cmd "deploy backup precedes candidate DB migration" grep -q 'Pre-deploy backup:' ops/deploy.sh
assert_cmd "deploy rehearses migration on isolated live-data clone before maintenance" bash -c 'grep -q "migration-rehearsal.sh" ops/deploy.sh && grep -q "изолированном clone" ops/deploy.sh && grep -q -- "--internal" ops/migration-rehearsal.sh && grep -q "MIGRATION REHEARSAL PASS" ops/migration-rehearsal.sh'
assert_cmd "deploy supports frozen-offline DB compatibility preflight without starting production" python3 - <<'PYOFFLINEPREFLIGHT'
from pathlib import Path
d=Path('ops/deploy.sh').read_text()
r=Path('ops/migration-rehearsal.sh').read_text()
required_deploy=[
    'OLD_DB_RUNNING=0',
    'grep -qx db; then OLD_DB_RUNNING=1; fi',
    'if [[ "$OLD_DB_RUNNING" -eq 1 ]]; then',
    'Production DB offline: HTTPS video compatibility',
    'production DB не запускается.',
]
required_rehearsal=[
    'HTTP_VIDEO_COUNT=',
    'game_videos',
    'HTTP/non-HTTPS video URL',
    'freeze-safe root-owned preaudit dump selected after production DB stop',
]
raise SystemExit(0 if all(x in d for x in required_deploy) and all(x in r for x in required_rehearsal) else 1)
PYOFFLINEPREFLIGHT
assert_cmd "PREDEPLOY exact production image is cryptographically bound through deployment" python3 - <<'PYEXACTIMAGE'
from pathlib import Path
pre=Path('ops/wp11-023-predeploy.sh').read_text()
pre_root=Path('ops/root/gomaf-predeploy-release').read_text()
deploy_root=Path('ops/root/gomaf-deploy-release').read_text()
deploy=Path('ops/deploy.sh').read_text()

for token in (
    'CANDIDATE_SOURCE_HASH=$SOURCE_HASH',
    'CANDIDATE_IMAGE=$IMAGE',
    'CANDIDATE_IMAGE_ID=$IMAGE_ID',
    'KEEP_IMAGE=1',
    'com.gomaf.source-hash=$SOURCE_HASH',
    'com.gomaf.application=classic-mafia',
):
    assert token in pre, token

for token in (
    'candidate_source_hash=',
    'candidate_image_id=',
    'computed_source_hash=',
    'predeploy exact candidate image missing or image id changed',
):
    assert token in pre_root, token

for token in (
    'GOMAF_APPROVED_IMAGE="$candidate_image"',
    'GOMAF_APPROVED_IMAGE_ID="$candidate_image_id"',
    'GOMAF_APPROVED_SOURCE_HASH="$candidate_source_hash"',
    'PREDEPLOY exact candidate image missing or image id changed',
):
    assert token in deploy_root, token

for token in (
    'APPROVED_IMAGE="$(printenv GOMAF_APPROVED_IMAGE',
    'APPROVED_IMAGE_ID="$(printenv GOMAF_APPROVED_IMAGE_ID',
    'APPROVED_SOURCE_HASH="$(printenv GOMAF_APPROVED_SOURCE_HASH',
    'docker image tag "$APPROVED_IMAGE_ID" "$NEW_IMAGE"',
    'Production tag does not point to exact PREDEPLOY image id.',
):
    assert token in deploy, token

assert deploy.count('docker build --pull') == 0
assert 'gomaf-safe-build' in deploy
assert 'gomaf-deploy-backend-build-$RELEASE_ID' in deploy
assert '--label "com.gomaf.application=classic-mafia"' not in deploy
PYEXACTIMAGE
assert_cmd "deploy supplies mandatory backend test image to PostgreSQL migration rehearsal" python3 - <<'PYDEPLOYTESTIMAGE'
from pathlib import Path
s=Path('ops/deploy.sh').read_text()
required=[
    'DEPLOY_TEST_IMAGE="gomaf/deploy-backend-test:${RELEASE_ID}"',
    '"$RELEASE/ops/root/gomaf-safe-build" "gomaf-deploy-backend-build-$RELEASE_ID" 1024 0.75',
    '--pull --target backend-build -t "$DEPLOY_TEST_IMAGE" "$RELEASE"',
    'migration-rehearsal.sh" "$NEW_IMAGE" "$RELEASE" "" "$DEPLOY_TEST_IMAGE"',
    'docker image rm "$DEPLOY_TEST_IMAGE" >/dev/null 2>&1 || true',
]
raise SystemExit(0 if all(x in s for x in required) else 1)
PYDEPLOYTESTIMAGE
assert_cmd "SBOM and vulnerability scanners use bounded named test containers" python3 - <<'PYBOUNDEDSCANNERS'
from pathlib import Path
import re
sbom=Path('ops/generate-sbom.sh').read_text()
vuln=Path('ops/online-vulnerability-scan.sh').read_text()
for text in (sbom,vuln):
    assert 'gomaf-safe-test-run' in text
    assert text.count('GOMAXPROCS=1') >= 2
    assert not re.search(r'(?m)^\s*docker\s+run', text)
for token in (
    'gomaf-predeploy-syft-source-',
    'gomaf-predeploy-syft-image-',
    '"$SOURCE_C" 512 0.50',
    '"$IMAGE_C" 512 0.50',
    'docker rm -f "$SOURCE_C" "$IMAGE_C"',
):
    assert token in sbom, token
for token in (
    'gomaf-predeploy-trivy-source-',
    'gomaf-predeploy-trivy-image-',
    '"$SOURCE_C" 768 0.50',
    '"$IMAGE_C" 768 0.50',
    'docker rm -f "$SOURCE_C" "$IMAGE_C"',
):
    assert token in vuln, token
PYBOUNDEDSCANNERS
assert_cmd "heavy validation waits for green resource gate between phases" python3 - <<'PYINTERPHASE'
from pathlib import Path
pre=Path('ops/wp11-023-predeploy.sh').read_text()
reh=Path('ops/migration-rehearsal.sh').read_text()
preflight=Path('ops/root/gomaf-test-preflight').read_text()
assert '--resources-only' in preflight
assert 'mode="' + '$' + '{1:-full}"' in preflight
assert pre.count('wait_resources ') >= 7
for label in ('backend test image build','exact production image build','SBOM scan','vulnerability scan','migration rehearsal','browser E2E backend','browser E2E runner'):
    assert label in pre
assert reh.count('wait_resources ') >= 5
for label in ('PostgreSQL concurrency gate','production snapshot copy','Flyway candidate migration','candidate runtime','rollback runtime'):
    assert label in reh
assert '--resources-only' in pre and '--resources-only' in reh
PYINTERPHASE
assert_cmd "PREDEPLOY and deploy heavy Docker work is bounded" python3 - <<'PYBOUNDEDROLL'
from pathlib import Path
import re
pre=Path('ops/wp11-023-predeploy.sh').read_text()
deploy=Path('ops/deploy.sh').read_text()
for token in (
    '"$SAFE_BUILD" "gomaf-predeploy-backend-build-$TOKEN" 1024 0.75',
    '"$SAFE_BUILD" "gomaf-predeploy-app-build-$TOKEN" 1024 0.75',
    '"$SAFE_RUN" "$E2E_BACKEND" 512 0.50',
    '"$SAFE_RUN" "$BROWSER_CONTAINER" 768 0.75',
    '--shm-size=384m',
):
    assert token in pre, token
assert 'workers: 1' in Path('frontend/playwright.config.ts').read_text()
assert not re.search(r'(?m)^\s*docker\s+(?:build|run)', pre)
for token in (
    'gomaf-deploy-caddy-validate-$SOURCE_HASH',
    'gomaf-deploy-backend-build-$RELEASE_ID',
    'ops/root/gomaf-safe-build',
    'ops/root/gomaf-safe-test-run',
):
    assert token in deploy, token
assert not re.search(r'(?m)^\s*docker\s+(?:build|run)', deploy)
PYBOUNDEDROLL
assert_cmd "migration rehearsal bounds every temporary Docker runtime" python3 - <<'PYBOUNDEDREHEARSAL'
from pathlib import Path
import re
s=Path('ops/migration-rehearsal.sh').read_text()
for token in (
    'bounded_small()',
    'bounded_db()',
    'bounded_app()',
    'bounded_maven()',
    '--memory=128m',
    '--memory=512m',
    '--memory=768m',
    '--memory-swap=1024m',
    '--cpus=0.75',
    'TEST_CGROUP_OPT',
    'CONCURRENCY_CONTAINER="gomaf-rehearsal-concurrency-$TOKEN"',
):
    assert token in s, token
raw=[line.strip() for line in s.splitlines() if line.lstrip().startswith('docker run ')]
assert len(raw)==4, raw
assert all('--memory=' in line and '--cpus=' in line and '--pids-limit=' in line for line in raw), raw
PYBOUNDEDREHEARSAL
assert_cmd "migration rehearsal executes production security validation with ephemeral secrets" bash -c 'grep -q "APP_ENV=production" ops/migration-rehearsal.sh && grep -q "APP_ADMIN_USERNAME=admin" ops/migration-rehearsal.sh && grep -q "ADMIN_PASSWORD=\"\$(openssl rand" ops/migration-rehearsal.sh && grep -q "SESSION_COOKIE_SECURE=true" ops/migration-rehearsal.sh && ! grep -q "env_get APP_ADMIN_PASSWORD" ops/migration-rehearsal.sh'
assert_cmd "migration rehearsal enforces PostgreSQL participation concurrency regression" bash -c 'grep -q "PostgresParticipationConcurrencyIntegrationTests" backend/src/test/java/com/mafia/classicmafia/PostgresParticipationConcurrencyIntegrationTests.java && grep -q "gomaf.postgres.concurrency" backend/src/test/java/com/mafia/classicmafia/PostgresParticipationConcurrencyIntegrationTests.java && grep -q "CONCURRENCY_DB=\"gomaf_concurrency\"" ops/migration-rehearsal.sh && grep -q "PostgreSQL concurrency gate PASS" ops/migration-rehearsal.sh && grep -q "migration-rehearsal.sh \"\$IMAGE\" \"\$ROOT\" \"\" \"\$TEST_IMAGE\"" ops/wp11-023-predeploy.sh'
assert_cmd "migration rehearsal supports verified freeze-safe dump fallback" python3 - <<'PYFROZENDUMP'
from pathlib import Path
s=Path('ops/migration-rehearsal.sh').read_text()
required=[
    'select_verified_frozen_dump() {',
    "-name 'gomaf-preaudit-*.dump'",
    'frozen dump fallback разрешён только когда все production services остановлены',
    'dump_epoch >= finished_epoch',
    'root:root && "$mode" == 600',
    'DUMP_SHA256="$(sha256sum "$DUMP_PATH"',
    'dump_sha256=$DUMP_SHA256',
    'freeze-safe root-owned preaudit dump selected after production DB stop',
]
raise SystemExit(0 if all(x in s for x in required) else 1)
PYFROZENDUMP
assert_cmd "pre-public DB rollback uses backup restore" grep -q 'restore_database_from_dump_at' ops/deploy.sh
assert_cmd "post-migration rollback has Flyway forward-compat mode" bash -c 'grep -q "enable_forward_compat_mode" ops/rollback.sh && grep -q "SPRING_FLYWAY_ENABLED: \"false\"" ops/lib.sh'
assert_cmd "post-public legacy rollback finalizes canonical ADMIN transition" python3 - <<'PYPOSTPUBLICADMIN'
from pathlib import Path
s = Path('ops/deploy.sh').read_text()
start = s.index('rollback_after_public()')
end = s.index('if [[ "$DB_VOLUME_EXISTS"', start)
block = s[start:end]
required = [
    'enable_forward_compat_mode "$OLD_RELEASE"',
    'if restart_old_runtime; then',
    'gomaf-v10-admin-switch finalize',
    'ADMIN_CONFIG_SWITCHED=0',
]
raise SystemExit(0 if all(item in block for item in required) else 1)
PYPOSTPUBLICADMIN
assert_cmd "deploy persists previous source hash for rollback" bash -c 'grep -q "PREVIOUS_SOURCE_HASH" ops/deploy.sh && grep -q "previous_source_hash" ops/deploy.sh'
assert_cmd "rollback swaps source-hash metadata with image/release" bash -c 'grep -q "PREVIOUS_SOURCE_HASH" ops/rollback.sh && grep -q "CURRENT_SOURCE_HASH" ops/rollback.sh && grep -q "current_source_hash" ops/rollback.sh && grep -q "previous_source_hash" ops/rollback.sh'
assert_cmd "deploy rollback and restore share an exclusive production-operation lock" python3 - <<'PYOPLOCK'
from pathlib import Path
for path in ['ops/deploy.sh', 'ops/rollback.sh', 'ops/restore.sh']:
    s = Path(path).read_text()
    if 'exec 9>"$RUNTIME_DIR/deploy.lock"' not in s or 'flock -n 9' not in s:
        raise SystemExit(1)
raise SystemExit(0)
PYOPLOCK
assert_cmd "v10 host upgrade preflights live v9 ADMIN before maintenance" bash -c 'grep -q "label=com.docker.compose.service=db" ops/upgrade-server-v10.sh && grep -q "username admin уже принадлежит не-ADMIN" ops/upgrade-server-v10.sh && grep -q "APP_ADMIN_USERNAME не соответствует фактическому" ops/upgrade-server-v10.sh && grep -q "Flyway V3 уже применена" ops/upgrade-server-v10.sh'
assert_cmd "v10 host upgrade preserves live v9 ADMIN config and records rollback identity" bash -c 'grep -q "Keep the v9 ADMIN configuration unchanged" ops/upgrade-server-v10.sh && grep -q "v10-v9-admin-username" ops/upgrade-server-v10.sh && ! grep -q "APP_ADMIN_USERNAME'"'"':'"'"'admin" ops/upgrade-server-v10.sh'
assert_cmd "v10 host upgrade writes transition/env state atomically" python3 - <<'PYV10ATOMIC'
from pathlib import Path
s = Path('ops/upgrade-server-v10.sh').read_text()
required = [
    'mktemp /etc/gomaf/.v10-v9-admin-username',
    "tempfile.mkstemp(prefix='.production.env.'",
    'os.replace(tmp, p)',
    '[[ -f "$ENV" && ! -L "$ENV" ]]',
    'mv -T "$STATE_TMP" "$STATE"',
]
raise SystemExit(0 if all(item in s for item in required) else 1)
PYV10ATOMIC
assert_cmd "v10 host upgrade creates ADMIN transition state only for a non-canonical v9 ADMIN" python3 - <<'PYV10STATE'
from pathlib import Path
s = Path('ops/upgrade-server-v10.sh').read_text()
guard = 'if [[ "$CURRENT_ADMIN" != "admin" ]]; then'
state_write = 'mv -T "$STATE_TMP" "$STATE"'
canonical_message = 'v9 уже использует канонический username admin'
if guard not in s or state_write not in s or canonical_message not in s:
    raise SystemExit(1)
guard_pos = s.index(guard)
state_pos = s.index(state_write)
else_pos = s.find('\nelse\n', guard_pos)
fi_pos = s.find('\nfi\n', guard_pos)
raise SystemExit(0 if guard_pos < state_pos < else_pos < fi_pos else 1)
PYV10STATE
assert_cmd "deploy rejects unsafe or stale ADMIN transition state before validation" python3 - <<'PYV10DEPLOYSTATE'
from pathlib import Path
s = Path('ops/deploy.sh').read_text()
required = [
    '[[ -f "$ADMIN_TRANSITION_STATE" && ! -L "$ADMIN_TRANSITION_STATE" && -r "$ADMIN_TRANSITION_STATE" ]]',
    'Небезопасный ADMIN transition state',
    '[[ "$(env_get APP_ADMIN_USERNAME)" != "admin" ]]',
    'ADMIN transition state существует, но APP_ADMIN_USERNAME уже admin',
    'автоматический deploy остановлен fail-closed',
]
# These checks must happen before production config validation/runtime work.
state_pos = s.find('ADMIN_TRANSITION_STATE=/etc/gomaf/v10-v9-admin-username')
validate_pos = s.find('validate_prod_config "$LEGACY_ADMIN_UPGRADE"')
if state_pos < 0 or validate_pos < 0 or state_pos >= validate_pos:
    raise SystemExit(1)
for item in required:
    pos = s.find(item, state_pos, validate_pos)
    if pos < 0:
        raise SystemExit(1)
raise SystemExit(0)
PYV10DEPLOYSTATE
assert_cmd "v10 ADMIN transition helper is atomic and finalizable" bash -c 'grep -q "os.replace(tmp_name, env)" ops/root/gomaf-v10-admin-switch && grep -q "os.fsync" ops/root/gomaf-v10-admin-switch && grep -q "v10|v9|finalize" ops/root/gomaf-v10-admin-switch && grep -q "refusing finalize: APP_ADMIN_USERNAME is not admin" ops/root/gomaf-v10-admin-switch'
assert_cmd "deployment sudo is restricted to audited no-argument helpers" bash -c 'grep -qF "mafia ALL=(root) NOPASSWD: /usr/local/sbin/gomaf-predeploy-release \"\"" ops/install-wp11-023-host-security.sh && grep -qF "mafia ALL=(root) NOPASSWD: /usr/local/sbin/gomaf-deploy-release \"\"" ops/install-wp11-023-host-security.sh && ! grep -R "mafia .*NOPASSWD: ALL" ops --exclude=source-audit.sh >/dev/null'
assert_cmd "deploy switches ADMIN only after v9 traffic stop and restores it on pre-public failure" python3 - <<'PYADMINFLOW'
from pathlib import Path
s=Path('ops/deploy.sh').read_text()
need=[
    'compose_at "$OLD_RELEASE" stop caddy app',
    'gomaf-v10-admin-switch v10',
    'GOMAF_RUNTIME_DIR="$RUNTIME_DIR" "$RELEASE/ops/backup.sh" predeploy',
    'DB_MAY_HAVE_CHANGED=1',
    'PUBLIC_CANDIDATE_STARTED=1',
]
pos=[s.find(x) for x in need]
if not all(i >= 0 for i in pos) or pos != sorted(pos):
    raise SystemExit(1)
pre=s[s.index('rollback_pre_public()'):s.index('rollback_after_public()')]
if 'restore_v9_admin_config' not in pre or 'restart_old_runtime' not in pre:
    raise SystemExit(1)
if '[[ -s "$PREDEPLOY_BACKUP" ]] || { restore_v9_admin_config' not in s:
    raise SystemExit(1)
commit=s[s.index('# Commit runtime pointers'):]
if commit.find('release_committed=1') < 0 or commit.find('gomaf-v10-admin-switch finalize') < commit.find('release_committed=1'):
    raise SystemExit(1)
PYADMINFLOW
assert_cmd "failed deploy restores original production DB running state" python3 - <<'PYDBFREEZERESTORE'
from pathlib import Path
s=Path('ops/deploy.sh').read_text()
assert 'OLD_DB_RUNNING=0' in s
assert 'grep -qx db; then OLD_DB_RUNNING=1' in s
start=s.index('restart_old_runtime()')
end=s.index('rollback_pre_public()', start)
block=s[start:end]
assert '"$OLD_DB_RUNNING" -eq 1' in block
assert 'if [[ "$OLD_DB_RUNNING" -ne 1 ]]' in block
assert 'compose_at "$OLD_RELEASE" stop db' in block
rollback=s[s.index('rollback_pre_public()'):s.index('rollback_after_public()')]
assert 'restart_old_runtime' in rollback
PYDBFREEZERESTORE
assert_cmd "v10 host upgrade restores production env on setup failure" grep -q 'restore_env_on_failure' ops/upgrade-server-v10.sh
assert_cmd "fresh setup prepares WP11-023 base host without fabricating candidate-bound PREDEPLOY evidence" python3 - <<'PYFRESHBASE'
from pathlib import Path
s=Path('ops/setup-server.sh').read_text()
assert 'setup-server.sh <opsadmin.pub> <mafia-deploy.pub>' in s
assert 'Release signing is not used' in s
assert 'install-wp11-023-host-security.sh' not in s
assert 'install-v10-host-support.sh' not in s
assert 'upgrade-server-v10.sh' not in s
PYFRESHBASE
assert_cmd "v9 upgrade reuses common v10 host support after transition preflight" grep -q 'install-v10-host-support.sh' ops/upgrade-server-v10.sh
assert_cmd "v9 upgrade does not duplicate common host-support configuration" python3 - <<'PYV10COMMON'
from pathlib import Path
s = Path('ops/upgrade-server-v10.sh').read_text()
for duplicate in (
    '/etc/sudoers.d/90-gomaf-mafia',
    '/etc/systemd/system/gomaf-storage-maintenance.service',
    '/etc/systemd/system/gomaf-storage-maintenance.timer',
    'install-backup-timer.sh',
):
    if duplicate in s:
        raise SystemExit(1)
raise SystemExit(0 if 'install-v10-host-support.sh' in s else 1)
PYV10COMMON
assert_cmd "WP11-023 host support pre-creates protected runtime and log paths" bash -c 'grep -q "/srv/gomaf/.deploy" ops/install-wp11-023-host-security.sh && grep -q "/srv/gomaf-logs/app" ops/install-wp11-023-host-security.sh && grep -q "/srv/gomaf-logs/caddy" ops/install-wp11-023-host-security.sh' 
assert_cmd "fresh setup provisions AIDE and auditd prerequisites" bash -c 'grep -q "auditd aide" ops/setup-server.sh'
assert_cmd "AIDE monitors stable host/runtime state rather than mutable candidate ops tree" python3 - <<'PYAIDESCOPE'
from pathlib import Path
s=Path('ops/host/gomaf-aide.conf').read_text()
assert '/srv/gomaf/ops GOMAF' not in s
for token in (
    '/srv/gomaf/.deploy/active_release$ GOMAF',
    '/srv/gomaf/.deploy/current_image$ GOMAF',
    '/srv/gomaf/.deploy/current_source_hash$ GOMAF',
    '/etc/systemd/system GOMAF',
    '/usr/local/sbin/gomaf-.* GOMAF',
):
    assert token in s
PYAIDESCOPE
assert_cmd "candidate-bound host security installs source-managed ops safety" bash -c 'grep -q "install-ops-safety.sh" ops/install-wp11-023-host-security.sh'
assert_cmd "ops safety installer manages recurring visibility and bounded validation" python3 - <<'PYOPSSAFETY'
from pathlib import Path
s=Path('ops/install-ops-safety.sh').read_text()
required=[
    'gomaf-storage-maintenance.timer',
    'gomaf-aide-check.timer',
    'gomaf-security-check.timer',
    'gomaf-ops-health-check.timer',
    'gomaf-test-memory-guard.service',
    'gomaf-testing.slice',
    'OnFailure=gomaf-ops-alert@%n.service',
    '50-gomaf-audit.rules',
    '99-gomaf-hardening.local',
    'gomaf-aide.conf',
    'if [[ ! -s /var/lib/aide/gomaf-aide.db ]]',
    'TimeoutStartSec=60s',
]
raise SystemExit(0 if all(x in s for x in required) else 1)
PYOPSSAFETY
assert_cmd "memory guard covers stage predeploy rehearsal deploy and buildx workloads" python3 - <<'PYGUARDCOVERAGE'
from pathlib import Path
s=Path('ops/root/gomaf-test-memory-guard').read_text()
for token in ('gomaf-(stage[0-9]+|predeploy|rehearsal|deploy)-','buildx_buildkit_gomaf-(stage[0-9]+|predeploy|deploy)-'):
    assert token in s
PYGUARDCOVERAGE
assert_cmd "memory guard bounds Docker control-plane operations" python3 - <<'PYGUARDTIMEOUT'
from pathlib import Path
s=Path('ops/root/gomaf-test-memory-guard').read_text()
for token in ('DOCKER_QUERY_TIMEOUT=3','DOCKER_STOP_TIMEOUT=12','/usr/bin/timeout "$DOCKER_QUERY_TIMEOUT" /usr/bin/docker ps','/usr/bin/timeout "$DOCKER_STOP_TIMEOUT" /usr/bin/docker stop'):
    assert token in s
PYGUARDTIMEOUT
assert_cmd "safe test runner rejects resource-limit and host-namespace overrides" python3 - <<'PYSAFERUN'
from pathlib import Path
s=Path('ops/root/gomaf-safe-test-run').read_text()
for token in ('--memory=*','--cpus=*','--pids-limit=*','--cgroup-parent=*','--restart=*','--name=*','--privileged','--pid=host','--ipc=host','--uts=host'):
    assert token in s
for token in ('stage[0-9]+|predeploy|rehearsal|deploy','gomaf-testing.slice','run_preflight','/usr/local/sbin/gomaf-test-preflight >&2','"$here/gomaf-test-preflight" >&2'):
    assert token in s
PYSAFERUN
assert_cmd "safe build wrapper bounds BuildKit resources and cancels on host pressure" python3 - <<'PYSAFEBUILD'
from pathlib import Path
s=Path('ops/root/gomaf-safe-build').read_text()
for token in (
    '--resource "memory=${memory_mib}m"',
    '--resource "cpu-quota=$quota"',
    '--resource cpu-period=100000',
    'min_available_kb=1048576',
    'max_swap_used_kb=524288',
    'low_count >= 3',
    'cancel_build',
    'max_seconds=1800',
    'run_preflight',
):
    assert token in s
for forbidden in ('--builder=*','--resource=*','--load','--output=*','--push'):
    assert forbidden in s
PYSAFEBUILD
assert_cmd "security and production audits expose pending reboot fail-closed" python3 - <<'PYREBOOTGATE'
from pathlib import Path
security=Path('ops/root/gomaf-security-check').read_text()
prod=Path('ops/production-audit.sh').read_text()
assert '/var/run/reboot-required' in security and 'exit 21' in security
assert '/var/run/reboot-required' in prod and 'FAIL host reboot required before production acceptance' in prod
PYREBOOTGATE
assert_cmd "recurring security check audits immutable active production release" python3 - <<'PYSECCHECKACTIVE'
from pathlib import Path
s=Path('ops/root/gomaf-security-check').read_text()
for token in (
    '/srv/gomaf/.deploy/active_release',
    '/srv/gomaf/.deploy/releases/*',
    'GOMAF_RUNTIME_DIR=/srv/gomaf/.deploy',
    'GOMAF_ENV_FILE=/etc/gomaf/production.env',
    '"$ACTIVE_RELEASE/ops/production-audit.sh"',
):
    assert token in s
assert '/srv/gomaf/ops/production-audit.sh' not in s
PYSECCHECKACTIVE
assert_cmd "ops health checker validates jobs backup host floors and bounded tests" python3 - <<'PYOPSHEALTH'
from pathlib import Path
s=Path('ops/root/gomaf-ops-health-check').read_text()
for token in ('gomaf-backup.timer','gomaf-aide-check.timer','gomaf-security-check.timer','backup-checksum-failed','host-reboot-required','host-low-memory','unbounded-test-memory','buildx_buildkit_gomaf-(stage[0-9]+|predeploy|deploy)-','DOCKER_TIMEOUT'):
    assert token in s
PYOPSHEALTH
assert_cmd "backup custom archive is validated as a regular container file before host copy" python3 - <<'PYBACKUPFILEFLOW'
from pathlib import Path
s=Path('ops/backup.sh').read_text()
required=[
    'pg_dump \\',
    '-Fc --no-owner --no-acl -f "$1"',
    'pg_restore --list "$1" >/dev/null',
    'CONTAINER_TMP="/var/lib/postgresql/data/.gomaf-backup-',
    'docker cp "$DB_ID:$CONTAINER_TMP" "$TMP"',
    'HOST_SHA=',
    '[[ "$HOST_SHA" == "$CONTAINER_SHA" ]]',
]
forbidden=[
    "pg_dump -h 127.0.0.1 -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -Fc --no-owner --no-acl' > \"$TMP\"",
    "cat \"$TMP\" | compose exec",
    'CONTAINER_TMP="/tmp/gomaf-backup-',
]
raise SystemExit(0 if all(x in s for x in required) and not any(x in s for x in forbidden) else 1)
PYBACKUPFILEFLOW
assert_cmd "backup container SHA extraction has portable quoting" python3 - <<'PYBACKUPSHAQUOTE'
from pathlib import Path
texts=[Path('ops/backup.sh').read_text(), Path('ops/lib.sh').read_text()]
needle='sha256sum "$1" | cut -d" " -f1'
broken="sha256sum \"$1\" | cut -d ' ' -f1"
raise SystemExit(0 if all(needle in t and broken not in t for t in texts) else 1)
PYBACKUPSHAQUOTE
assert_cmd "backup validation and restore avoid binary archive stdin pipes" python3 - <<'PYBACKUPRESTORE'
from pathlib import Path
s=Path('ops/lib.sh').read_text()
required=[
    'container_dump="/var/lib/postgresql/data/.gomaf-validate-',
    'container_dump="/var/lib/postgresql/data/.gomaf-restore-',
    'docker exec -i "$db_id" sh -ec',
    'cat > "$1"',
    'pg_restore --list "$1" >/dev/null',
    '--single-transaction "$dump"',
    'host_sha="$(sha256sum "$backup"',
]
forbidden=[
    'cat "$backup" | compose_at',
    'docker cp "$backup" "$db_id:$container_dump"',
    'container_dump="/tmp/gomaf-',
    "pg_restore --list >/dev/null'",
]
raise SystemExit(0 if all(x in s for x in required) and not any(x in s for x in forbidden) else 1)
PYBACKUPRESTORE
assert_cmd "backup maintenance preserves free-space floor plus incoming backup reserve" grep -q 'required = max(target_free, min_free + extra)' ops/backup-maintenance.sh
assert_cmd "storage pressure prunes backups before application/access logs" python3 - <<'PYSTORAGE'
from pathlib import Path
s=Path('ops/root/gomaf-storage-maintenance').read_text()
main=s[s.index('def main() -> None:'):]
need=['prune_docker_cache(work_free)', 'prune_exports(export_retention', 'prune_backups(target_free', 'prune_rotated_logs(target_free, audit_only=False)', 'prune_rotated_logs(min_free, audit_only=True)']
pos=[main.find(x) for x in need]
raise SystemExit(0 if all(i>=0 for i in pos) and pos==sorted(pos) else 1)
PYSTORAGE
assert_cmd "audit logs are excluded from ordinary low-space cleanup" bash -c 'grep -q "if audit_only:" ops/root/gomaf-storage-maintenance && grep -q "prune_rotated_logs(target_free, audit_only=False)" ops/root/gomaf-storage-maintenance && grep -q "prune_rotated_logs(min_free, audit_only=True)" ops/root/gomaf-storage-maintenance'
assert_cmd "low-space maintenance restores build headroom from disposable cache before protected data" python3 - <<'PYDOCKERGC'
from pathlib import Path
s=Path('ops/root/gomaf-storage-maintenance').read_text()
assert "work_free = max(target_free, 8192)" in s
assert "'builder', 'prune', '-f', '--filter', 'until=168h'" in s
assert "'builder', 'prune', '-f', '--filter', 'until=24h'" in s
assert "[docker, 'builder', 'prune', '-f']" in s
assert "'image', 'prune', '-f'" in s
assert "'image', 'prune', '-a'" not in s
assert 'prune_docker_cache(work_free)' in s
PYDOCKERGC
assert_cmd "log export redacts exact configured production secrets fail-closed" bash -c 'grep -q "REDACTED_PRODUCTION_SECRET" ops/root/gomaf-export-logs && grep -q "APP_ADMIN_PASSWORD" ops/root/gomaf-export-logs && grep -q "secret redaction failed" ops/root/gomaf-export-logs && grep -q "redaction-report.txt" ops/root/gomaf-export-logs'
assert_cmd "restore stages dump into a separate database before production swap" python3 - <<'PYRESTORESTAGE'
from pathlib import Path
s=Path('ops/lib.sh').read_text()
required=[
    'restore_db="gomaf_restore_${suffix}"',
    'createdb -h 127.0.0.1 -U "$POSTGRES_USER" -T template0 "$restore_db"',
    '--no-owner --no-acl --single-transaction "$dump"',
    r'ALTER DATABASE \"$prod\" RENAME TO \"$previous\"',
    r'ALTER DATABASE \"$restored\" RENAME TO \"$prod\"',
]
forbidden=['--clean --if-exists --no-owner --no-acl --single-transaction']
raise SystemExit(0 if all(x in s for x in required) and not any(x in s for x in forbidden) else 1)
PYRESTORESTAGE
assert_cmd "restore keeps previous database until trusted DB privilege finalization succeeds" python3 - <<'PYRESTORESWAP'
from pathlib import Path
s=Path('ops/lib.sh').read_text()
start=s.index('restore_database_from_dump_at() {')
body=s[start:]
bootstrap=body.index('compose_at "$PROJECT_ROOT" run --rm --no-deps db-role-bootstrap')
finalize=body.index('compose_at "$PROJECT_ROOT" run --rm --no-deps db-finalize', bootstrap)
drop=body.index('dropdb -h 127.0.0.1 -U "$POSTGRES_USER" --if-exists --force "$1"', finalize)
raise SystemExit(0 if bootstrap < finalize < drop and 'Swap the untouched previous DB back' in body and 'db-prepare' in body else 1)
PYRESTORESWAP
assert_cmd "restore database swap validates safe production database name" python3 - <<'PYRESTOREDBNAME'
from pathlib import Path
s=Path('ops/lib.sh').read_text()
required=['prod_db="$(env_get POSTGRES_DB)"', 'Некорректный POSTGRES_DB для restore']
raise SystemExit(0 if all(x in s for x in required) else 1)
PYRESTOREDBNAME
assert_cmd "daily backup timer exists" grep -q 'OnCalendar=\*-\*-\* 03:30:00' ops/install-backup-timer.sh
assert_cmd "daily backup service can read root-only production config without granting mafia Docker access" python3 - <<'PYBACKUPUNIT'
from pathlib import Path
s=Path('ops/install-backup-timer.sh').read_text()
assert 'User=root' in s and 'Group=root' in s and 'UMask=0077' in s
assert 'SupplementaryGroups=docker' not in s and 'User=mafia' not in s
PYBACKUPUNIT
assert_cmd "WP11-023 host transition reinstalls hardened daily backup unit" grep -q 'CANDIDATE_ROOT/ops/install-backup-timer.sh' ops/install-wp11-023-host-security.sh

assert_cmd "production backup path is canonical and matches storage maintenance" python3 - <<'PYCANONBACKUP'
from pathlib import Path
lib = Path('ops/lib.sh').read_text()
helper = Path('ops/root/gomaf-storage-maintenance').read_text()
raise SystemExit(0 if (
    'BACKUP_DIR должен быть строго /srv/gomaf-backups' in lib
    and "BACKUPS = Path('/srv/gomaf-backups')" in helper
) else 1)
PYCANONBACKUP
assert_cmd "WP11-023 release packaging is fail-closed and self-verifying" python3 - <<'PYPACK23'
from pathlib import Path
s=Path('ops/package-release.sh').read_text()
for token in ('bash ./ops/source-audit.sh','RELEASE-SHA256SUMS.txt','unzip -tqq "$BOOTSTRAP_ARCHIVE"','sha256sum -c RELEASE-SHA256SUMS.txt','ALL_ARCHIVE=','START-HERE.txt','CONTENTS-SHA256SUMS.txt'):
    assert token in s, token
PYPACK23
assert_cmd "release packaging emits one-shot WP11-023 client wrapper" bash -c 'grep -q "DEPLOY_WRAPPER" ops/package-release.sh && grep -q "client/gomaf-deploy-update.sh.in" ops/package-release.sh && grep -q "@@BOOTSTRAP_BASENAME@@" client/gomaf-deploy-update.sh.in'
assert_cmd "WP11-023 VPS release chain has no system zip/unzip dependency" python3 - <<'PYCANDIDATEZIP'
from pathlib import Path
safe=Path('ops/release/safe-zip.py').read_text()
assert 'MAX_FILES = 5000' in safe
assert 'MAX_UNCOMPRESSED = 128 * 1024 * 1024' in safe
assert 'non-regular ZIP entry not allowed' in safe
assert 'encrypted ZIP entry not allowed' in safe
assert 'unsafe ZIP path' in safe
for name in ('ops/release/package-candidate.sh','ops/bootstrap-wp11-023-predeploy.sh','ops/install-wp11-023-host-security.sh','ops/root/gomaf-release-common'):
    text=Path(name).read_text()
    assert 'command -v unzip' not in text, name
    assert 'unzip -q ' not in text, name
    assert 'unzip -t' not in text, name
assert 'safe-zip.py' in Path('ops/release/package-candidate.sh').read_text()
assert 'safe-zip.py' in Path('ops/bootstrap-wp11-023-predeploy.sh').read_text()
assert 'safe-zip.py' in Path('ops/install-wp11-023-host-security.sh').read_text()
assert 'gomaf-safe-zip' in Path('ops/root/gomaf-release-common').read_text()
assert 'gomaf-safe-zip' in Path('ops/install-wp11-023-host-security.sh').read_text()
PYCANDIDATEZIP
assert_cmd "WP11-023 package contains no obsolete VM workflow" bash -c '! find . -maxdepth 2 -type f \( -name "*WP11-023-VM*" -o -name "*VM-HANDOFF*" \) -print -quit | grep -q .'
assert_cmd "client candidate packaging does not mask primary failure with chown" python3 - <<'PYCLIENTPKGFAIL'
from pathlib import Path
for name in ('client/gomaf-deploy-update.sh.in','client/gomaf-deploy-update.sh'):
    s=Path(name).read_text()
    assert "package-candidate.sh' '$REMOTE_CANDIDATE' '$SEQUENCE' '$CURRENT_HASH' && sudo chown" in s, name
    assert "package-candidate.sh' '$REMOTE_CANDIDATE' '$SEQUENCE' '$CURRENT_HASH'; sudo chown" not in s, name
PYCLIENTPKGFAIL
assert_cmd "client wrapper preserves separate PREDEPLOY before production deploy" python3 - <<'PYCLIENT23'
from pathlib import Path
s=Path('client/gomaf-deploy-update.sh.in').read_text()
required=['sha256sum -c "$BOOTSTRAP_SHA_FILE"','wait_public_health 6','verify-production-freeze.sh','bootstrap-wp11-023-predeploy.sh','bootstrap-predeploy-evidence.tar.gz','install-wp11-023-host-security.sh','gomaf-predeploy-release','final-predeploy-evidence.tar.gz','gomaf-deploy-release','deployment-evidence.tar.gz','UPDATE PASS']
raise SystemExit(0 if all(x in s for x in required) else 1)
PYCLIENT23
assert_cmd "client wrapper requires explicit interactive approval after FINAL PREDEPLOY" python3 - <<'PYCLIENTAPPROVAL'
from pathlib import Path
for name in ('client/gomaf-deploy-update.sh.in','client/gomaf-deploy-update.sh'):
    s=Path(name).read_text()
    final=s.index('verify_evidence_archive "$RESULT_DIR/final-predeploy-evidence.tar.gz" final')
    phrase=s.index("DEPLOY_APPROVAL_PHRASE='РАЗРЕШАЮ ЗАПУСК PRODUCTION'", final)
    tty=s.index('[[ -t 0 ]]', phrase)
    read=s.index('IFS= read -r deploy_approval', tty)
    exact=s.index('[[ "$deploy_approval" == "$DEPLOY_APPROVAL_PHRASE" ]]', read)
    deploy=s.index("new_mafia_ssh 'sudo -n /usr/local/sbin/gomaf-deploy-release'", exact)
    if not final < phrase < tty < read < exact < deploy:
        raise SystemExit(1)
    forbidden=('GOMAF_DEPLOY_APPROVED','AUTO_DEPLOY','--yes','--force-deploy')
    if any(token in s for token in forbidden):
        raise SystemExit(1)
raise SystemExit(0)
PYCLIENTAPPROVAL
assert_cmd "final PREDEPLOY evidence archive is staged and self-verifying" bash -c 'grep -q "evidence-stage" ops/root/gomaf-predeploy-release && grep -q "SHA256SUMS.txt" ops/root/gomaf-predeploy-release'
assert_cmd "successful deploy drops only temporary approved-image tag after production audit" python3 - <<'PYAPPROVEDTAG'
from pathlib import Path
s=Path('ops/root/gomaf-deploy-release').read_text()
audit=s.index('PRODUCTION AUDIT PASS')
remove=s.rindex('docker image rm "$candidate_image"')
done=s.rindex('DEPLOY PASS and PRODUCTION AUDIT PASS committed for exact image')
assert audit < remove < done
assert 'gomaf/wp11-023-approved:' in s
PYAPPROVEDTAG
assert_cmd "deploy.sh failure always emits bound evidence and reconciles active source" python3 - <<'PYDEPLOYFAILCHAIN'
from pathlib import Path
s=Path('ops/root/gomaf-deploy-release').read_text()
assert "printf 'deploy-sh\\n' > \"$txn/failure-stage.txt\"" in s
assert 'deployment-failure-evidence.tar.gz' in s
assert 'post_hash="$(cat "$RELEASE_RUNTIME_DIR/current_source_hash"' in s
assert 'automatic rollback is mandatory' in s
assert "rsync -a --delete --exclude '.deploy/' --exclude '.audit/' \"$active_after/\" /srv/gomaf/ && chown -R root:root /srv/gomaf" in s
assert 'anti-replay state was not committed' in s
client=Path('client/gomaf-deploy-update.sh.in').read_text()
assert "stage == 'deploy-sh'" in client and "stage == 'post-deploy-audit'" in client
PYDEPLOYFAILCHAIN

assert_cmd "post-deploy production-audit failure triggers automatic rollback and active-source restoration" bash -c 'grep -q "automatically rolling back" ops/root/gomaf-deploy-release && grep -q "ops/rollback.sh" ops/root/gomaf-deploy-release && grep -q "active source restored" ops/root/gomaf-deploy-release && grep -q "source-restore.log" ops/root/gomaf-deploy-release && grep -q "deployment-failure-evidence.tar.gz" ops/root/gomaf-deploy-release'
assert_cmd "release chain has no release-signing key or detached-signature dependency" python3 - <<'PYCLIENTSIGN'
from pathlib import Path
paths=[
 'client/gomaf-deploy-update.sh.in','client/gomaf-deploy-update.sh',
 'ops/bootstrap-wp11-023-predeploy.sh','ops/root/gomaf-release-common',
 'ops/root/gomaf-predeploy-release','ops/root/gomaf-deploy-release'
]
for name in paths:
    s=Path(name).read_text()
    for forbidden in ('candidate.zip.sig','release-private.pem','release-public.pem','openssl pkeyutl -sign','openssl pkeyutl -verify','release-signing-public.pem'):
        assert forbidden not in s, (name, forbidden)
assert not Path('ops/release/sign-candidate.sh').exists()
assert not Path('ops/release/generate-release-key.sh').exists()
PYCLIENTSIGN
assert_cmd "client is restartable after mafia key rotation or post-hardening gate failure" python3 - <<'PYCLIENTRECOVERY'
from pathlib import Path
s=Path('client/gomaf-deploy-update.sh.in').read_text()
assert 'install_mafia_managed_alias(){' in s
assert "Legacy mafia alias уже не действует" in s
assert "opsadmin recovery path" in s
assert "sudo -n cat /srv/gomaf/.deploy/current_source_hash" in s
keycheck=s.index("Новый ограниченный mafia SSH key не работает после hardening")
alias=s.index('install_mafia_managed_alias', keycheck)
final=s.index('FINAL PREDEPLOY:', keycheck)
assert keycheck < alias < final
assert 'wait_ssh "$MAFIA_SSH" 180 || fail' not in s
PYCLIENTRECOVERY

assert_cmd "client uses existing opsadmin trust and rotates mafia to separate restricted key" python3 - <<'PYCLIENTKEYS'
from pathlib import Path
s=Path('client/gomaf-deploy-update.sh.in').read_text()
for token in ('cat ~/.ssh/authorized_keys','mafia-deploy-ed25519','IdsOnly','opsadmin-authorized_keys'):
    pass
assert 'cat ~/.ssh/authorized_keys' in s
assert 'mafia-deploy-ed25519' in s
assert '-o IdentitiesOnly=yes' in s
assert 'opsadmin-authorized_keys' in s
PYCLIENTKEYS
assert_cmd "client keeps ordinary SSH non-multiplexed and SHA-verifies transfers" python3 - <<'PYCLIENTSSH23'
from pathlib import Path
s=Path('client/gomaf-deploy-update.sh.in').read_text()
for token in ('ControlMaster=no','upload_alias()','download_alias()','sha256sum','wait_ssh "$OPSADMIN_SSH" 180','-o IdentitiesOnly=yes -i "$MAFIA_KEY"'):
    assert token in s, token
assert 'wait_ssh "$MAFIA_SSH" 180 || fail' not in s
assert 'scp ' not in s
assert 'bash -s' not in s
PYCLIENTSSH23
assert_cmd "client permits offline production only after strict read-only freeze confirmation" python3 - <<'PYCLIENTFREEZE'
from pathlib import Path
s=Path('client/gomaf-deploy-update.sh.in').read_text()
f=Path('ops/verify-production-freeze.sh').read_text()
for token in ('PUBLIC_HEALTH_READY=0','wait_public_health 6','verify-production-freeze.sh','intentional production freeze'):
    assert token in s, token
assert "wait_public_health 120 || fail" not in s
for token in ('gomaf-app-1','gomaf-db-1','gomaf-caddy-1','State.Running','sport = :80 or sport = :443','PRODUCTION_FREEZE_CONFIRMED'):
    assert token in f, token
assert 'docker start' not in f and 'docker compose up' not in f
PYCLIENTFREEZE
assert_cmd "bootstrap PREDEPLOY shadows runtime metadata instead of writing production state" python3 - <<'PYBOOTSHADOW'
from pathlib import Path
s=Path('ops/bootstrap-wp11-023-predeploy.sh').read_text()
for token in ('runtime-shadow','predeploy.env','current_image','GOMAF_RUNTIME_DIR="$TXN/runtime-shadow"','GOMAF_ENV_FILE="$TXN/predeploy.env"'):
    assert token in s, token
PYBOOTSHADOW
assert_cmd "host hardening requires candidate-bound bootstrap PREDEPLOY PASS before mutation" python3 - <<'PYHOSTBOUND'
from pathlib import Path
s=Path('ops/install-wp11-023-host-security.sh').read_text()
for token in ("grep -qx 'STATUS=PASS'",'ARCHIVE_SHA256=$ARCHIVE_SHA','RELEASE_SEQUENCE=$SEQUENCE','EXPECTED_CURRENT_SOURCE_HASH=$EXPECTED','No host mutation happened above this line'):
    assert token in s, token
assert s.index('No host mutation happened above this line') < s.index('install -d -m 0700 -o root -g root /var/lib/gomaf-release')
PYHOSTBOUND
assert_cmd "ZIP stdlib extraction has a bounded permission normalizer" python3 - <<'PYNORMALIZEPERMS'
from pathlib import Path
s=Path('ops/normalize-source-permissions.sh').read_text()
required=['find "$ROOT/ops" -maxdepth 1 -type f -name','find "$ROOT/ops/root" -maxdepth 1 -type f','find "$ROOT/docker" -maxdepth 1 -type f -name']
forbidden=['chmod -R','find "$ROOT" -type f -exec chmod']
raise SystemExit(0 if all(x in s for x in required) and not any(x in s for x in forbidden) else 1)
PYNORMALIZEPERMS
assert_cmd "SSH hardening drop-in precedes cloud-init" grep -q '/etc/ssh/sshd_config.d/00-gomaf-hardening.conf' ops/install-wp11-023-host-security.sh
assert_cmd "SSH root login disabled" grep -q 'PermitRootLogin no' ops/install-wp11-023-host-security.sh
assert_cmd "SSH password auth disabled" grep -q 'PasswordAuthentication no' ops/install-wp11-023-host-security.sh
assert_cmd "SSH installer validates effective configuration" grep -q 'ssh_effective=.*sshd -T' ops/install-wp11-023-host-security.sh
assert_cmd "SSH forwarding is disabled for deployment identities" bash -c 'grep -q "AllowTcpForwarding no" ops/install-wp11-023-host-security.sh && grep -q "AllowStreamLocalForwarding no" ops/install-wp11-023-host-security.sh && grep -q "PermitOpen none" ops/install-wp11-023-host-security.sh && grep -q "PermitListen none" ops/install-wp11-023-host-security.sh'
assert_cmd "production audit validates effective SSH configuration" grep -q 'effective SSH hardening' ops/production-audit.sh
assert_cmd "UFW enables 80/tcp" grep -q 'ufw allow 80/tcp' ops/setup-server.sh
assert_cmd "UFW enables 443/tcp" grep -q 'ufw allow 443/tcp' ops/setup-server.sh
assert_cmd "deployment account has no unrestricted NOPASSWD ALL" bash -c '! grep -R "mafia .*NOPASSWD:ALL\|mafia .*NOPASSWD: ALL" ops --exclude=source-audit.sh >/dev/null'
assert_cmd "DB schema checks pass SQL as data instead of nested shell quoting" python3 - <<'PYDBQUOTE'
from pathlib import Path
for name in ('ops/deploy.sh','ops/production-audit.sh'):
    text=Path(name).read_text(); assert chr(39)*3 not in text, name
assert 'db_query_at "$RELEASE" "$schema_sql"' in Path('ops/deploy.sh').read_text()
PYDBQUOTE
assert_cmd "unexpected deploy exit after maintenance has automatic recovery" python3 - <<'PYDEPLOYRECOVERY'
from pathlib import Path
text=Path('ops/deploy.sh').read_text()
for token in ('MAINTENANCE_STARTED=1','RECOVERY_ATTEMPTED=0','rollback_pre_public','rollback_after_public','Неожиданный EXIT после возможного изменения БД'): assert token in text, token
PYDEPLOYRECOVERY
assert_cmd "post-migration command substitutions are explicitly rollback-guarded" python3 - <<'PYPOSTMIG'
from pathlib import Path
text=Path('ops/deploy.sh').read_text()
for token in ('if ! schema_state=', 'if ! compat_bad=', 'if ! internal_headers=', 'if ! csrf_code=', 'if ! forwarded_headers='): assert token in text, token
PYPOSTMIG
assert_cmd "restore waits for readiness and does not run v10-only smoke against recovered v9" python3 - <<'PYRESTOREREADY'
from pathlib import Path
s=Path('ops/restore.sh').read_text(); assert 'wait_healthy app 240' in s; assert 'wait_local_https_health "$DOMAIN" 180 3' in s; assert 'ops/smoke-test.sh' not in s
PYRESTOREREADY
assert_cmd "HTTPS readiness uses retry loop rather than immediate curl after Caddy start" python3 - <<'PYHTTPSREADY'
from pathlib import Path
lib=Path('ops/lib.sh').read_text(); restore=Path('ops/restore.sh').read_text(); deploy=Path('ops/deploy.sh').read_text()
assert 'wait_local_https_health()' in lib and 'sleep "$interval"' in lib; assert 'wait_local_https_health "$DOMAIN" 180 3' in restore; assert 'wait_local_https_health "$DOMAIN" 180 4' in deploy
PYHTTPSREADY


assert_cmd "runtime entrypoint precreates private application and audit log files" python3 - <<'PYLOGENTRY'
from pathlib import Path
s=Path('docker/app-entrypoint.sh').read_text()
for token in (
    'LOG_DIR="${APP_LOG_DIR:-/tmp/gomaf-logs}"',
    ': >> "$LOG_DIR/application.log"',
    ': >> "$LOG_DIR/audit.log"',
    'chmod 0640 "$LOG_DIR/application.log" "$LOG_DIR/audit.log"',
):
    assert token in s, token
PYLOGENTRY
assert_cmd "production audit inspects protected log files through container mounts" python3 - <<'PYLOGAUDIT'
from pathlib import Path
s=Path('ops/production-audit.sh').read_text()
assert 'check_container_log_file()' in s
assert 'docker exec "$container_id"' in s
assert 'check_container_log_file "$APP_ID" /var/log/gomaf/application.log app' in s
assert 'check_container_log_file "$APP_ID" /var/log/gomaf/audit.log app' in s
assert 'check_container_log_file "$CADDY_ID" /var/log/caddy/access.log caddy' in s
assert 'for file in /srv/gomaf-logs/app/application.log' not in s
PYLOGAUDIT
assert_cmd "production audit requires recurring ops visibility and host security monitors" python3 - <<'PYOPSAUDIT'
from pathlib import Path
s=Path('ops/production-audit.sh').read_text()
for token in (
    'gomaf-aide-check.timer',
    'gomaf-security-check.timer',
    'gomaf-ops-health-check.timer',
    'gomaf-test-memory-guard.service',
    'operational health monitor last result PASS',
    'auditd lost=0',
    'fail2ban recidive jail active',
):
    assert token in s
PYOPSAUDIT
assert_cmd "production audit checks UFW enablement rather than daemon active state" python3 - <<'PYUFWAUDIT'
from pathlib import Path
s=Path('ops/production-audit.sh').read_text()
assert 'systemctl is-enabled --quiet ufw' in s
assert "grep -Eq '^ENABLED=yes$' /etc/ufw/ufw.conf" in s
assert 'systemctl is-active --quiet ufw' not in s
PYUFWAUDIT
assert_cmd "fresh setup allows SSH without UFW connection rate limit" python3 - <<'PYUFWSSH'
from pathlib import Path
s=Path('ops/setup-server.sh').read_text()
assert 'ufw allow "$SSH_PORT/tcp"' in s
assert 'ufw limit "$SSH_PORT/tcp"' not in s
PYUFWSSH

assert_cmd "authorization version remains represented in principal regression test" grep -q "authorizationVersion()).isEqualTo(7)" backend/src/test/java/com/mafia/classicmafia/security/MafiaUserPrincipalTests.java

assert_cmd "WP11-023 evidence archives are self-verifying and client revalidates bindings" python3 - <<'PYEVIDENCECHAIN'
from pathlib import Path
bootstrap=Path('ops/bootstrap-wp11-023-predeploy.sh').read_text()
pre=Path('ops/root/gomaf-predeploy-release').read_text()
deploy=Path('ops/root/gomaf-deploy-release').read_text()
client=Path('client/gomaf-deploy-update.sh.in').read_text()
assert 'SHA256SUMS.txt' in bootstrap and 'find predeploy-report -type f' in bootstrap
assert 'SHA256SUMS.txt' in pre and 'find predeploy-report -type f' in pre
assert 'FAILURE-SHA256SUMS.txt' in deploy and 'deployment-failure-evidence.tar.gz' in deploy
assert 'verify_evidence_archive(){' in client
for kind in ('bootstrap','final','deploy','failure'):
    assert f' {kind} || fail' in client, kind
assert "'ARCHIVE_SHA256':candidate_sha" in client
assert "'RELEASE_SEQUENCE':sequence" in client
assert "'EXPECTED_CURRENT_SOURCE_HASH':baseline" in client
assert "'[gomaf] DEPLOY PASS:'" in client
assert "'PRODUCTION AUDIT PASS'" in client
PYEVIDENCECHAIN

assert_cmd "API route contract matrix is exact and fail-closed" python3 - <<'PYAPIMATRIXSOURCE'
from pathlib import Path
p=Path('backend/src/test/resources/api-route-contract-matrix.tsv')
rows=[line.split('\t') for line in p.read_text().splitlines()[1:] if line]
assert len(rows)==57 and len({r[0] for r in rows})==57
assert all(len(r)==3 and r[1] and 'runtime-inventory' in r[2].split(';') and 'domain-flow' in r[2].split(';') for r in rows)
t=Path('backend/src/test/java/com/mafia/classicmafia/ApiRouteContractMatrixIntegrationTests.java').read_text()
for token in ('RequestMappingHandlerMapping','hasSize(57)','everyAuthnBoundaryRowRejectsAnonymousRequests','requiredJsonBodyRoutes'):
    assert token in t
PYAPIMATRIXSOURCE
assert_cmd "authenticated quality browser evidence is mandatory" bash -c "grep -q 'AxeBuilder' frontend/e2e/authenticated-quality.spec.ts && grep -q 'loginCredentials' frontend/e2e/authenticated-quality.spec.ts && grep -q 'assertKeyboardFocus' frontend/e2e/authenticated-quality.spec.ts && grep -q '/admin/moderators' frontend/e2e/authenticated-quality.spec.ts"
assert_cmd "ADMIN browser login has no second-factor helper" bash -c "test ! -e frontend/e2e/admin-mfa.ts && ! grep -RIE 'completeAdminMfa|one-time-code|Подтверждение входа' frontend/e2e frontend/src >/dev/null"
assert_cmd "PREDEPLOY preserves and binds complete screenshot evidence" python3 - <<'PYSCREENEVIDENCE'
from pathlib import Path
p=Path('ops/wp11-023-predeploy.sh').read_text()
r=Path('ops/root/gomaf-predeploy-release').read_text()
for token in ["expected 215 Playwright screenshots", "test-results", "SCREENSHOT-SHA256SUMS.txt", "SCREENSHOT_MANIFEST_SHA256", "SCREENSHOT_COUNT=$SCREENSHOT_COUNT"]:
    assert token in p, token
for token in ["predeploy screenshot manifest missing", "SCREENSHOT_MANIFEST_SHA256", "SCREENSHOT_COUNT=215", "screenshot checksums invalid"]:
    assert token in r, token
PYSCREENEVIDENCE
assert_cmd "requested rules/players/history/game/profile/mobile UI regressions are guarded" python3 - <<'PYREQUESTEDUI'
from pathlib import Path
app=Path('frontend/src/app/App.tsx').read_text()
css=Path('frontend/src/app/app.css').read_text()
rules=Path('frontend/src/content/RulesPage.tsx').read_text()
players=Path('frontend/src/players/PlayersPage.tsx').read_text()
profile=Path('frontend/src/players/PlayerProfilePage.tsx').read_text()
self_profile=Path('frontend/src/profile/ProfilePage.tsx').read_text()
game=Path('frontend/src/games/GameDetailPage.tsx').read_text()
game_form=Path('frontend/src/games/GameFormPage.tsx').read_text()
e2e=Path('frontend/e2e/g4.spec.ts').read_text()
assert 'mobile-primary-nav' in app and 'Разделы сайта' in app
assert 'max-height: calc(100dvh - 108px)' in css and 'overflow-y: auto' in css and 'scrollbar-gutter: stable' in css
assert 'Навигация по правилам' in rules and 'rules-back-top' in rules and "block.type === 'continuation'" in rules
assert 'Средний доп. балл' in players and 'player.averageScore' in players
assert '`/games/${game.gameId}`' in profile and '`/games/${game.gameId}`' in self_profile
for token in ('Таблица игроков и очков','Первый убитый','Лучший ход','victoryPoints','additionalPoints'):
    assert token in game, token
for token in ('game-entry-table','registered-game-players','getPlayers','ЛХ 1','ЛХ 2','ЛХ 3'):
    assert token in game_form, token
assert 'loadRegisteredPlayers' in game_form and "size: 100" in game_form and "sort: 'username,asc'" in game_form
for document in (profile, self_profile):
    assert 'width=\"208\"' in document and 'height=\"208\"' in document
    assert 'srcSet' in document and 'size=256' in document
    assert 'width=1280' in document and 'fetchPriority=\"high\"' in document and 'loading=\"eager\"' in document
assert 'font-size: clamp(1.9rem, 3.2vw, 2.75rem)' in css
for token in ('AVATAR_OUTPUT_SIDE = 512','type="range"','Вписать полностью','После обработки: 512 x 512 px, PNG.'):
    assert token in self_profile, token
assert 'new FileReader()' in self_profile and 'readAsDataURL(next)' in self_profile
assert 'URL.createObjectURL(next)' not in self_profile
assert 'padding: 28px 32px 30px 276px' in css
assert 'min-height: 184px' in css
assert 'font-family: \"GoMaf Inter\"' in css
assert 'min-height: 3.2em' in css
assert 'profile-social-copy' in profile and 'profile-about' in profile
assert 'profile-identity' not in profile and 'profile-about-frame' not in profile
assert 'always-visible mobile section navigation' in e2e and 'independently scrollable table of contents' in e2e
PYREQUESTEDUI
assert_cmd "UI/UX scalability and responsive layout audit" bash ./ops/ui-ux-audit.sh


assert_cmd "user password minimum is 6 characters" bash -c 'grep -q "MIN_LENGTH = 6" backend/src/main/java/com/mafia/classicmafia/security/PasswordPolicy.java && grep -q "minLength={6}" frontend/src/auth/RegisterPage.tsx' 
assert_cmd "V4 schedule tournament profile migration exists" bash -c 'test -f backend/src/main/resources/db/migration/V4__schedule_tournaments_profiles_video.sql && grep -q "CREATE TABLE IF NOT EXISTS tournaments" backend/src/main/resources/db/migration/V4__schedule_tournaments_profiles_video.sql && grep -q "CREATE TABLE IF NOT EXISTS schedule_participants" backend/src/main/resources/db/migration/V4__schedule_tournaments_profiles_video.sql && grep -q "CREATE TABLE IF NOT EXISTS game_videos" backend/src/main/resources/db/migration/V4__schedule_tournaments_profiles_video.sql'
assert_cmd "hidden tournament games enforce server-side visibility" bash -c 'grep -q "getDetailedVisible" backend/src/main/java/com/mafia/classicmafia/service/GameService.java && grep -q "hiddenAllowedUsers" backend/src/main/java/com/mafia/classicmafia/entity/Game.java'
assert_cmd "game video links restrict schemes" bash -c 'grep -q "equalsIgnoreCase(\"https\")" backend/src/main/java/com/mafia/classicmafia/service/GameService.java && grep -q "game_videos" backend/src/main/resources/db/migration/V4__schedule_tournaments_profiles_video.sql'
assert_cmd "ranking exposes average additional points" bash -c 'grep -q "averageAdditionalPoints" backend/src/main/java/com/mafia/classicmafia/view/GameRankingRow.java && grep -q "getAdditionalPoints" backend/src/main/java/com/mafia/classicmafia/entity/GamePlayer.java'

assert_cmd "deploy and rehearsal require Flyway v10, persistent login and retired MFA state" python3 - <<'PYV10DEPLOY'
from pathlib import Path
d=Path('ops/deploy.sh').read_text()
r=Path('ops/migration-rehearsal.sh').read_text()
assert 'Flyway V10/persistent-login/security/MFA retirement check failed' in d
assert '[[ "$FLYWAY_VERSION" == 10 ]]' in r
for token in ('admin_mfa_credentials','admin_mfa_recovery_codes','mfa-ip:%','mfa-pair:%','persistent_login_tokens'):
    assert token in d and token in r, token
assert 'legacy MFA compatibility tables unexpectedly changed' in r
assert 'V10 persistent-login schema отсутствует или неполна' in r
for token in ('idx_users_public_rating_username','idx_games_public_date_id','idx_games_tournament_date_id','idx_news_created_at_desc','idx_tournaments_created_at_desc'):
    assert token in d and token in r, token
assert 'runtime role неожиданно может CREATE TABLE' in r
assert 'runtime role неожиданно может читать flyway_schema_history' in r
PYV10DEPLOY
assert_cmd "Flyway latest-version checks use installed_rank rather than lexicographic max(version)" python3 - <<'PYFLYWAYLATEST'
from pathlib import Path
for rel in ('ops/migration-rehearsal.sh','ops/deploy.sh','ops/production-audit.sh'):
    text=Path(rel).read_text()
    assert 'max(version) FROM flyway_schema_history' not in text, rel
    assert 'WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1' in text, rel
PYFLYWAYLATEST
assert_cmd "post-V9 ADMIN security sessions are not misclassified as legacy MFA state" python3 - <<'PYADMINSESSION'
from pathlib import Path
for rel in ('ops/migration-rehearsal.sh','ops/deploy.sh'):
    text=Path(rel).read_text()
    assert "count(*)=0 FROM security_sessions WHERE role_name='ADMIN'" not in text, rel
    assert "count(*) FROM security_sessions WHERE role_name='ADMIN'" not in text, rel
for token in ('admin_mfa_credentials','admin_mfa_recovery_codes','mfa-ip:%','mfa-pair:%'):
    assert token in Path('ops/migration-rehearsal.sh').read_text(), token
    assert token in Path('ops/deploy.sh').read_text(), token
PYADMINSESSION

assert_cmd "long-lived login is opaque, revocable, rotated and HttpOnly" python3 - <<'PYPERSISTLOGIN'
from pathlib import Path
service=Path('backend/src/main/java/com/mafia/classicmafia/security/PersistentLoginService.java').read_text()
filter_=Path('backend/src/main/java/com/mafia/classicmafia/security/PersistentLoginFilter.java').read_text()
migration=Path('backend/src/main/resources/db/migration/V10__persistent_login_tokens.sql').read_text()
properties=Path('backend/src/main/resources/application.properties').read_text()
for token in ('Duration.ofDays(90)','Duration.ofDays(30)','HttpOnly','SameSite=Lax','MessageDigest.isEqual','setValidatorHash','getValidatorHash','tokens.delete(token)','create(user, now)'):
    assert token in service, token
for token in ('request.getSession(true)','request.changeSessionId()','securitySessions.register'):
    assert token in filter_, token
assert 'CREATE TABLE persistent_login_tokens' in migration and 'validator_hash VARCHAR(64)' in migration
assert 'server.servlet.session.timeout=12h' in properties
assert 'jwt' not in service.lower()
PYPERSISTLOGIN

assert_cmd "WP11-023 preserves moderator-scope rollback compatibility while adding V5" python3 - <<'PYV5ROLLBACK'
from pathlib import Path
migration=Path('backend/src/main/resources/db/migration/V5__security_hardening.sql').read_text()
enum=Path('backend/src/main/java/com/mafia/classicmafia/entity/ModeratorScope.java').read_text()
deploy=Path('ops/deploy.sh').read_text()
rehearsal=Path('ops/migration-rehearsal.sh').read_text()
assert 'TOURNAMENTS_MANAGE' not in migration
assert 'TOURNAMENTS_MANAGE' not in enum
assert "scope NOT IN ('NEWS_MANAGE','RULES_MANAGE','GAMES_MANAGE','SCHEDULE_MANAGE'" in deploy
assert "scope NOT IN ('NEWS_MANAGE','RULES_MANAGE','GAMES_MANAGE','SCHEDULE_MANAGE'" in rehearsal
assert 'rollback compatibility violated' in rehearsal
PYV5ROLLBACK

assert_cmd "profile about text is bounded" bash -c 'grep -q "max(500" frontend/src/profile/ProfilePage.tsx && grep -q "normalized.length() > 500" backend/src/main/java/com/mafia/classicmafia/api/ProfileApiController.java'
assert_cmd "MFA runtime is removed while immutable migration history is preserved" python3 - <<'PYMFARETIRE'
from pathlib import Path
main=Path('backend/src/main')
frontend=Path('frontend/src')
for root in (main, frontend):
    for path in root.rglob('*'):
        if not path.is_file(): continue
        rel=str(path).replace('\\','/')
        if '/db/migration/V5__' in rel or '/db/migration/V6__' in rel or '/db/migration/V7__' in rel or '/db/migration/V9__' in rel:
            continue
        if path.suffix not in {'.java','.properties','.ts','.tsx','.css'}: continue
        text=path.read_text(errors='ignore').lower()
        assert all(token not in text for token in ('auth/mfa','mfacrypto','adminmfa','totp','one-time-code','app_security_mfa')), rel
v9=Path('backend/src/main/resources/db/migration/V9__retire_mfa_runtime.sql').read_text()
for token in ('DELETE FROM admin_mfa_credentials','DELETE FROM admin_mfa_recovery_codes',"bucket_key LIKE 'mfa-ip:%'","DELETE FROM security_sessions"):
    assert token in v9, token
assert not Path('backend/src/main/java/com/mafia/classicmafia/security/AdminMfaService.java').exists()
assert not Path('backend/src/main/java/com/mafia/classicmafia/security/MfaCryptoService.java').exists()
assert not Path('frontend/e2e/admin-mfa.ts').exists()
installer=Path('ops/install-wp11-023-host-security.sh').read_text()
assert "if k == 'APP_SECURITY_MFA_ENCRYPTION_KEY': continue" not in installer
assert "APP_SECURITY_MFA_ENCRYPTION_KEY" not in Path('compose.prod.yaml').read_text()
PYMFARETIRE
assert_cmd "public API controllers do not reflect caught exception messages" bash -c '! grep -R "exception.getMessage()" backend/src/main/java/com/mafia/classicmafia/api --exclude=ApiExceptionHandler.java >/dev/null'

printf 'SOURCE_AUDIT_PASS=%s SOURCE_AUDIT_FAIL=%s\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
