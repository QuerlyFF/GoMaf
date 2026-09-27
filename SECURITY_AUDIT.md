# SECURITY AUDIT v11 pre-cutover

Дата актуализации: 2026-09-14.

Этот документ описывает реализованные защитные меры и известную verification boundary. Он не является утверждением об абсолютном отсутствии уязвимостей.

## Identity, roles, sessions

- Роли: PLAYER, MODERATOR, ADMIN.
- ADMIN единственный и имеет username строго `admin`.
- Username `admin` зарезервирован и запрещён регистрации/переименованию других пользователей.
- Основной идентификатор authenticated session — immutable database `userId`, а не username.
- `credential_version` инвалидирует sessions после password reset/rename.
- `authorization_version` инвалидирует sessions после role/scope changes.
- Session fixation protection включена.
- Session cookie HttpOnly, SameSite=Lax; production требует Secure.

## MODERATOR authorization

- Все 9 global scopes создаются `FALSE` миграцией.
- Персональные решения: INHERIT, ALLOW, DENY.
- Effective permission вычисляется server-side для каждого защищённого action.
- ADMIN всегда разрешён; PLAYER всегда запрещён для scoped actions.
- MODERATOR не имеет доступа к `/admin/**` и не может менять roles/scopes.
- Global default change инвалидирует все MODERATOR sessions.
- Per-user override/role change инвалидирует только target session.
- UI visibility не считается механизмом безопасности: POST/administrative GET проверяются на сервере.

## Public ADMIN isolation

ADMIN исключён из:

- списка игроков;
- публичного профиля/avatar;
- рейтинга;
- selectable game/schedule participants;
- пересчёта публичной статистики.

Историческая игровая запись, если она связана с ADMIN, отображает нейтральное `Скрытый игрок`, не раскрывая username.

## Sensitive player actions

Переименование, password reset, avatar reset и account deletion требуют scoped permission и повторной проверки текущего пароля оператора через rate-limited SensitiveActionService.

Delete дополнительно требует точного ввода текущего username target. История игр сохраняется путём транзакционного detach `game_players.user_id`, после чего account удаляется.

ADMIN account не является valid target player-management операций.

## Authentication/input security

- BCrypt cost 12.
- Пароль: 6-64 символа, максимум 72 UTF-8 bytes.
- CAPTCHA и rate limits для login/register и sensitive paths.
- Registration source хранится через HMAC-SHA256, а не plain IP.
- CSRF включён.
- Legacy Thymeleaf templates удалены в WP11-022; React не использует `dangerouslySetInnerHTML`, inline script или runtime eval.
- CSP для v11 SPA: `script-src 'self'`, `style-src 'self'`, `img-src 'self' data:`, `connect-src 'self'`, `object-src 'none'`, `frame-src 'none'`, `frame-ancestors 'none'`, `base-uri 'none'`; `unsafe-inline` и `unsafe-eval` запрещены.
- Пользовательский ввод не конкатенируется в SQL application code.

## Avatar handling

- Только декодируемые PNG/JPEG.
- До 2 MiB, 2048x2048, 4 млн pixels.
- Полный decode, перерисовка, уменьшение максимум до 512 px и повторное PNG encoding.
- Исходные bytes/filename/MIME metadata не сохраняются.
- SVG/GIF/WebP запрещены.
- Response фиксирует image/png и nosniff.

## Dedicated 403

Authorization failure обрабатывается отдельным AccessDeniedHandler:

- HTTP 403;
- пользовательская страница `Доступ запрещён`;
- без stack trace/internal exception details;
- событие записывается в audit log.

## Reverse proxy / HTTPS

Caddy является единственным public path к backend. Внешний `Forwarded` удаляется, `X-Forwarded-For`, `X-Forwarded-Proto`, `X-Forwarded-Host` формируются Caddy. Production Spring включает native forwarded-header support; Tomcat early context-root redirect отключён.

Caddy блокирует common probes до Spring: `.env`, `.git`, `.svn`, `.hg`, actuator, wp-admin/wp-login, phpMyAdmin, PHPUnit vendor probe, server-status.

Smoke test проверяет HTTP -> HTTPS и то, что unauthenticated redirect Location имеет `https://gomaf.online/...`.

## HTTP headers

- CSP;
- X-Frame-Options DENY;
- X-Content-Type-Options nosniff;
- Referrer-Policy strict-origin-when-cross-origin;
- Permissions-Policy;
- COOP/CORP;
- HSTS от Caddy;
- Server header удаляется Caddy.

## Audit logging

Отдельный `audit.log` получает authorization denials, failed sensitive re-authentication и административные изменения ролей/scopes/players/content/games/schedule. Audit helper санитизирует управляющие символы и не записывает passwords.

Caddy access logging не включает request bodies. Потенциально чувствительные Cookie/Authorization headers Caddy редактирует по умолчанию.

## PostgreSQL

- Application использует отдельную non-superuser role.
- Запрещены SUPERUSER/CREATEDB/CREATEROLE/REPLICATION/BYPASSRLS.
- PostgreSQL port не публикуется.
- Hibernate production `ddl-auto=validate`.
- Schema changes только Flyway.
- Flyway clean disabled.

V3 добавляет DB-level constraints для canonical username, nonnegative security versions, valid role values, legacy rollback representation, reserved `admin`, единственного ADMIN и moderator scope/override values.

## Container/runtime hardening

- app UID 10001;
- read-only root filesystem;
- `/tmp` tmpfs noexec,nosuid;
- cap_drop ALL;
- no-new-privileges;
- PID/RAM limits;
- backend network internal;
- только Caddy публикует 80/443.

## Deploy/migration security

Перед production mutation существующая DB миграция репетируется на одноразовом clone:

- source — consistent pg_dump live DB;
- temporary internal Docker network;
- no host ports;
- generated temporary credentials;
- candidate read-only/cap-drop/no-new-privileges;
- clone удаляется cleanup trap.

Затем production получает validated predeploy dump перед Flyway V3.

## Logs/backups/disk pressure

Постоянные application/audit/access logs имеют independent rotation. Log export helper копирует только allowlisted logs и bounded diagnostics.

Backup retention учитывает свободное место, размер incoming dump и protected recovery backups. При pressure audit logs являются последним классом очистки и не удаляются обычной operational cleanup веткой.

## Остаточные риски и границы

- Local backups на том же VPS не заменяют off-site backup.
- Volumetric DDoS требует upstream/provider защиты.
- In-memory rate-limit buckets сбрасываются после restart; registration abuse имеет дополнительный persistent DB limit.
- Никакой source audit не заменяет реальный `mvn verify`, Flyway migration, container health и production HTTPS audit.
- На текущей стадии runtime v10 ещё не был запущен в этой среде; см. `FINAL_VERIFICATION.md`.
