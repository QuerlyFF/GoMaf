# V11-G2 Players Vertical Slice

Status in source package: implemented; executable gate required before PASS.

## Public read API

- `GET /api/v1/players`
- `GET /api/v1/players/{id}`
- `GET /api/v1/players/{id}/games`
- `GET /api/v1/players/{id}/avatar`

ADMIN is excluded from public player surfaces. Public DTOs do not expose account role, credentials, security versions, registration IP data, or moderator status.

## Administration API

Authenticated capabilities:

- `GET /api/v1/players/{id}/administration`

Mutations:

- `PATCH /api/v1/players/{id}/rating`
- `PATCH /api/v1/players/{id}/username`
- `PUT /api/v1/players/{id}/password`
- `DELETE /api/v1/players/{id}/avatar`
- `PATCH /api/v1/players/{id}/profile`
- `DELETE /api/v1/players/{id}`

All mutations remain CSRF protected. Moderator scopes and target restrictions are enforced by the backend. Username/password/avatar/delete actions require the operator's current password through the existing `SensitiveActionService`. Rating preserves legacy behavior and does not require reauthentication. Profile/about administrative editing remains ADMIN-only.

## React UI

The V11 Players UI provides URL-backed search, sorting and pagination, profile navigation, game history, backend-owned administration capabilities, React Hook Form + Zod validation, and TanStack Query mutations without normal CRUD page reloads.

The legacy Thymeleaf Players page remains present during G2.

## Browser E2E

Playwright covers anonymous list/profile, real login, admin edit, allowed moderator action, denied moderator action, long usernames, 390/1024/1440 viewports and document-level horizontal overflow.

E2E credentials and deterministic CAPTCHA support exist only under `backend/src/test` and profile `e2e`. They are not part of the production application sources or production jar.
