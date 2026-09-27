# Final verification status v10

Дата: 2026-09-13.

Статус на этой стадии: STATIC PASS, RUNTIME NOT YET VERIFIED.

## Выполнено фактически

- `ops/source-audit.sh`: 144 PASS, 0 FAIL на текущем проверочном прогоне.
- `pom.xml` разобран XML parser.
- `compose.prod.yaml` прошёл stdlib-only structural smoke; полная Compose validation выполняется Docker Compose в deploy.
- Legacy Thymeleaf templates are absent; React SPA/API checks replace template-specific verification.
- Все POST forms проверяются source-audit на `_csrf`.
- Все `ops/*.sh` проходят `bash -n`.
- `docker/db-prepare.sh` проходит `sh -n`.
- `ops/root/gomaf-storage-maintenance` проходит Python bytecode compile.
- В production source отсутствуют BAT/Windows deployment artifacts.
- В source tree отсутствуют реальные `.env`/`production.env` и database dumps.
- V3 содержит DB invariants для `admin`, roles, security versions и moderator scopes.
- Deploy содержит pre-maintenance migration rehearsal на internal Docker network без host ports.
- Storage maintenance отдельно проверен на приоритет: operational logs раньше audit logs; audit не удаляется обычной low-space веткой.
- Fresh-install path отделён от v9->v10 transition: `setup-server.sh` устанавливает общий v10 host support без требования живой v9 БД.
- Log export redaction проверен отдельной локальной фикстурой: точные production-secret значения заменяются и затем повторно ищутся fail-closed.
- Java sources дополнительно прошли parser smoke через `javac`: parse-like ошибок не обнаружено; dependency/type compilation без Maven не заявляется как PASS.
- Final release packaging выполняется `ops/package-release.sh`: повторный source audit, запрет secret/generated artefacts, внутренний SHA-256 manifest, unzip verification и повторный source audit из распакованного ZIP.

## Тесты, подготовленные в source tree

Добавлены/обновлены integration/unit scenarios для:

- strict ADMIN `admin` startup rules;
- reserved username `admin`;
- публичной невидимости ADMIN;
- PLAYER/MODERATOR public profiles;
- rename + immutable session identity/invalidation;
- avatar reset;
- account deletion with game-history preservation;
- MODERATOR defaults false;
- global/default permission;
- `ALLOW`/`DENY` overrides;
- global scope session invalidation;
- per-user override session invalidation;
- assign/remove MODERATOR session invalidation;
- запрета MODERATOR доступа к `/admin/moderators`;
- обычного PLAYER -> dedicated 403;
- удаления BCrypt hash из authenticated principal после authentication (`CredentialsContainer`);
- запрета MODERATOR управлять собой/другим MODERATOR через player-management scopes;
- UI-фильтрации недоступных MODERATOR targets;
- маскировки старой несвязанной game-history записи с canonical nickname `admin`.

## Что НЕ запускалось в этой среде

Следующее не заявляется как PASS:

- `mvn -B verify`;
- Spring context start после всех v10 changes;
- JUnit execution;
- PostgreSQL/Flyway V3 runtime migration;
- Docker image build;
- migration rehearsal script против реальной v9 DB;
- Caddy runtime validation;
- HTTPS smoke tests;
- final `PRODUCTION AUDIT PASS` на VPS.

Причина: в текущей среде нет Maven, Docker daemon и PostgreSQL runtime; Maven dependency resolution недоступен.

## Обязательная финальная серверная граница

Production v10 может считаться проверенной только если на целевом сервере `./ops/deploy.sh` завершился успешно и затем:

```bash
./ops/production-audit.sh
```

вывел:

```text
PRODUCTION AUDIT PASS
```

До этого корректная формулировка состояния: source/static audit PASS, runtime verification pending.
