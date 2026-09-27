# GoMaf v11 Decision Log

## D11-001 Backend language/framework

Decision: retain Java + Spring Boot.

Reason: current business/security/data/deployment logic is already established and the problem being solved is frontend/product architecture, not lack of backend capability.

## D11-002 Frontend

Decision: React + TypeScript + Vite.

Reason: rich admin/product interactions, reusable UI, immediate HMR during design, typed API integration and browser-level application behavior.

## D11-003 Architecture style

Decision: modular monolith, not microservices.

Reason: GoMaf does not need distributed-system overhead; one process simplifies transactions, authorization and deployment.

## D11-004 Production topology

Decision: one GoMaf app container remains. React production assets are included in the Spring Boot artifact.

Reason: preserve the proven deploy/rollback model and avoid adding a Node runtime/service to production.

## D11-005 API

Decision: `/api/v1`, JSON DTOs, no JPA entity serialization.

## D11-006 Authentication

Decision: server-side Spring Security session; keep `MAFIASESSION` HttpOnly; no JWT/localStorage auth.

## D11-007 CSRF

Decision: keep CSRF enabled for SPA mutations with a Spring Security-supported token flow.

## D11-008 Origin model

Decision: production is same-origin. Do not introduce wildcard CORS.

## D11-009 Database

Decision: retain PostgreSQL + Flyway; V1..V4 immutable; v11 starts with V5 if schema changes are needed.

## D11-010 Migration strategy

Decision: incremental API + React migration alongside legacy MVC. No big-bang rewrite.

## D11-011 Rollback

Decision: preserve v10.2.1 as known-good baseline and keep transitional migrations additive/forward-compatible where feasible until legacy retirement is explicitly approved.

## D11-012 Deployment

Decision: preserve one-command update UX and all current fail-closed gates. Add frontend gates; do not replace existing backend/DB/HTTPS gates.

## D11-013 First vertical slice

Decision: Players + administration.

Reason: proves auth, authorization, pagination, search, sorting, CRUD-like operations, validation, audit, responsive UI and client cache invalidation in one slice.

## D11-014 Moderator tournament permission during transition

Decision: preserve the existing nine rollback-compatible moderator scopes. Tournament management continues to use the current permission mapping until a separately designed migration can safely change the model.

## D11-015 Legacy deletion

Decision: do not remove Thymeleaf/MVC in the first v11 public release. Removal is a separate post-acceptance work package.
