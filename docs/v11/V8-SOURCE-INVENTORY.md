# GoMaf V8 source inventory

Scope: current V8 readiness source tree before packaging. Candidate SHA is intentionally recorded outside this file because embedding an archive hash inside the archive would be circular.

## Counts
- Java main source files: 162
- Java test source files: 30
- TypeScript/TSX files under frontend/src and frontend/e2e: 37
- Playwright spec files: 4
- Flyway migrations: 8
- Ops files: 53
- Spring Controller files: 14
- JPA repositories: 17
- Services: 24
- Entity package Java files: 25

## HTTP controller surface
- AuthApiController
- ContentApiController
- GamesApiController
- ModeratorAdminApiController
- PlayerAdministrationApiController
- PlayersApiController
- ProfileApiController
- RatingApiController
- ScheduleApiController
- TournamentsApiController
- ApiFallbackController
- CaptchaController
- InfrastructureController
- SpaController
## Production topology
- PostgreSQL 17.11 is internal-only and has no host port.
- db-role-bootstrap, db-migrate and db-finalize use separated DB roles.
- Runtime app uses a DML-only DB role with Flyway disabled.
- App exposes 8787 only on the internal backend network.
- Caddy is the only public service and publishes 80/tcp, 443/tcp and 443/udp.
- App and migration containers use read-only filesystems, cap_drop ALL and no-new-privileges.
- Caddy is read-only, drops capabilities and adds only NET_BIND_SERVICE.
- Backend Docker network is internal.

## Authentication and session security
- BCrypt cost 12.
- CSRF is enabled with XSRF-TOKEN / X-XSRF-TOKEN and XOR request handling.
- Session fixation protection changes the session ID.
- CredentialVersionFilter invalidates stale credential state.
- SecuritySessionFilter enforces tracked sessions.
- ADMIN remains MFA-pending until second factor completes.
- MFA credential is encrypted and recovery codes are one-time password hashes.
- V6 persists replay counters; V7 forces ADMIN MFA re-enrollment after the disclosed-seed incident.

## Production secret/config names
- POSTGRES_PASSWORD
- APP_DB_PASSWORD
- MIGRATION_DB_PASSWORD
- APP_ADMIN_PASSWORD
- APP_SECURITY_IP_HASH_SECRET
- APP_SECURITY_MFA_ENCRYPTION_KEY
- /etc/gomaf/production.env is required to remain root:root mode 0600.
## Release and deployment trust boundaries
- Incoming candidate is accepted only through root-owned release helpers.
- Candidate ZIP structure is validated by root-owned safe-zip.
- Internal RELEASE-SHA256SUMS is verified before use.
- RELEASE-CANDIDATE metadata binds sequence and expected current source hash.
- last_sequence rejects replay/downgrade.
- FINAL PREDEPLOY evidence is bound to candidate SHA, sequence and baseline.
- Approved candidate/evidence are root-owned mode 0600.
- Production deploy is a separate helper after PREDEPLOY.
- Release signing is intentionally disabled by owner policy; trust therefore relies on SSH/deployment controls plus archive hashes, baseline binding, root-owned helpers and anti-replay.

## Persistent data and logs
- PostgreSQL volume: gomaf_postgres_data.
- Caddy state/config use named Docker volumes.
- Application logs: /srv/gomaf-logs/app.
- Caddy logs: /srv/gomaf-logs/caddy.
- Backups: /srv/gomaf-backups.
- Release state: /var/lib/gomaf-release and /srv/gomaf/.deploy.

## Current migration chain
- V1 initial schema
- V2 production indexes
- V3 roles/moderator scopes
- V4 schedule/tournaments/profiles/video
- V5 security hardening
- V6 MFA replay protection
- V7 forced ADMIN MFA re-enrollment incident response
- V8 query-plan indexes