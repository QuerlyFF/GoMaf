#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
pass=0; fail=0
ok(){ printf 'PASS  %s\n' "$1"; pass=$((pass+1)); }
bad(){ printf 'FAIL  %s\n' "$1"; fail=$((fail+1)); }
check_file(){ [[ -f "$1" ]] && ok "$1 exists" || bad "$1 exists"; }
check_grep(){ local label="$1" pattern="$2" file="$3"; grep -Eq "$pattern" "$file" && ok "$label" || bad "$label"; }
check_absent(){ local label="$1" pattern="$2" file="$3"; if grep -Eq "$pattern" "$file"; then bad "$label"; else ok "$label"; fi; }

# WP11-010 Profiles
for f in backend/src/main/java/com/mafia/classicmafia/api/ProfileApiController.java backend/src/test/java/com/mafia/classicmafia/ProfileApiIntegrationTests.java frontend/src/profile/ProfilePage.tsx frontend/src/api/profile.ts; do check_file "$f"; done
check_grep 'self profile API exists' '@RequestMapping\("/api/v1/profile"\)' backend/src/main/java/com/mafia/classicmafia/api/ProfileApiController.java
check_grep 'profile about bounded to 500' 'length\(\) > 500' backend/src/main/java/com/mafia/classicmafia/api/ProfileApiController.java
check_grep 'avatar uses existing sanitizer' 'avatarService\.validate' backend/src/main/java/com/mafia/classicmafia/api/ProfileApiController.java
check_grep 'avatar upload rate limited' 'allowAvatarUpload' backend/src/main/java/com/mafia/classicmafia/api/ProfileApiController.java
check_grep 'self history reuses player read visibility' 'getSelfGames' backend/src/main/java/com/mafia/classicmafia/api/ProfileApiController.java
check_grep 'FormData keeps browser multipart boundary' 'instanceof FormData' frontend/src/api/client.ts
check_grep 'profile integration tests cover sanitized PNG' 'avatarUploadSanitizesToPng' backend/src/test/java/com/mafia/classicmafia/ProfileApiIntegrationTests.java
check_grep 'profile multipart test uses compatible multipart builder type' 'var upload = multipart' backend/src/test/java/com/mafia/classicmafia/ProfileApiIntegrationTests.java
check_grep 'profile integration tests cover hidden history' 'selfHistoryUsesSameHiddenGameVisibilityPolicy' backend/src/test/java/com/mafia/classicmafia/ProfileApiIntegrationTests.java

# WP11-011 Games
for f in backend/src/main/java/com/mafia/classicmafia/api/GamesApiController.java frontend/src/games/GamesPage.tsx frontend/src/games/GameDetailPage.tsx frontend/src/games/GameFormPage.tsx frontend/src/api/games.ts; do check_file "$f"; done
check_grep 'games API delegates canonical scoring/save' 'games\.saveGame' backend/src/main/java/com/mafia/classicmafia/api/GamesApiController.java
check_grep 'games detail enforces central visibility' 'getDetailedVisible' backend/src/main/java/com/mafia/classicmafia/api/GamesApiController.java
check_grep 'games writes require GAMES_MANAGE' 'ModeratorScope\.GAMES_MANAGE' backend/src/main/java/com/mafia/classicmafia/api/GamesApiController.java
check_grep 'games UI has ten seat model' 'length: 10' frontend/src/games/GameFormPage.tsx
check_absent 'games UI does not calculate authoritative score' 'victoryPoints.*=|totalPoints.*=' frontend/src/games/GameFormPage.tsx

# WP11-012 Schedule/events
for f in backend/src/main/java/com/mafia/classicmafia/api/ScheduleApiController.java frontend/src/schedule/SchedulePage.tsx frontend/src/api/schedule.ts; do check_file "$f"; done
check_grep 'schedule GAME/EVENT parsed server-side' 'ScheduleEntryType\.valueOf' backend/src/main/java/com/mafia/classicmafia/api/ScheduleApiController.java
check_grep 'events are forcibly unrated' 'type == ScheduleEntryType\.GAME && body\.rated' backend/src/main/java/com/mafia/classicmafia/api/ScheduleApiController.java
check_grep 'events force classic format' 'type == ScheduleEntryType\.GAME \? parseFormat.*: MafiaFormat\.CLASSIC' backend/src/main/java/com/mafia/classicmafia/api/ScheduleApiController.java
check_grep 'participation restricted to games' '!entry\.isGame\(\)' backend/src/main/java/com/mafia/classicmafia/api/ScheduleApiController.java
check_grep 'schedule React heading is exact accepted label' '<h1>РАСПИСАНИЕ</h1>' frontend/src/schedule/SchedulePage.tsx
check_grep 'schedule UI exposes rated choice' 'rated' frontend/src/schedule/SchedulePage.tsx
check_grep 'schedule UI exposes city/classic choice' 'CITY|Городская' frontend/src/schedule/SchedulePage.tsx

# WP11-013 Tournaments
for f in backend/src/main/java/com/mafia/classicmafia/api/TournamentsApiController.java frontend/src/tournaments/TournamentsPage.tsx frontend/src/tournaments/TournamentDetailPage.tsx frontend/src/tournaments/TournamentFormPage.tsx frontend/src/api/tournaments.ts; do check_file "$f"; done
check_grep 'tournament visibility uses central visibleGames' 'visibleGames' backend/src/main/java/com/mafia/classicmafia/api/TournamentsApiController.java
check_grep 'invitation rows only for managers' 'List<TournamentParticipantResponse> invitationRows = manageable' backend/src/main/java/com/mafia/classicmafia/api/TournamentsApiController.java
check_grep 'tournament management preserves GAMES_MANAGE scope' 'ModeratorScope\.GAMES_MANAGE' backend/src/main/java/com/mafia/classicmafia/api/TournamentsApiController.java
check_grep 'paid participation still delegated to canonical service' 'toggleParticipation' backend/src/main/java/com/mafia/classicmafia/api/TournamentsApiController.java

# WP11-014 Rating
check_file backend/src/main/java/com/mafia/classicmafia/api/RatingApiController.java
check_file frontend/src/rating/RatingPage.tsx
check_file frontend/src/api/rating.ts
check_grep 'rating API reuses canonical aggregate' 'buildRanking' backend/src/main/java/com/mafia/classicmafia/api/RatingApiController.java
check_grep 'rating exposes average additional points' 'averageAdditionalPoints' frontend/src/api/types.ts

# WP11-015 News/rules
for f in backend/src/main/java/com/mafia/classicmafia/api/ContentApiController.java frontend/src/content/NewsPage.tsx frontend/src/content/RulesPage.tsx frontend/src/api/content.ts; do check_file "$f"; done
check_grep 'news uses NEWS_MANAGE scope' 'ModeratorScope\.NEWS_MANAGE' backend/src/main/java/com/mafia/classicmafia/api/ContentApiController.java
check_grep 'rules uses RULES_MANAGE scope' 'ModeratorScope\.RULES_MANAGE' backend/src/main/java/com/mafia/classicmafia/api/ContentApiController.java
check_grep 'raw rules only returned to manager' 'manageable \? page\.getContent\(\) : null' backend/src/main/java/com/mafia/classicmafia/api/ContentApiController.java
check_grep 'rules editor narrows unknown mutation error before JSX render' 'error != null && <Status error>' frontend/src/content/RulesPage.tsx
if grep -R -Eq 'dangerouslySetInnerHTML' frontend/src; then bad 'React content rendering contains no raw HTML injection'; else ok 'React content rendering contains no raw HTML injection'; fi

# WP11-016 Moderator/admin console
for f in backend/src/main/java/com/mafia/classicmafia/api/ModeratorAdminApiController.java backend/src/test/java/com/mafia/classicmafia/ModeratorAdminApiIntegrationTests.java frontend/src/admin/ModeratorAdminPage.tsx frontend/src/api/moderators.ts; do check_file "$f"; done
check_grep 'admin moderator API canonical admin only' 'Role\.ADMIN.*"admin"|"admin".*Role\.ADMIN' backend/src/main/java/com/mafia/classicmafia/api/ModeratorAdminApiController.java
check_grep 'admin mutations reuse sensitive reauthentication' 'sensitiveActions\.verify' backend/src/main/java/com/mafia/classicmafia/api/ModeratorAdminApiController.java
check_grep 'admin defaults use canonical administration service' 'administration\.setDefaults' backend/src/main/java/com/mafia/classicmafia/api/ModeratorAdminApiController.java
check_grep 'admin overrides use canonical administration service' 'administration\.setOverrides' backend/src/main/java/com/mafia/classicmafia/api/ModeratorAdminApiController.java
check_grep 'admin assignment uses canonical administration service' 'administration\.assignModerator' backend/src/main/java/com/mafia/classicmafia/api/ModeratorAdminApiController.java
check_grep 'admin removal uses canonical administration service' 'administration\.removeModerator' backend/src/main/java/com/mafia/classicmafia/api/ModeratorAdminApiController.java
check_grep 'admin console requires password fields' 'current-password' frontend/src/admin/ModeratorAdminPage.tsx
check_grep 'admin console visible only for ADMIN in app navigation' "data\?\.role === 'ADMIN'" frontend/src/app/App.tsx
check_grep 'admin API tests isolate sensitive reauth IP' 'setRemoteAddr|isolatedIp' backend/src/test/java/com/mafia/classicmafia/ModeratorAdminApiIntegrationTests.java

# Cross-domain executable coverage and E2E
check_file backend/src/test/java/com/mafia/classicmafia/G3DomainApiIntegrationTests.java
check_grep 'G3 tests cover hidden game privacy' 'gamesApiUsesLegacyScoringVideoValidationAndHiddenVisibility' backend/src/test/java/com/mafia/classicmafia/G3DomainApiIntegrationTests.java
check_grep 'G3 tests cover schedule game/event participation' 'scheduleApiPreservesGameEventFieldsAndParticipation' backend/src/test/java/com/mafia/classicmafia/G3DomainApiIntegrationTests.java
check_grep 'G3 tests cover paid tournament invitations' 'paidTournamentRequiresInvitation' backend/src/test/java/com/mafia/classicmafia/G3DomainApiIntegrationTests.java
check_grep 'G3 tests cover public rating' 'ratingIsPublicAndUsesServerAggregate' backend/src/test/java/com/mafia/classicmafia/G3DomainApiIntegrationTests.java
check_grep 'G3 tests cover news/rules scopes/privacy' 'newsAndRulesKeepPublicReadAndScopedRawEditAccess' backend/src/test/java/com/mafia/classicmafia/G3DomainApiIntegrationTests.java
check_file frontend/e2e/g3.spec.ts
check_grep 'G3 E2E covers public migrated routes' 'anonymous domain routes render' frontend/e2e/g3.spec.ts
check_grep 'G3 E2E covers profile and admin console' 'canonical admin console render' frontend/e2e/g3.spec.ts
check_grep 'G3 E2E asserts responsive overflow' 'scrollWidth - document\.documentElement\.clientWidth' frontend/e2e/g3.spec.ts
check_grep 'G3 gate kills whole E2E process groups' 'kill -TERM -- "-\$pid"' ops/v11-g3-gate.sh
check_grep 'G3 gate preflights E2E ports' 'assert_port_free 5173' ops/v11-g3-gate.sh
check_grep 'G3 gate verifies launched Vite process is alive before readiness' 'if ! kill -0 "\$FRONTEND_PID"' ops/v11-g3-gate.sh
check_grep 'G3 gate verifies G3 Vite source fingerprint' "src/content/NewsPage\.tsx.*Новости" ops/v11-g3-gate.sh
check_grep 'Playwright dependency is frozen in package-lock' 'node_modules/@playwright/test' frontend/package-lock.json

# Security and architecture invariants
check_grep 'public G3 GETs explicitly whitelisted' '"/api/v1/games"' backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java
check_grep 'API catch-all remains authenticated' 'requestMatchers\("/api/v1/\*\*"\)\.authenticated' backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java
check_absent 'frontend does not introduce browser token storage' 'localStorage|sessionStorage|Bearer ' frontend/src/api/client.ts
check_absent 'frontend does not introduce JWT auth' 'jwt|JWT' frontend/src/api/client.ts
check_absent 'CSRF remains enabled' 'csrf\([^)]*disable|csrf\.disable' backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java
check_grep 'multipart request does not overwrite content type' 'instanceof FormData' frontend/src/api/client.ts

# Immutable migrations
declare -A expected=(
  [backend/src/main/resources/db/migration/V1__initial_schema.sql]='4a19c886e98bb8b4ee3cc28f9d86bf466582d2d7bd35bfc7e5462205e188de16'
  [backend/src/main/resources/db/migration/V2__production_indexes.sql]='b3875b08740a947f19dd70133ff4081ff0d7f4e6ab2058317008be07c75f1d31'
  [backend/src/main/resources/db/migration/V3__roles_moderator_scopes.sql]='b9f2be6f8341d78b915b9bf27ab71a229e810f59ea433936b97fa225b8f09ed8'
  [backend/src/main/resources/db/migration/V4__schedule_tournaments_profiles_video.sql]='7b2e2f192ff843d1b9bde533535b0aa24de5b46d319e1852c8c6c0dce99e071d'
)
for file in "${!expected[@]}"; do actual="$(sha256sum "$file" | awk '{print $1}')"; [[ "$actual" == "${expected[$file]}" ]] && ok "$file baseline hash" || bad "$file baseline hash"; done

printf '\nV11_G3_AUDIT_PASS=%d V11_G3_AUDIT_FAIL=%d\n' "$pass" "$fail"
(( fail == 0 ))
