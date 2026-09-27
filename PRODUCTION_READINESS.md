# Production readiness v10

Дата подготовки: 2026-09-13.

## Реализовано

Архитектура: Internet -> Caddy 2.11.4 -> Spring Boot 4.1.1 -> PostgreSQL 17.11. App и DB не публикуют host ports; backend Docker network internal.

Авторизация:

- единственный ADMIN `admin`;
- `PLAYER`, `MODERATOR`, `ADMIN`;
- ADMIN скрыт из публичной игровой поверхности;
- MODERATOR остаётся публичным игроком;
- 9 deny-by-default scopes;
- per-user `INHERIT/ALLOW/DENY`;
- session invalidation через `authorization_version`;
- password/rename invalidation через `credential_version`;
- immutable `userId` является session identity.

База:

- Flyway V3;
- `account_role` для v10 и legacy `role` для rollback v9;
- DB constraints на canonical username, security versions, роли, единственного ADMIN и reserved username `admin`;
- moderator defaults/overrides имеют DB CHECK constraints.

Edge:

- Caddy блокирует стандартные probes до backend;
- forwarding headers перезаписываются Caddy;
- production Spring использует native forwarded headers;
- Tomcat early context-root redirect отключён;
- HTTPS redirect smoke-test проверяет `Location`.

Логи/диск:

- отдельные application/audit/access logs с rotation;
- безопасный log export helper;
- storage maintenance с приоритетом сохранения audit evidence;
- disk-aware PostgreSQL backup retention.

Deploy:

- immutable source snapshots;
- Docker build содержит обязательный `mvn -B verify`;
- pre-maintenance migration rehearsal на clone live DB;
- predeploy backup;
- candidate health/security tests;
- forward-compatible rollback v9 после V3.

## Фактически проверено в среде подготовки

- усиленный `ops/source-audit.sh`: 144 PASS, 0 FAIL на текущем проверочном прогоне;
- XML parsing `pom.xml`;
- stdlib-only structural smoke production Compose;
- HTML parsing всех templates;
- CSRF-token presence во всех POST forms;
- shell syntax всех `ops/*.sh`;
- `sh -n` для `docker/db-prepare.sh`;
- Python compile для storage maintenance helper;
- отдельная in-memory проверка порядка low-disk cleanup подтвердила, что обычная очистка не удаляет audit logs;
- exact-secret redaction log-export проверен локальной фикстурой;
- Java parser smoke не выявил parse-like ошибок (полная type/dependency compilation остаётся за Maven);
- fresh-install host support отделён от migration-only v9->v10 upgrade path;
- конфигурация forwarding сверена с актуальной документацией Spring Boot/Caddy.

## Непроверенная runtime-граница

В среде подготовки нет Maven, Docker daemon и PostgreSQL runtime, а Maven dependencies недоступны по сети. Поэтому здесь НЕ отмечены как PASS:

- `mvn -B verify`;
- реальный Docker image build;
- реальный Flyway V3 против PostgreSQL;
- migration rehearsal на live clone;
- Caddy config runtime validation;
- HTTPS/ACME;
- production audit на VPS.

Эти проверки встроены в fail-closed deploy и должны быть реально выполнены на сервере до признания v10 production PASS.
