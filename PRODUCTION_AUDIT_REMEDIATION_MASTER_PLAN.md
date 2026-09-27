# GoMaf production audit and remediation master plan

Updated: 2026-09-22
Scope: production audit, remediation, performance, security, operations, final validation
Rule: no production deploy until the user explicitly approves mass deployment.
Authoritative work channel: GoMaf_Jenelle_Root_Secret MCP only.

## Status legend

DONE
Work completed and verified with tests or direct measurements.

IN PROGRESS
Work started, but final validation or remaining subitems still exist.

NOT STARTED
Work has not yet been completed in the current remediation cycle.

BLOCKED
Cannot be closed until a later integration or deployment step.

DEFERRED
Intentionally postponed to a later stage.

## Important baseline

Production runtime source hash:
a038a0109a20

Production image:
gomaf/app:20260922T103345Z-a038a0109a20

Active release:
20260922T103345Z-a038a0109a20

Current worktree hash at last verification:
2ce207d3c221

Production has not been redeployed during this remediation cycle.

Current Stage 3 candidate image exists only as a separate test candidate:
gomaf/app:stage3-candidate

Latest candidate backend verification visible in build log:
133 tests
0 failures
0 errors
1 skipped
BUILD SUCCESS

## Original detailed 12-stage roadmap

### Stage 1. Candidate deployment and measurement

Status: IN PROGRESS

Completed:
1. Existing production release and runtime hash identified.
2. Current worktree candidate can be built independently.
3. Separate Stage 3 candidate container exists for isolated testing.
4. Production baseline and candidate can be compared without replacing production.

Not completed:
1. No production candidate deployment has been performed.
2. Final production before and after measurement is blocked until explicit deployment approval.
3. Final rollback validation against the future mass deployment is not yet possible.

Mapping to current 5-stage structure:
Macro Stage 1 and Macro Stage 5.

### Stage 2. Performance baseline

Status: DONE for pre-deploy baseline, final post-change baseline remains BLOCKED until deployment.

Completed:
1. Lighthouse baseline collected on public routes.
2. Profile page identified as the main performance problem.
3. Profile baseline observed around Performance 64, Accessibility 100, Best Practices 100, SEO 100.
4. LCP around 5.9 seconds on the heavy profile baseline.
5. FCP around 1.8 seconds.
6. TBT around 520 ms.
7. Transfer around 1005 KiB.
8. News, players and schedule routes measured near high 90s performance.
9. Initial frontend entry size measured.
10. Route splitting verified.
11. Banner and avatar response sizes measured on real production data.
12. Cross-browser media behavior tested with Chromium, Firefox and WebKit.

Remaining:
1. Final Lighthouse comparison after all Stage 3 changes.
2. Final production measurement after deployment.

Mapping:
Macro Stage 1 and Macro Stage 3.

### Stage 3. Backend hot paths

Status: DONE

Completed:
1. Public players list query count measured.
2. Player profile query count measured.
3. Player history query count measured.
4. Rating query count measured.
5. Schedule query count measured.
6. Games list query count measured.
7. Game detail query count measured.
8. Tournament read paths measured.
9. N plus 1 risk on Game.tournament fixed using entity graph.
10. Game detail collection fetches reduced by scalar projections.
11. Tournament read path refactored toward projections.
12. Legacy avatar read path changed to public media projection.
13. Current small PostgreSQL dataset inspected with EXPLAIN ANALYZE on an isolated clone.
14. No new indexes were justified by current measured data.
15. pg_stat_statements was not enabled because that would require preload and restart without a measured need.
16. Full backend regression after Stage 2 changes passed.
17. Source, security and UI audits passed at Stage 2 closure.

Observed query counts after remediation:
players page 2
player profile 1
player history 3
rating 1
schedule range 2
games page with multiple tournaments 2
game detail 3
tournaments list 1

Mapping:
Macro Stage 2.

### Stage 4. Media caching and media delivery

Status: DONE

Completed:
1. Legacy avatar endpoint uses public-media projection instead of loading the full User/media object.
2. Valid public media uses validator caching with strong ETags and conditional 304 responses.
3. Profile banner and avatar resize paths were measured on real production data.
4. Heavy opaque PNG representations are transcoded to JPEG quality 0.90 only when safe; transparency-preserving PNG and GIF behavior remain covered by tests.
5. Response Content-Type follows the actual public representation.
6. Media representation versioning was bumped so old validators cannot alias new bytes.
7. Real exact-candidate banner bytes for player 9 were verified: 640=48247 B, 960=87696 B, 1280=133161 B.
8. The prior 1280 opaque PNG was 1317528 B, so the verified 1280 candidate representation is about 90 percent smaller.
9. Exact-candidate avatar representations were verified as JPEG for opaque sources; player 9 measured 9214 B at 128, 26044 B at 256, and 68496 B at 512.
10. Conditional GET on the banner returns 304 with the same strong ETag.
11. ProfileBannerService tests pass, AvatarService has 16 passing tests, and the complete backend build passes.
12. Cross-browser delivery was verified in Chromium, Firefox and WebKit.

Mapping:
Macro Stage 3.

### Stage 5. Frontend loading

Status: DONE

Completed:
1. Frontend architecture, route splitting, Query cache settings, bundle sizes and profile critical resources were inspected.
2. Profile banner preload was changed from three media-specific preloads to one responsive imagesrcset/imagesizes preload matching the later img srcset/sizes.
3. Same-origin browser tests verified Chromium, Firefox and WebKit at mobile, tablet and desktop with DPR 1 and DPR 2.
4. The WebKit duplicate 1280 banner fetch was eliminated; the final matrix is 18 PASS, 0 FAIL.
5. Public PlayerProfile administration UI was split into a lazy PlayerAdministrationPanel, so anonymous profile traffic does not load React Hook Form, Zod/resolver or the administration chunk.
6. Exact network probes verified that the public profile loads the main bundle plus the small public profile/player chunks and does not load the administration/form stack.
7. The custom Inter variable font was confirmed to transfer 352240 B on the profile route.
8. A broad Inter subset experiment reduced it only to 248276 B and would require proper Reserved Font Name handling; the self-hosted original Inter was therefore intentionally retained rather than shipping an unsafe derivative.
9. Responsive source-size hints were corrected to the measured mobile layout widths: up to 640 px uses calc(100vw - 26px), 641-720 uses calc(100vw - 42px), then 960 px and 1280 px.
10. After the sizes correction, mobile DPR 2 selects the 960 representation in Chromium, Firefox and WebKit instead of 1280 while keeping one request.
11. Frontend lint and typecheck pass, Vitest is 57/57, and source/dist public-surface audits pass.
12. Final public PlayerProfilePage route chunk is about 5.9 KB raw; administration is a separate lazy chunk.
13. Three-run Lighthouse median improved from baseline Performance 59 to 71.
14. Median LCP improved from 7037.5 ms to 5424.6 ms.
15. Median FCP improved from 3122.8 ms to 2751.5 ms.
16. Median TBT improved from 443 ms to 237 ms.
17. Median transfer reduced from 1326738 B to 811906 B.
18. Accessibility stayed 100, SEO stayed 100, and CLS stayed 0 in the comparable Lighthouse runs.

Mapping:
Macro Stage 3.

### Stage 6. SEO and navigation

Status: IN PROGRESS

Completed:
1. Unknown browser route behavior was audited.
2. Unknown browser route changed from redirect to direct 404 semantics in the worktree.
3. 404 response is HTML with no-store.
4. 404 response includes noindex and nofollow.
5. Integration test added and passed for direct 404 behavior.
6. Existing metadata and canonical handling inspected.
7. Profile banner preload is injected by server-side SPA metadata handling.

Still required:
1. Final crawler pass over public routes.
2. Revalidate canonical URLs.
3. Revalidate robots directives.
4. Revalidate sitemap if applicable.
5. Recheck route status code matrix after all code settles.
6. Confirm no indexable error or private routes.

Mapping:
Macro Stage 4.

### Stage 7. UI stabilization

Status: IN PROGRESS

Completed:
1. UI and UX audit passed at Stage 1 closure.
2. UI and UX audit passed at Stage 2 closure.
3. Public profile responsive rendering has been exercised during browser tests.

Still required:
1. Final responsive pass after Stage 3 media changes.
2. Recheck layout shift.
3. Recheck loading and fallback states.
4. Recheck keyboard and focus behavior on changed routes.
5. Final accessibility pass after all frontend changes.

Mapping:
Macro Stage 4.

### Stage 8. Visual regression

Status: NOT STARTED as a complete final gate

Partial work already available:
1. Multiple browser screenshots and rendering observations were made during diagnostics.
2. Lighthouse and Playwright exercised public pages.

Still required:
1. Defined viewport matrix.
2. Screenshot comparison for critical public routes.
3. Profile routes with and without custom banner and avatar.
4. Admin and authenticated routes where feasible.
5. Chromium, Firefox and WebKit final comparison.
6. Record accepted visual differences caused by image representation changes.

Mapping:
Macro Stage 4 and Macro Stage 5.

### Stage 9. DAST and active security verification

Status: NOT STARTED as the final full cycle

Completed earlier as supporting work:
1. Security audit script passed with 104 PASS and 0 FAIL at Stage 2 closure.
2. Authorization integration tests exist and pass.
3. CSRF integration coverage exists.
4. API fuzz and boundary tests exist.
5. IDOR-sensitive domain paths have integration coverage.

Still required:
1. Final DAST-style pass against the isolated candidate.
2. Recheck IDOR cases on player, game, tournament and admin resources.
3. Recheck CSRF on state-changing browser flows.
4. Recheck security headers.
5. Recheck unauthenticated and wrong-role behavior.
6. Recheck rate limiting behavior where safe to test.
7. Final security audit on exact release candidate.

Mapping:
Macro Stage 4.

### Stage 10. Retention and operational hygiene

Status: NOT STARTED in final form

Known issue already found:
1. AIDE currently reports no differences.
2. AIDE database modification time was newer than a successful systemd check.
3. No verified automatic init or update source was identified.
4. Therefore AIDE baseline provenance is not proven and must not be described as properly managed yet.

Still required:
1. Define and verify AIDE baseline update procedure.
2. Verify log retention and rotation.
3. Verify backup retention.
4. Verify old release retention.
5. Verify temporary audit resources are removed.
6. Verify disk growth controls.
7. Verify scheduled operational jobs.
8. Verify alerting or failure visibility for critical scheduled jobs.

Mapping:
Macro Stage 4.

### Stage 11. Disaster recovery

Status: NOT STARTED as a full exercise

Existing supporting evidence:
1. Production deployment metadata tracks current and previous release and image.
2. Pre-deploy and successful backup pointers exist.
3. Isolated database restore was used successfully for Stage 2 measurements.

Still required:
1. Verify exact backup artifact integrity.
2. Perform controlled restore rehearsal from the selected production backup.
3. Verify application startup against restored database in an isolated environment.
4. Verify rollback procedure for application release.
5. Verify rollback procedure for database-affecting deployment.
6. Measure and document recovery sequence.
7. Verify secrets and environment dependencies required for recovery without exposing secret values.

Mapping:
Macro Stage 4 and Macro Stage 5.

### Stage 12. Redesign and final polish

Status: NOT STARTED as a separate redesign phase

Important rule:
Do not perform cosmetic redesign merely to change appearance. Only make redesign changes supported by measured UX, accessibility, performance or consistency problems.

Potential scope after technical closure:
1. Resolve remaining profile-page performance-driven visual implementation issues.
2. Normalize media fallbacks.
3. Improve consistency only where audits identify a concrete defect.
4. Final polish must not invalidate accessibility, SEO or performance gains.

Mapping:
Macro Stage 5.

## Current grouped 5-stage execution plan

This is the later condensed plan created after the user explicitly asked to divide the remaining work into five stages.

### Macro Stage 1. Re-baseline and close the original audit

Status: DONE except AIDE provenance intentionally carried into operations.

Completed:
1. Production release and hashes rechecked.
2. Health and audits rerun.
3. Direct unknown route behavior corrected in worktree.
4. Source hash locale dependence fixed by forcing LC_ALL=C for sorting.
5. Locale-independent worktree hash behavior verified.
6. Full backend verification passed at closure.
7. Lighthouse and media baseline refreshed.
8. Frontend bundles, fonts and route splitting inspected.

Deferred to Macro Stage 4:
AIDE baseline provenance and update procedure.

### Macro Stage 2. Backend and PostgreSQL hot paths

Status: DONE

Completed:
1. Query-count probes.
2. PostgreSQL isolated clone.
3. EXPLAIN ANALYZE on relevant query shapes.
4. N plus 1 remediation.
5. Game detail scalar read refactor.
6. Tournament read projection refactor.
7. Legacy avatar read optimization.
8. Cache validators for legacy avatar.
9. Full regression.
10. Source, UI and security audits.
11. Temporary database resources cleaned after Stage 2.

### Macro Stage 3. Frontend, media and performance

Status: DONE

Closure source hash:
4bd4f367b588

Exact candidate image:
gomaf/app:stage3-candidate-4bd4f367b588

Completed:
1. Frontend baseline and bundle analysis.
2. Real production media measurements.
3. Opaque PNG banner and avatar representation optimization.
4. Strong ETag and conditional GET verification.
5. Responsive image preload and img source selection aligned.
6. WebKit duplicate banner request eliminated.
7. Same-origin Chromium, Firefox and WebKit matrix completed at mobile/tablet/desktop, DPR 1 and DPR 2: 18 PASS, 0 FAIL.
8. Public player administration form stack split into a lazy chunk and verified absent from anonymous profile requests.
9. Inter font transfer measured; unsafe/low-value derivative subsetting intentionally not shipped.
10. Final responsive sizes corrected from actual measured layout widths and reverified cross-browser.
11. Frontend lint PASS, typecheck PASS, Vitest 57/57.
12. Backend Maven verify: 135 tests, 0 failures, 0 errors, 1 expected skip, BUILD SUCCESS.
13. Source audit: 241 PASS, 0 FAIL.
14. UI/UX audit: PASS.
15. Security audit: 104 PASS, 0 FAIL.
16. V11 G2 audit: 60 PASS, 0 FAIL.
17. Final Lighthouse three-run median versus baseline:
   Performance 59 -> 71
   LCP 7037.5 ms -> 5424.6 ms
   FCP 3122.8 ms -> 2751.5 ms
   TBT 443 ms -> 237 ms
   Transfer 1326738 B -> 811906 B
   Accessibility 100 -> 100
   SEO 100 -> 100
   CLS 0 -> 0
18. Production was not deployed and remains on runtime source hash a038a0109a20.

Remaining Stage 3 work:
None. Temporary validation infrastructure is cleaned after evidence capture.

### Macro Stage 4. SEO, UX, security and operations

Status: DONE

Closure worktree hash:
97c5ea0add07

Production during closure:
a038a0109a20, unchanged and not deployed.

Completed:
1. SEO crawler, sitemap, canonical, robots and error-route status matrix verified on the Stage 4 runtime candidate.
2. Public sitemap matrix: 37/37 valid; public routes return 200 with self-canonical/index-follow where applicable.
3. Missing player/game/tournament and unknown browser routes return real 404 with no-store and noindex/nofollow.
4. Private/auth/editor surfaces are noindex and do not expose inappropriate canonical URLs.
5. Accessibility/responsive UX matrix covered 10 routes x 3 viewports x 3 browser engines: no serious/critical Axe violations, no page errors, no failed network requests and no horizontal overflow.
6. Visual regression anomaly on the long /rules page was isolated to Playwright fullPage rendering; six ordinary viewport comparisons from top through deep-scroll positions were pixel-identical. Dynamic CAPTCHA and intentionally re-encoded media explain the remaining expected diffs.
7. Current-source light gates: source audit 242 PASS / 0 FAIL; WP11-023 security audit 104 PASS / 0 FAIL; V11 G2 audit 60 PASS / 0 FAIL.
8. Focused backend security suite: 43 tests, 0 failures, 0 errors, 0 skips.
9. Active isolated HTTP DAST: 44 PASS / 0 FAIL, covering CSRF, 401/403 authorization boundaries, moderator target restrictions, PLAYER denial, ADMIN mutations, sensitive reauthentication, stale-session invalidation, security headers/cookie attributes and login rate limiting to HTTP 429.
10. AIDE helper/timer configuration and reviewed baseline procedure were repaired and verified; canonical systemd AIDE check passes.
11. Backup/release/log retention and scheduled jobs were verified; all operational jobs report success, release retention is exactly five, fresh backup checksum is valid, disk remains well above safety floors.
12. Disaster-recovery rehearsal restored the fresh checksum-verified logical backup into an isolated bounded PostgreSQL clone, passed PostgreSQL concurrency regression, validated migration/runtime role separation, validated candidate migration/runtime health, and validated the actual production rollback image against the same clone.
13. All temporary Stage 4 containers, networks and volumes were removed; post-cleanup resource preflight passed.
14. The only source-tree change after the f61b536f49b6 Stage 4 runtime image was frontend/e2e/g4.spec.ts updating the browser-route contract to the already-implemented direct 404 behavior; runtime code did not change.
15. Evidence:
    - .audit/stage4-security-97c5ea0add07
    - .audit/stage4-ops-97c5ea0add07
    - .audit/stage4-dr-97c5ea0add07
    - .audit/stage4-closure-97c5ea0add07

### Macro Stage 5. Final integration

Status: PREDEPLOY GATES COMPLETE; READY FOR USER REVIEW; DEPLOYMENT NOT AUTHORIZED

Predeploy completion evidence:
- Candidate source hash: 97c5ea0add07
- Candidate image ID: sha256:cc60143b029bd9d80fc868cf8603b096e3f8928189320bc37119c33c19604178
- Immutable snapshot: /srv/gomaf-candidates/stage5-97c5ea0add07
- Frontend: 57/57 PASS
- Backend: 137 tests, 0 failures, 0 errors, 1 skipped
- Source audit: 242/0
- Security audit: 104/0
- G2 audit: 60/0
- Final browser matrix: 36/0
- Stage 4 focused security: 43/0
- Stage 4 active HTTP DAST: 44/0
- DR restore/migration/rollback: PASS
- Current production audit after candidate validation: PASS
- Final predeploy review package: /srv/gomaf/.audit/stage5-exact-97c5ea0add07

Required final gate:
1. Freeze exact candidate source tree.
2. Record deterministic source hash.
3. Full backend tests.
4. Full frontend tests.
5. Source audit.
6. UI and UX audit.
7. Security audit.
8. Production audit against the candidate environment where applicable.
9. Final Lighthouse and browser matrix.
10. Final database migration and compatibility review.
11. Final backup verification.
12. Rollback plan.
13. Release notes and change inventory.
14. User review of final evidence.
15. Only after explicit user approval, perform the mass production deployment.
16. Post-deploy health checks.
17. Post-deploy smoke tests.
18. Post-deploy performance comparison.
19. Post-deploy security and status-code smoke checks.
20. Verify rollback remains immediately available.

## Completed code changes already present in current remediation worktree

1. Direct 404 handling for unknown browser routes.
2. Deterministic LC_ALL=C source hashing.
3. Game repository tournament entity graph for public list.
4. Game detail scalar projection refactor.
5. Tournament read projections and scalar viewer-state reads.
6. Legacy avatar public-media projection.
7. Legacy avatar validator caching.
8. Profile banner opaque PNG to JPEG public representation conversion.
9. Avatar opaque PNG to JPEG public representation conversion.
10. Dynamic response media types and versioned validators for converted media.
11. Responsive profile banner preload with matching srcset/sizes.
12. Lazy PlayerAdministrationPanel split so public profiles do not load the administration/form stack.
13. Measured mobile source-size hints for responsive banner selection.

## Important unresolved or unverified items

1. User review of the final exact-candidate evidence.
2. Mass production deployment, only after explicit user approval.
3. Post-deploy health, smoke, performance, security/status and rollback-readiness verification.

Resolved Stage 5 predeploy items:
1. Exact immutable candidate source snapshot and deterministic source hash.
2. Exact immutable candidate image.
3. Full frontend/backend regression on the exact build.
4. Source/UI/security/G2 audits on the exact snapshot.
5. Exact browser matrix, Lighthouse and status/security smoke.
6. Database migration/compatibility and isolated disaster-recovery/rollback rehearsal.
7. Verified backup mechanism and current checksum-valid backup.
8. Release inventory, release notes, rollback plan and checksum-bound predeploy review package.
9. Temporary Stage 5 runtime resources cleaned after evidence capture.

Resolved operational item:
AIDE baseline provenance/update procedure was closed on 2026-09-23 by reviewed manual promotion with the old database, reviewed diffs, pre/post SHA-256 values, rollback copy and successful post-promotion systemd AIDE check archived under .audit/stability-20260923/aide-baseline-promotion.

## Stability incident and host rehabilitation 2026-09-23

Status: REHABILITATION VERIFIED; HOST STABLE; RESOURCE GUARDS MANDATORY FOR HEAVY WORK

Confirmed:
1. Macro Stage 4 validation overloaded the 4 GiB host while several temporary Java/PostgreSQL/browser resources existed concurrently without hard test-workload limits.
2. Previous-boot journal records memory pressure, Docker health-check timeouts, containerd deadline failures, and loss of RDC heartbeat before the host became unreachable.
3. No retained kernel OOM-kill line was found, so an exact OOM victim cannot be claimed.
4. Production was not deployed during the incident and remains on runtime source hash a038a0109a20.
5. After reboot, production app/database/Caddy are healthy and public routes return HTTP 200.

Host rehabilitation:
1. All Stage 3/4 temporary containers, networks, and clone volumes were removed.
2. 2 GiB emergency swap is enabled with vm.swappiness=10.
3. gomaf-test-memory-guard.service protects the host by stopping only gomaf-stageN-* test containers after sustained MemAvailable below 1 GiB.
4. gomaf-testing.slice caps aggregate test workloads at MemoryHigh=1280M, MemoryMax=1536M, CPUQuota=150%.
5. /usr/local/sbin/gomaf-test-preflight is mandatory before heavy validation.
6. /usr/local/sbin/gomaf-safe-test-run is mandatory for temporary stage containers unless equivalent explicit cgroup/memory/CPU limits are demonstrated.
7. Primary GoMaf MCP and RDC are protected with OOMScoreAdjust=-400 and 768 MiB hard memory caps.
8. The secret-link GoMaf MCP is protected with OOMScoreAdjust=-400 while retaining its existing 768 MiB MemoryHigh / 1536 MiB MemoryMax because its observed memory was predominantly reclaimable file cache rather than Node heap.
9. Maven/full browser/Lighthouse workloads must run serially, not concurrently.
10. Fresh daily backup after rehabilitation is checksum-valid.
11. Canonical AIDE helper/timer configuration was unified and its reviewed baseline was manually promoted with rollback evidence; post-promotion AIDE returns no differences.
12. Full post-rehabilitation gate and 81-second soak passed with production healthy, control plane active, low load, zero Stage test resources and no failed systemd units.
13. Evidence is stored under /srv/gomaf/.audit/stability-20260923.

## Production safety boundary

Production must remain on the current release until the user explicitly authorizes the mass deployment.

Do not deploy individual Stage 3 changes separately.

Do not modify production database state for diagnostics when an isolated clone can be used.

Do not expose secrets in logs, notes or command output.

Use GoMaf_Jenelle_Root_Secret MCP as the primary host channel. RDC is also authorized by the user for recovery, stability work, and host operations when needed.

## Resume point

Current resume point is final user review of the exact Stage 5 predeploy evidence.

Exact candidate:
- source hash: 97c5ea0add07
- image: gomaf/app:stage5-candidate-97c5ea0add07
- immutable snapshot: /srv/gomaf-candidates/stage5-97c5ea0add07
- evidence: /srv/gomaf/.audit/stage5-exact-97c5ea0add07

Predeploy status:
1. Build/regression/audit/browser/Lighthouse/security/DR/rollback/backup/release-inventory gates are complete.
2. Stage 5 temporary containers, networks and clone volume are removed.
3. Production remains on a038a0109a20 and was not mutated by Stage 5 validation.
4. Deployment is blocked until explicit user approval.
5. At deployment time, run the mandatory resource preflight and create/verify a fresh predeploy backup before any production mutation.
6. After deployment, run the full post-deploy health, smoke, performance, security/status and rollback-readiness cycle.

Do not deploy without explicit user approval.
