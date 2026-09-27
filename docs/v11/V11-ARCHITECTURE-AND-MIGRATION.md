# GoMaf v11 Architecture and Migration Specification

Version: 0.1
Status: ACCEPTED BASELINE FOR IMPLEMENTATION
Baseline: production source of truth v10.2.1

## 1. Objective

GoMaf v11 changes the product from a primarily server-rendered Thymeleaf site into a modern single-origin web application while preserving the proven Java backend, PostgreSQL schema lineage, security invariants, and fail-closed deployment pipeline.

This is not a rewrite of the business layer and not a microservice migration.

Target architecture:

```text
Browser
  |
  | HTTPS, same origin
  v
Caddy
  |
  v
GoMaf app container
  |-- React + TypeScript production assets
  |-- Spring Boot REST API (/api/v1)
  |-- Spring Security session/auth/CSRF
  |
  v
PostgreSQL
```

Development architecture:

```text
Vite dev server
  | browser UI + HMR
  | same product contracts
  v
Spring Boot development backend
  |
  v
PostgreSQL / isolated development DB
```

The exact development proxy arrangement is implementation detail, but production remains same-origin and does not require CORS.

## 2. Non-goals

v11 must not introduce the following without a separate architectural decision:

- microservices;
- Kubernetes;
- a production Node.js web server;
- JWT stored in localStorage/sessionStorage;
- frontend authorization as a security boundary;
- rewriting PostgreSQL/Flyway history;
- replacing Spring Boot merely because the frontend changes;
- big-bang removal of v10 MVC before replacement paths are proven;
- loosening deployment rollback/audit guarantees for convenience.

## 3. Target technology stack

### Backend retained

- Java 21.
- Spring Boot.
- Spring Security.
- Spring Data JPA.
- Bean Validation.
- PostgreSQL.
- Flyway.

### New frontend

- React.
- TypeScript with strict type checking.
- Vite.
- React Router.
- TanStack Query for server-state fetching/cache/invalidation.
- React Hook Form for forms.
- Zod for client-side schema validation where useful.

Client validation is UX only. The backend remains authoritative and repeats all security/business validation.

### Testing

- existing JUnit/Spring tests retained and expanded;
- frontend unit/component tests with Vitest and React Testing Library;
- Playwright for browser-level E2E and viewport checks;
- production source/ops audits remain shell/Python based where appropriate.

## 4. Repository layout

Target layout after migration settles:

```text
/
  backend/
    pom.xml
    src/main/java/
    src/main/resources/
    src/test/

  frontend/
    package.json
    package-lock.json
    tsconfig*.json
    vite.config.*
    src/
    public/

  ops/
  docker/
  caddy/
  config/
  compose.prod.yaml
  Dockerfile
```

Migration rule: moving the existing backend tree into `backend/` is a mechanical work package and must not be combined with behavioral changes. Until that work package passes all existing backend tests/source audits, no API behavior changes are allowed in the same package.

## 5. Backend modular-monolith boundaries

The application remains one Spring Boot process, but new code is organized by product domain rather than one global controller/entity/service folder.

Target domains:

```text
auth
users
profiles
games
schedule
tournaments
ratings
news
rules
moderation
audit
platform
```

Each domain may contain API/controller, DTO, service/application logic, repository access and mapping code. Existing entities/repositories can be moved gradually; package reshuffling is not required for the first vertical slice.

No domain may expose JPA entities directly as public API responses.

## 6. API boundary

All new product API endpoints live under:

```text
/api/v1
```

Rules:

- JSON for normal API requests/responses;
- binary avatar/captcha resources may retain appropriate media responses;
- API DTOs are explicit and versioned by the `/api/v1` contract, not by JPA shape;
- validation errors use one canonical JSON structure;
- pagination is server-side;
- sorting/filtering parameters are allow-listed;
- hidden/private game authorization is enforced in services/repositories, never only in React;
- ADMIN hiding/public-statistics rules remain server-side;
- sensitive moderator/admin operations preserve re-authentication/current-password requirements where the v10 operation already requires them;
- audit logging remains backend-owned.

## 7. Authentication/session design

v11 retains server-side session authentication.

Hard rules:

- `MAFIASESSION` stays HttpOnly;
- production session cookie stays Secure;
- no auth token in localStorage or sessionStorage;
- login/logout remain Spring Security controlled;
- authorization version/credential version session invalidation remains;
- account roles remain `PLAYER`, `MODERATOR`, `ADMIN`;
- exactly one logical ADMIN and reserved `admin` identity remain invariants;
- moderator scope behavior remains backend-enforced.

For the SPA, authentication responses become API-friendly rather than redirect-dependent. The implementation may use Spring Security handlers/filter integration, but it must not reimplement password verification outside Spring Security.

## 8. CSRF design

CSRF stays enabled.

The SPA receives a CSRF token through an explicit Spring Security-supported mechanism and sends it in a request header on state-changing requests. The CSRF token may be JavaScript-readable because it is not an authentication secret; the session cookie remains HttpOnly.

Hard rules:

- no global CSRF disable;
- no exemption of all `/api/**` mutations;
- login/register and authenticated mutations remain protected as applicable;
- source audit and integration tests must prove that a mutation without a valid CSRF token is rejected.

## 9. Same-origin policy and CORS

Production frontend and backend are served from `gomaf.online` through the same application origin.

Therefore:

- no broad CORS configuration is needed;
- no `Access-Control-Allow-Origin: *`;
- development Vite may proxy `/api` to Spring locally so browser behavior remains same-origin-like;
- credentials are never opened to arbitrary origins.

## 10. Frontend routing and static asset delivery

Production Vite output is included in the Spring Boot application artifact.

The app container remains the only application service.

Spring serves:

- fingerprinted static JS/CSS/assets;
- the SPA entry document for allow-listed frontend routes;
- `/api/**` exclusively as API;
- `/healthz` as infrastructure health;
- avatar/captcha binary endpoints either under compatible legacy paths during transition or under explicitly migrated API/resource paths.

Unknown `/api/**` routes must return API 404, never the SPA shell.

The frontend fallback must be constrained so security probes and arbitrary extension paths are not silently converted into HTTP 200 SPA pages.

## 11. CSP/security headers after React

The current `script-src 'none'` cannot coexist with a React production bundle.

v11 changes CSP only as much as required for self-hosted compiled assets.

Target baseline:

```text
default-src 'self';
script-src 'self';
style-src 'self';
img-src 'self' data:;
font-src 'self';
connect-src 'self';
object-src 'none';
frame-src 'none';
form-action 'self';
frame-ancestors 'none';
base-uri 'none';
```

Exact final directives are validated against the actual production build.

Prohibited without explicit review:

- `unsafe-eval`;
- arbitrary external scripts;
- wildcard script origins;
- third-party runtime CDN dependencies for core UI;
- secrets embedded in `VITE_*` variables.

Existing frame/referrer/permissions/COOP/CORP protections remain unless a documented functional requirement proves otherwise.

## 12. Production artifact and Docker build

Production still deploys one GoMaf `app` image.

Conceptual multi-stage build:

```text
frontend dependency stage
  -> deterministic npm ci

frontend verification/build stage
  -> typecheck
  -> frontend tests
  -> production Vite build

backend build stage
  -> copy backend
  -> copy verified frontend dist into Spring static resources
  -> mvn -B verify
  -> Spring Boot jar

runtime stage
  -> Java runtime only
  -> non-root app user
  -> app.jar
```

Rules:

- exact Node/base image versions are pinned during implementation; no floating `latest`;
- `package-lock.json` is mandatory;
- production uses `npm ci`, not dependency resolution by `npm install`;
- Node/npm do not need to exist in the runtime image;
- final runtime remains non-root and read-only as in v10.2.1;
- `/healthz` remains served by Spring and remains independent from client-side React execution.

## 13. Deployment invariants

The existing one-command deployment UX is preserved.

The client-side operational entry point remains conceptually:

```text
gomaf-deploy-update.sh
```

The v11 deploy must preserve:

1. transport and release SHA-256 verification;
2. immutable release directories;
3. exclusive deploy lock;
4. source audit before build;
5. deterministic frontend dependency/install/build gates;
6. backend `mvn verify` gate;
7. Docker/Compose/Caddy validation;
8. migration rehearsal against a cloned production database;
9. pre-deploy backup;
10. candidate app health before Caddy exposes it;
11. internal API/security smoke tests;
12. external HTTPS smoke tests;
13. production audit;
14. `DEPLOY PASS` marker;
15. `PRODUCTION AUDIT PASS` marker;
16. final external `/healthz` confirmation from the user's machine;
17. previous immutable release/image/source hash metadata;
18. automatic recovery paths.

The frontend architecture is not allowed to weaken any of these properties.

## 14. Updated deployment gates for v11

`source-audit.sh` must stop asserting Thymeleaf-specific behavior only after the corresponding React path exists.

New mandatory gates include:

- `package-lock.json` present and tracked in release sums;
- deterministic `npm ci`;
- TypeScript strict/typecheck PASS;
- frontend unit/component test PASS;
- Vite production build PASS;
- no source maps in production unless explicitly approved and access-reviewed;
- no inline/event-handler script patterns in authored HTML where prohibited by CSP design;
- no `unsafe-eval` production requirement;
- backend `mvn -B verify` PASS;
- API authorization integration tests PASS;
- CSRF integration tests PASS;
- Playwright E2E PASS for selected critical flows;
- Chromium viewport audit at approximately 1440, 1024 and 390 px;
- document-level horizontal overflow checks;
- production bundle is present inside the built application artifact.

## 15. Database migration policy

Existing V1..V4 files are immutable.

New migrations start at V5.

During the transitional releases, migrations should be additive whenever feasible:

- add tables/columns/indexes rather than destructive rename/drop;
- new required columns need safe defaults/backfill before NOT NULL;
- old v10.2.1 JPA must continue to tolerate the resulting schema during emergency forward-compatible rollback;
- destructive cleanup is deferred until legacy MVC rollback is deliberately retired.

A migration that breaks v10.2.1 forward compatibility requires a new explicit maintenance/cutover gate and cannot be slipped into an ordinary release.

## 16. Transitional compatibility strategy

There is no big-bang switch.

### Phase A — baseline

v10.2.1 remains the known-good production/rollback release.

### Phase B — API alongside MVC

New `/api/v1` controllers are introduced while existing Thymeleaf routes keep working.

The API calls existing service/business logic rather than cloning rules into a second implementation.

### Phase C — React vertical slices

React replaces one product surface at a time. Legacy server-rendered pages remain available internally/compatibly until the corresponding slice passes acceptance.

### Phase D — v11 transitional production

The first public v11 release still retains enough legacy code/schema compatibility for emergency rollback and comparison.

### Phase E — legacy retirement

Only after production acceptance and a separate gate:

- Thymeleaf dependency may be removed;
- legacy page controllers/templates may be removed;
- Thymeleaf-specific source-audit rules are removed;
- destructive schema cleanup can be planned separately.

## 17. First vertical slice: Players + Administration

The first functional slice is deliberately not the homepage.

It must prove:

- current session retrieval;
- anonymous/authenticated/admin/moderator authorization;
- player list pagination;
- search;
- sorting;
- public profile navigation;
- long username rendering;
- rating administration;
- username administration;
- avatar reset;
- password reset;
- account deletion behavior;
- moderator restrictions against self/other moderators;
- re-authentication/current-password behavior for sensitive operations;
- audit events;
- frontend validation + backend validation;
- server error rendering;
- query invalidation without full-page reload;
- desktop/tablet/mobile UI.

This slice is the architecture proof. Other domains do not migrate until it passes.

## 18. Product-domain migration order

After Players vertical slice:

1. Profiles.
2. Games and game detail/editing.
3. Schedule and events.
4. Tournaments and hidden-game visibility.
5. Ratings.
6. News.
7. Rules.
8. Remaining admin/moderator surfaces.

The order may change only when dependency analysis shows a better technical sequence; hidden-game privacy may not be weakened for sequencing convenience.

## 19. UI/UX acceptance requirements

The v10 UI lessons remain mandatory:

- no document-level horizontal overflow;
- long usernames wrap safely;
- predictable geometry;
- mobile is first-class, not a scaled desktop page;
- no essential hover-only interaction;
- list/admin context is preserved after mutations;
- server-side pagination for large collections;
- search/sort/filter states live in URL query parameters where appropriate so refresh/back/forward work;
- destructive actions require deliberate confirmation;
- loading, empty, validation, permission-denied and server-error states are designed explicitly;
- real Chromium screenshots/Playwright checks at desktop/tablet/mobile widths are required.

## 20. Observability and audit

Backend remains authoritative for audit logging.

Frontend errors may be presented to users but must not expose stack traces or sensitive backend details.

API responses should include/request-correlate a request ID when practical so an operator can match an observed frontend error to server logs without exposing secrets.

## 21. Definition of V11-G0 PASS

V11-G0 is PASS when all of the following are true:

- v10.2.1 source topology was inspected;
- current deploy and rollback mechanics were inspected;
- target architecture is written and internally consistent;
- auth/session/CSRF same-origin model is decided;
- one-container production deployment is decided;
- additive migration/rollback policy is decided;
- first vertical slice is chosen;
- implementation gates are defined;
- no production code was changed as part of the architecture gate.

This document satisfies the architectural portion. Implementation begins only in V11-G1.
