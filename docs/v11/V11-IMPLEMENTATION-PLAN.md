# GoMaf v11 Implementation Plan

Status: starts after V11-G0

## Gate overview

```text
V11-G0  Architecture and migration specification
V11-G1  Repository/backend/API foundation + auth/session
V11-G2  React foundation + Players vertical slice
V11-G3  Remaining product domains
V11-G4  Production build/deploy migration + cutover
```

## V11-G0 — Architecture

Deliverables:

- source inventory;
- architecture specification;
- API conventions;
- migration/rollback strategy;
- implementation work package plan;
- decision log.

Acceptance:

- no production behavior changed;
- architecture decisions are sufficient to start implementation without choosing basic topology/auth/deployment strategy during coding.

Current status: **PASS**.

## V11-G1 — Foundation

### WP11-001 Baseline preservation

- preserve original v10.2.1 release archive and SHA-256;
- create v11 development source tree from a verified copy;
- record baseline source hash and V1..V4 migration hashes;
- no editing of V1..V4.

Acceptance: byte-level baseline evidence retained.

### WP11-002 Mechanical repository split

- introduce `backend/` and `frontend/` target structure;
- move current Maven application mechanically to `backend/`;
- update only paths in scripts/build metadata necessary for equivalent v10 behavior;
- do not add REST behavior in this WP.

Acceptance:

- existing backend tests still pass;
- existing source/security invariants still pass after path-aware audit updates;
- production packaging is not yet switched to React.

### WP11-003 Frontend foundation

Create React + TypeScript + Vite application with:

- strict TypeScript;
- React Router;
- TanStack Query;
- React Hook Form;
- Zod;
- Vitest/RTL;
- lint/typecheck/build scripts;
- no third-party runtime CDN dependencies.

Initial page may be a diagnostic shell only.

Acceptance:

- deterministic `npm ci`;
- typecheck PASS;
- tests PASS;
- production build PASS.

### WP11-004 API infrastructure

Implement:

- `/api/v1` base conventions;
- DTO mapping conventions;
- canonical error envelope;
- validation error handler;
- request correlation ID if selected;
- pagination response utility;
- API 401/403 behavior distinct from MVC redirects.

Acceptance: integration tests cover API error/security behavior.

### WP11-005 SPA session/auth/CSRF

Implement API-compatible:

- session status;
- login;
- logout;
- registration;
- CSRF token flow;
- existing captcha/rate-limit behavior preserved;
- authorization/credential version invalidation preserved.

Acceptance:

- session cookie remains HttpOnly;
- CSRF mutation without token fails;
- login session fixation protection remains;
- role/permission decisions remain backend-owned.

V11-G1 PASS requires WP11-001..005 PASS.

## V11-G2 — Players vertical slice

### WP11-006 Players read API

- paged/searchable/sortable players list;
- public profile DTO;
- avatar resource;
- privacy/ADMIN hiding invariants;
- games/statistics DTOs required by player UI.

### WP11-007 Player administration API

- rating;
- username;
- password reset;
- avatar reset;
- about/profile admin edit where applicable;
- account deletion;
- moderator target restrictions;
- sensitive-action/current-password requirements;
- audit events.

### WP11-008 Players React UI

Implement real replacement UI:

- URL-backed search/sort/page state;
- responsive data presentation;
- profile navigation;
- admin controls;
- validation/error/loading/empty states;
- mutations through TanStack Query;
- state preserved after mutation;
- no full-page reload required for normal CRUD actions.

### WP11-009 Browser E2E for Players

Playwright scenarios at minimum:

- anonymous player list/profile;
- login;
- admin edit;
- moderator allowed operation;
- moderator denied operation;
- long username;
- mobile 390 px;
- tablet 1024 px;
- desktop 1440 px;
- no document-level horizontal overflow.

V11-G2 PASS means the Players slice is production-quality but does not yet require deleting the legacy page.

## V11-G3 — Domain migration

Work packages proceed one domain at a time with API + React + tests:

### WP11-010 Profiles

Self profile, about text, avatar upload/reset, game history privacy.

### WP11-011 Games

Archive, detail, create/edit/delete, 10 seats, linked/historical players, score logic, videos, tournament privacy.

### WP11-012 Schedule and events

Game/event CRUD, participation, Rating/No rating, Classic/City, participant list, responsive layout.

### WP11-013 Tournaments

Upcoming/completed, dates, cost/free, invitations, participation, attached games, hidden game access management.

### WP11-014 Rating

Rating table/statistics including average additional score semantics.

### WP11-015 News and rules

Read/admin CRUD while preserving content safety.

### WP11-016 Moderator/admin console

Dedicated modern administration surfaces for moderator defaults/overrides and remaining admin functions.

V11-G3 PASS requires functional parity against all v10.2.1 production features intended to survive into v11.

## V11-G4 — Production integration and cutover

### WP11-017 Production asset integration

- Vite build embedded into Spring Boot artifact;
- constrained SPA route fallback;
- `/api/**` never falls back to SPA;
- static asset caching policy defined;
- `/healthz` independent of React.

### WP11-018 CSP/security hardening

- move from `script-src 'none'` to minimal self-hosted React CSP;
- prove no `unsafe-eval` requirement;
- preserve remaining v10 security headers;
- source audit updated accordingly.

### WP11-019 Docker build pipeline

Multi-stage deterministic frontend/backend build with mandatory:

- npm ci;
- typecheck;
- frontend tests;
- frontend build;
- mvn verify;
- final non-root Java runtime image.

### WP11-020 Deployment script migration

Update existing ops scripts without removing their guarantees:

- source audit understands `frontend/` and `backend/`;
- migration rehearsal preserved;
- backup/recovery preserved;
- internal smoke checks frontend asset + API + CSRF + security headers;
- production audit validates final asset/image/source binding;
- client wrapper still requires server PASS markers and external health.

### WP11-021 Transitional production release

Deploy a v11 transitional release while legacy rollback remains supported.

Acceptance:

- migration rehearsal PASS;
- backup PASS;
- frontend/backend build/test gates PASS;
- Playwright critical E2E PASS before packaging;
- candidate internal smoke PASS;
- HTTPS smoke PASS;
- production audit PASS;
- external health PASS;
- rollback rehearsal documented and, where safe, tested on clone/staging conditions.

### WP11-022 Legacy retirement

Separate later package after production acceptance:

- remove Thymeleaf dependency;
- remove legacy MVC templates/controllers no longer needed;
- delete obsolete Thymeleaf audit checks;
- only then consider schema cleanup migrations.

This package is never combined with the first v11 public cutover.

## Stop conditions

Implementation stops and the gate remains FAIL/IN_PROGRESS if any of the following occurs:

- existing v10 security invariant cannot be reproduced in API form;
- hidden game privacy differs between API access paths;
- CSRF is disabled as a shortcut;
- production requires wildcard CORS;
- frontend needs auth tokens in browser storage;
- an ordinary migration breaks emergency v10.2.1 forward-compatible rollback;
- deploy PASS can be emitted before frontend/backend/security/HTTPS gates finish;
- Playwright finds page-level horizontal overflow at required viewports;
- business logic is duplicated client-side in a way that can bypass server rules.
