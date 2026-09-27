# V10 implementation map

## Task A — интеграция полезных функций смежной ветки

Источник истины: production v9. Смежная ветка использовалась только как источник функциональных идей; её production/security решения не переносились автоматически.

Перенесено собственной реализацией:

1. публичный профиль игрока;
2. кликабельный список игроков;
3. rename игрока;
4. avatar reset;
5. account delete с сохранением game history;
6. небольшой UI polish.

Ключевые отличия от смежной реализации:

- immutable userId для session identity;
- rename инвалидирует старые sessions;
- reserved `admin`;
- delete транзакционно detach history;
- опасные actions требуют re-authentication;
- MODERATOR player-management scopes не позволяют действовать на себя или другого MODERATOR;
- management UI показывает MODERATOR только реально доступные PLAYER targets;
- delete требует точного username confirmation;
- нет inline JavaScript из-за CSP;
- сохранены v9 password policy, Flyway, healthcheck, Ubuntu production infrastructure.

Task A был зафиксирован отдельным checkpoint до начала Task B.

## Task B — v10 authorization + production hardening

### Authorization

- roles PLAYER/MODERATOR/ADMIN;
- strict single ADMIN `admin`;
- public ADMIN isolation;
- 9 MODERATOR scopes;
- global defaults false;
- per-user INHERIT/ALLOW/DENY;
- server-side permission checks;
- credential/authorization session versions.

### Database

- Flyway V3;
- new `account_role`;
- legacy `role` retained for v9 rollback;
- moderator scope tables;
- DB constraints for identity/security invariants.

### HTTP/security

- dedicated 403;
- audit logging;
- Caddy probe blocking;
- trusted forwarding design;
- HTTPS redirect smoke tests.

### Operations

- persistent app/audit/access logs;
- safe log export с exact-secret redaction и fail-closed повторной проверкой;
- disk-aware backup/log retention;
- migration rehearsal on isolated clone of live v9 DB;
- immutable candidate deploy;
- pre-public DB restore rollback;
- post-public forward-compatible app rollback;
- отдельный idempotent `install-v10-host-support.sh` используется fresh setup и upgrade без смешивания transition-логики;
- deploy проверяет helper drift против immutable snapshot перед migration rehearsal/backup.

## Verification rule

Source/static PASS is not runtime PASS. Final v10 is accepted only after server-side Docker build executes `mvn -B verify`, migration rehearsal passes, production Flyway/candidate smoke tests pass and `ops/production-audit.sh` returns `PRODUCTION AUDIT PASS`.
