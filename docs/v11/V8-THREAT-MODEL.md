# GoMaf V8 threat model

Scope: current V8 readiness source tree, public web app, ADMIN/MODERATOR privileges, PostgreSQL, uploads, logs/backups, host and release chain.

## Assets
- Player accounts, credentials and tracked sessions.
- Single canonical ADMIN account and MFA state.
- Moderator scopes and privileged mutations.
- Games, ratings, schedule, tournaments, profiles and video links.
- PostgreSQL data and Flyway history.
- Production secrets in /etc/gomaf/production.env.
- Backups, audit/application logs and release metadata.
- Approved candidate and anti-replay sequence.

## Actors
- Anonymous Internet user.
- Authenticated PLAYER.
- MODERATOR with individually granted scopes.
- Canonical ADMIN with password plus MFA.
- Restricted deployment identity mafia.
- Host administrator opsadmin/root.
- Attacker with stolen account, session or deployment credentials.

## Entry points
- Caddy on public 80/443.
- Public APIs for players, games, schedule, tournaments, rating, news and rules.
- Login, registration and ADMIN MFA APIs.
- Profile and administration APIs.
- CAPTCHA and avatar upload/read paths.
- SSH for opsadmin and restricted mafia identities.
- Candidate ZIP entering /srv/gomaf-incoming.- Backup/restore and migration operations executed by root-owned helpers.

## Trust boundaries
1. Internet -> Caddy.
2. Caddy -> internal Spring app.
3. Spring runtime -> PostgreSQL through DML-only role.
4. Migration jobs -> PostgreSQL through separate migration role.
5. Browser session -> privileged ADMIN/MODERATOR API.
6. Uploaded bytes -> image decoder -> canonical stored PNG.
7. mafia incoming files -> root-owned release verification.
8. Root-owned release state -> immutable production release/runtime pointers.
9. Production secrets -> containers through environment variables.
10. Backup archives -> restore staging DB -> production database swap.

## Threats and mitigations
- Credential stuffing/brute force: DB-backed bounded rate limiter, CAPTCHA/login gates and 429 handling.
- Session theft/fixation: Secure production cookie, SameSite=Lax, changeSessionId, tracked-session and version filters.
- CSRF: CookieCsrfTokenRepository, X-XSRF-TOKEN header and CSRF enabled on mutations.
- ADMIN password compromise: MFA required before ADMIN authentication completes.
- TOTP replay: persistent counter, pessimistic locking and bounded same-session reuse.
- Disclosed ADMIN MFA seed: V7 removes credential, recovery codes and sessions and bumps security versions.
- Broken authorization: server-side role/scope checks, moderator target restrictions and authorization-matrix regression tests.
- API error leakage: stable external code/requestId; unexpected internal details stay server-side.
- XSS/content injection: self-only CSP scripts/styles, React escaping, URL scheme checks and canonical image re-encoding.
- Malicious avatar/polyglot: size/dimension/pixel limits, decoder validation, metadata removal and canonical PNG rewrite.
- SQL/schema takeover: parameterized repository/JPA access; runtime DB role cannot CREATE or read Flyway history.- Container breakout: non-root app, read-only filesystems, cap_drop ALL, no-new-privileges and internal network.
- DB/network exposure: PostgreSQL and app have no host ports; Caddy is the only public container.
- Release ZIP attacks: bounded safe-ZIP validation/extraction plus internal SHA-256 manifest.
- Stale/wrong release: expected-current-source binding and monotonic sequence.
- Candidate substitution after PREDEPLOY: approved SHA/sequence/baseline evidence is root-owned and revalidated by deploy helper.
- Failed deployment state drift: rollback restores original app/caddy/db running states; DB OFF/ON dynamic harness passes.
- Backup corruption: logical dump restore is validated on isolated PostgreSQL before reliance.
- Secret disclosure in logs: dedicated redacting export and root-only production.env.
- SSH password/root compromise: root/password/keyboard auth disabled; publickey required; AllowUsers restricted.
- Query-scale degradation: V8 adds indexes for player ranking, public games, tournament games, news and tournament ordering; plans were checked on a production-data clone.
- Browser/layout regressions: Chromium exhaustive responsive matrix plus Firefox/WebKit representative smoke, Axe A/AA and keyboard-focus gates.

## Residual risks
- Release signing is intentionally disabled by owner policy. Compromise of an authorized deployment path therefore has a larger trust impact than with an offline signing key.
- opsadmin has unrestricted sudo and remains a high-value administrative identity by design.
- V7 invalidates the previously exposed ADMIN MFA material only when V7/V8 is actually deployed to production. Production remains frozen until explicit launch approval.

## Validation status before packaging
- Backend regression suite: 97 tests PASS.
- Frontend unit/integration suite: 29 tests PASS.
- API contract and fuzz regressions cover malformed types/JSON, unsupported method/media type, protected-route anonymous access and prior uncovered endpoints.
- Avatar regressions include zero-byte, fake/polyglot, size/dimension/pixel limits and EXIF stripping.
- V5 -> V8 rehearsal on verified frozen production dump PASS; candidate and current rollback image healthy on V8 clone.
- Browser suite: 120 cases, 83 PASS, 37 configured skips, 0 FAIL.
- Manual visual review: 200 screenshots grouped into 20 contact sheets, no product-level visual defect observed.
- Source audit: 212 PASS, 0 FAIL. Security audit: 88 PASS, 0 FAIL.

## Production readiness caveat
Production deploy has not occurred. Production MFA incident reset, production schema V8 and post-deploy production audit remain intentionally pending until explicit launch authorization.