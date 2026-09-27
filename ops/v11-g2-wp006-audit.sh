#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

pass=0
fail=0
ok() { printf 'PASS  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf 'FAIL  %s\n' "$1"; fail=$((fail+1)); }
check_file() { [[ -f "$1" ]] && ok "$1 exists" || bad "$1 exists"; }
check_grep() { local label="$1" pattern="$2" file="$3"; grep -Eq "$pattern" "$file" && ok "$label" || bad "$label"; }
check_absent() { local label="$1" pattern="$2" file="$3"; if grep -Eq "$pattern" "$file"; then bad "$label"; else ok "$label"; fi; }

check_file backend/src/main/java/com/mafia/classicmafia/api/PlayersApiController.java
check_file backend/src/main/java/com/mafia/classicmafia/service/PlayerReadService.java
check_file backend/src/main/java/com/mafia/classicmafia/api/dto/PlayerListItemResponse.java
check_file backend/src/main/java/com/mafia/classicmafia/api/dto/PlayerProfileResponse.java
check_file backend/src/main/java/com/mafia/classicmafia/api/dto/PlayerGameResponse.java
check_file backend/src/test/java/com/mafia/classicmafia/PlayersReadApiIntegrationTests.java

check_grep 'public players list endpoint' '@GetMapping' backend/src/main/java/com/mafia/classicmafia/api/PlayersApiController.java
check_grep 'players API base path' '@RequestMapping\("/api/v1/players"\)' backend/src/main/java/com/mafia/classicmafia/api/PlayersApiController.java
check_grep 'profile endpoint' '@GetMapping\("/\{id\}"\)' backend/src/main/java/com/mafia/classicmafia/api/PlayersApiController.java
check_grep 'history endpoint' '@GetMapping\("/\{id\}/games"\)' backend/src/main/java/com/mafia/classicmafia/api/PlayersApiController.java
check_grep 'avatar endpoint' '@GetMapping\("/\{id\}/avatar"\)' backend/src/main/java/com/mafia/classicmafia/api/PlayersApiController.java

check_grep 'public role whitelist excludes ADMIN' 'PUBLIC_ROLES = List\.of\(Role\.PLAYER, Role\.MODERATOR\)' backend/src/main/java/com/mafia/classicmafia/service/PlayerReadService.java
check_grep 'profile lookup enforces public-player role' 'getRole\(\)\.isPublicPlayer\(\)' backend/src/main/java/com/mafia/classicmafia/service/PlayerReadService.java
check_grep 'history reuses central visibility policy' 'gameService\.visibleHistory' backend/src/main/java/com/mafia/classicmafia/service/PlayerReadService.java
check_grep 'players search only public roles' 'findAllByRoleInAndUsernameContainingIgnoreCase\(PUBLIC_ROLES' backend/src/main/java/com/mafia/classicmafia/service/PlayerReadService.java
check_grep 'sort is explicit whitelist' 'case "rating,desc"' backend/src/main/java/com/mafia/classicmafia/service/PlayerReadService.java
check_absent 'sort does not accept arbitrary JPA property' 'Sort\.by\([^\n]*rawSort|Sort\.by\([^\n]*sort\)' backend/src/main/java/com/mafia/classicmafia/service/PlayerReadService.java

check_absent 'public list DTO does not disclose role' 'String role' backend/src/main/java/com/mafia/classicmafia/api/dto/PlayerListItemResponse.java
check_absent 'public profile DTO does not disclose role' 'String role' backend/src/main/java/com/mafia/classicmafia/api/dto/PlayerProfileResponse.java
check_absent 'public DTOs do not disclose password/security versions/IP hash' 'password|credentialVersion|authorizationVersion|registrationIpHash' backend/src/main/java/com/mafia/classicmafia/api/dto/PlayerListItemResponse.java
check_absent 'public profile DTO omits password/security versions/IP hash' 'password|credentialVersion|authorizationVersion|registrationIpHash' backend/src/main/java/com/mafia/classicmafia/api/dto/PlayerProfileResponse.java

check_grep 'anonymous Players API GETs are permitted' '"/api/v1/players"' backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java
check_grep 'anonymous player games GET is permitted' '"/api/v1/players/\*/games"' backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java
check_grep 'anonymous avatar GET is permitted' '"/api/v1/players/\*/avatar"' backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java
check_grep 'remaining API stays authenticated' 'requestMatchers\("/api/v1/\*\*"\)\.authenticated\(\)' backend/src/main/java/com/mafia/classicmafia/config/SecurityConfig.java

check_grep 'tests cover admin non-disclosure' 'PLAYER_NOT_FOUND' backend/src/test/java/com/mafia/classicmafia/PlayersReadApiIntegrationTests.java
check_grep 'tests cover hidden-game visibility' 'hidden game' backend/src/test/java/com/mafia/classicmafia/PlayersReadApiIntegrationTests.java
check_grep 'tests cover avatar binary resource' 'content\(\)\.bytes' backend/src/test/java/com/mafia/classicmafia/PlayersReadApiIntegrationTests.java
check_grep 'tests cover invalid sort' 'SORT_INVALID' backend/src/test/java/com/mafia/classicmafia/PlayersReadApiIntegrationTests.java
check_grep 'tests assert moderator role is not publicly exposed' '\$\.items\[0\]\.role.*doesNotExist' backend/src/test/java/com/mafia/classicmafia/PlayersReadApiIntegrationTests.java

# G0/G1 immutable DB baseline must remain unchanged.
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

printf '\nWP11_006_AUDIT_PASS=%d WP11_006_AUDIT_FAIL=%d\n' "$pass" "$fail"
(( fail == 0 ))
