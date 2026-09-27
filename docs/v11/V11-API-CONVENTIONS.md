# GoMaf v11 API Conventions

Version: 0.1
Base path: `/api/v1`

## 1. General rules

- API uses UTF-8 JSON unless the resource is explicitly binary.
- JPA entities are never serialized directly.
- DTO fields are intentionally chosen for the client contract.
- Unknown JSON fields should be rejected or ignored consistently; the implementation decision must be tested and not vary per controller accidentally.
- Dates use ISO-8601 calendar format, e.g. `2026-09-14`.
- Date-times use ISO-8601 with an explicit offset when crossing the API boundary.
- Backend stores/handles persistence timestamps consistently in UTC where current model semantics permit.
- IDs remain opaque numeric resource IDs in v11; frontend does not infer meaning from them.

## 2. Standard success shapes

Single resource:

```json
{
  "data": {
    "id": 123
  }
}
```

Collection:

```json
{
  "items": [],
  "page": 0,
  "size": 25,
  "totalItems": 0,
  "totalPages": 0
}
```

Mutation responses may return the updated resource, a compact result DTO, or `204 No Content`. The choice must be documented per endpoint and stable.

## 3. Standard error shape

```json
{
  "status": 400,
  "code": "VALIDATION_FAILED",
  "message": "Некорректные данные.",
  "fieldErrors": [
    {
      "field": "username",
      "code": "USERNAME_INVALID",
      "message": "Некорректное имя пользователя."
    }
  ],
  "requestId": "..."
}
```

Rules:

- no Java class names;
- no SQL details;
- no stack traces;
- no raw exception messages from internal failures;
- user-facing messages are Russian for the current product language;
- stable machine `code` values are preferred over frontend parsing of Russian message text.

## 4. Status conventions

- `200` — successful read/update with body.
- `201` — resource created.
- `204` — successful mutation with no body.
- `400` — syntactically/semantically invalid request.
- `401` — authentication required/invalid session for API request.
- `403` — authenticated but not authorized, or CSRF rejected as appropriate.
- `404` — resource absent or intentionally non-disclosed.
- `409` — state/business conflict, including concurrency conflict when introduced.
- `413` — upload/request too large.
- `429` — rate limit.
- `500` — unexpected server fault without internal details.

Private hidden-game endpoints may use 404 rather than revealing existence when disclosure itself is sensitive; this must be consistent with existing privacy policy.

## 5. Pagination/sorting/filtering

Canonical query parameters:

```text
page=0
size=25
sort=username,asc
q=search text
```

Rules:

- server clamps/validates page size;
- allowed sort keys are explicit per endpoint;
- arbitrary JPA property names are not accepted from the client;
- product defaults preserve current historical page sizes unless UX intentionally changes them:
  - players: 25;
  - games/archive: 20;
  - moderators: 20;
  - rating/table: up to 50.

## 6. Session/auth endpoints — initial contract

Planned initial surface:

```text
GET    /api/v1/session
POST   /api/v1/auth/login
POST   /api/v1/auth/logout
POST   /api/v1/auth/register
GET    /api/v1/csrf              (if explicit token endpoint is selected)
```

`GET /session` returns only client-relevant state, for example:

- authenticated boolean;
- user ID/username when authenticated;
- role;
- effective moderator permissions needed for UI capability hints;
- never password hashes, security versions, registration IP hashes or other internal fields.

Frontend permission flags are display hints. Backend authorization is authoritative.

## 7. Players/profile — initial target surface

Public reads:

```text
GET /api/v1/players
GET /api/v1/players/{id}
GET /api/v1/players/{id}/games
GET /api/v1/players/{id}/avatar
```

Authenticated self actions:

```text
PATCH /api/v1/me/profile
PUT   /api/v1/me/avatar
DELETE /api/v1/me/avatar
```

Administrative/domain actions use explicit endpoints rather than a single unrestricted generic user patch, e.g.:

```text
PATCH  /api/v1/admin/players/{id}/rating
PATCH  /api/v1/admin/players/{id}/username
POST   /api/v1/admin/players/{id}/password-reset
DELETE /api/v1/admin/players/{id}/avatar
DELETE /api/v1/admin/players/{id}
```

Exact naming may be refined in G1, but sensitive operations stay distinct so authorization/audit semantics are explicit.

## 8. Games — target resource model

```text
GET    /api/v1/games
POST   /api/v1/games
GET    /api/v1/games/{id}
PUT    /api/v1/games/{id}
DELETE /api/v1/games/{id}
```

Tournament association/privacy is represented in DTOs and validated server-side.

Video links are a child collection in the game command DTO or explicit child resource, but URL validation remains backend-enforced.

Player rows retain historical display names when no active user relation exists. Frontend creates profile links only when the API explicitly supplies a linked user identity.

## 9. Schedule/events — target resource model

```text
GET    /api/v1/schedule
POST   /api/v1/schedule/games
POST   /api/v1/schedule/events
PUT    /api/v1/schedule/{id}
DELETE /api/v1/schedule/{id}
PUT    /api/v1/schedule/{id}/participation/me
DELETE /api/v1/schedule/{id}/participation/me
```

Participation is relational DB state, not a string/list embedded in schedule text.

## 10. Tournaments — target resource model

```text
GET    /api/v1/tournaments
POST   /api/v1/tournaments
GET    /api/v1/tournaments/{id}
PUT    /api/v1/tournaments/{id}
DELETE /api/v1/tournaments/{id}

PUT    /api/v1/tournaments/{id}/participation/me
DELETE /api/v1/tournaments/{id}/participation/me
POST   /api/v1/tournaments/{id}/invitations
DELETE /api/v1/tournaments/{id}/invitations/{userId}
```

Current product invariants remain:

- paid tournament participation requires a valid invitation;
- revoking a paid invitation removes participation;
- tournament deletion is blocked while games are attached;
- tournament management continues to map to the existing rollback-compatible permission model until a separately planned permission migration is safe.

## 11. Hidden tournament games

All access paths use a common backend visibility policy.

The following must not leak an unauthorized hidden game:

- game list;
- game detail;
- player game history;
- tournament game list;
- search/filter/count endpoints;
- direct URL/API lookup.

ADMIN retains access according to the existing invariant.

## 12. Moderator administration

Initial target:

```text
GET /api/v1/admin/moderation/scopes
GET /api/v1/admin/moderators
PUT /api/v1/admin/moderation/defaults
PUT /api/v1/admin/moderators/{id}/scopes
POST /api/v1/admin/moderators/{id}/assign
POST /api/v1/admin/moderators/{id}/remove
```

The existing nine rollback-compatible moderator scopes are preserved during the v10.2.1-compatible transition. v11 must not silently add a tenth DB enum/scope row merely for tournaments.

## 13. News/rules

Resources become JSON API backed, but their persistence/service logic remains backend-owned.

No HTML supplied by API is trusted as executable frontend markup. React renders structured/plain content safely; any future rich-text feature requires a separate sanitization design.

## 14. Concurrency

The first vertical slice must detect whether administrative concurrent edits need optimistic concurrency. If introduced, the preferred direction is an explicit API version/revision field backed by an additive DB column or a stable ETag strategy.

Silent last-write-wins for high-impact admin operations should not be accepted without deliberate review.

## 15. API tests required before cutover

At minimum:

- anonymous/public access cases;
- authenticated PLAYER cases;
- MODERATOR scope allow/deny cases;
- MODERATOR cannot target self/other moderators where current invariant forbids it;
- ADMIN cases;
- CSRF failure cases;
- stale authorization-version session invalidation;
- hidden game non-disclosure;
- ADMIN hidden game access;
- validation errors and limits;
- pagination/sort allow-list;
- audit event generation for sensitive mutations.
