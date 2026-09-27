# GoMaf production v10

Production-пакет Cristalix Mafia для `gomaf.online`.

Целевая среда: Ubuntu Server 24.04 LTS, Docker Engine + Compose plugin, Caddy 2.11.4, Spring Boot 4.1.1, Java 21 и PostgreSQL 17.11.

## Архитектура

```text
Internet -> Caddy :80/:443 -> Spring Boot :8787 -> PostgreSQL :5432
```

Только Caddy публикует host-порты. Приложение и PostgreSQL находятся во внутренней Docker-сети. Production secrets не хранятся в исходниках и находятся только в `/etc/gomaf/production.env`.

Рабочие исходники находятся в `/srv/gomaf`, но запущенный production использует immutable release snapshot из `/srv/gomaf/.deploy/releases`. Редактирование `/srv/gomaf` само по себе не меняет работающий сайт.

## Что нового в v10

Функциональный слой, перенесённый из смежной ветки собственной реализацией:

- публичные профили PLAYER и MODERATOR;
- кликабельный список игроков;
- безопасное переименование игрока;
- сброс аватара;
- удаление аккаунта с сохранением исторических записей игр;
- UI polish без inline JavaScript.

Новая модель управления доступом:

- `PLAYER`, `MODERATOR`, `ADMIN`;
- единственный ADMIN с username строго `admin`;
- ADMIN скрыт из публичных списков, рейтинга, профилей и выбора участников;
- MODERATOR остаётся обычным публичным игроком;
- девять независимых MODERATOR scopes;
- все общие MODERATOR scopes по умолчанию выключены;
- персональные overrides `INHERIT`, `ALLOW`, `DENY`;
- изменение ролей/scopes инвалидирует затронутые активные сессии.

Production hardening:

- отдельная HTTP 403 страница;
- Caddy блокирует стандартные secret/CMS probes до Spring;
- корректные HTTPS redirects за reverse proxy;
- постоянные `application.log`, `audit.log`, Caddy access log;
- безопасный экспорт логов;
- disk-aware backup/storage retention;
- rehearsal миграции v9 -> v10 на изолированном clone реальной БД до maintenance window;
- rollback предыдущего immutable release с forward-compatible режимом Flyway.

## Новый server setup

Для нового пустого Ubuntu 24.04 сервера:

```bash
sudo bash ops/setup-server.sh
sudo /usr/local/sbin/gomaf-set-admin
sudo -u mafia -H bash -lc 'cd /srv/gomaf && ./ops/deploy.sh'
```

Username главного администратора фиксирован: `admin`. Helper запрашивает только новый пароль ADMIN.

## Обновление существующего v9

Обновление существующего `gomaf.online` выполняется отдельно от первого setup. Не переустанавливай VPS и не удаляй Docker volumes.

Перед v10 не нужен отдельный v9 deploy для переименования ADMIN. Из v10 source tree host-upgrade сначала проверяет единственного фактического ADMIN в живой v9 БД и отсутствие конфликта reserved username `admin`; затем V3 переименует этот же аккаунт только после успешной migration rehearsal:

```bash
# host-level шаг: из сессии opsadmin
sudo bash /srv/gomaf/ops/upgrade-server-v10.sh

# deploy приложения: deployment user mafia
sudo -u mafia -H bash -lc 'cd /srv/gomaf && ./ops/deploy.sh'
```

Полный пошаговый порядок и rollback: `V10_UPGRADE.md`.

## Логи

```text
/srv/gomaf-logs/app/application.log
/srv/gomaf-logs/app/audit.log
/srv/gomaf-logs/caddy/access.log
/srv/gomaf-log-exports/
```

Просмотр container logs:

```bash
cd /srv/gomaf
./ops/logs.sh app
./ops/logs.sh caddy
```

Экспорт постоянных логов и ограниченной host diagnostics без production.env и database dumps:

```bash
cd /srv/gomaf
./ops/export-logs.sh
```

## Backups

PostgreSQL backups находятся в `/srv/gomaf-backups` и создаются в custom format `pg_dump -Fc` с SHA-256 sidecar.

Перед новым dump оценивается размер БД и свободное место. Последние и recovery-critical backups защищаются от обычной очистки. При pressure сначала удаляются старые/низкоприоритетные backups и log exports, затем старые application/access logs. Audit logs не участвуют в обычной low-space очистке и могут затрагиваться только при падении ниже hard free-space floor.

Ручной backup:

```bash
cd /srv/gomaf
./ops/backup.sh manual
```

Restore:

```bash
cd /srv/gomaf
./ops/restore.sh /srv/gomaf-backups/gomaf-....dump
```

Restore валидирует dump и checksum, создаёт safety backup, полностью восстанавливает dump в отдельную временную БД и только после успешной проверки переключает database names. Это позволяет безопасно восстанавливать более старую схему поверх уже мигрированной production БД без конфликтов зависимостей.

## Проверки

Статический source audit:

```bash
cd /srv/gomaf
./ops/source-audit.sh
```

Полный аудит уже запущенного production:

```bash
cd /srv/gomaf
./ops/production-audit.sh
```

`deploy.sh` работает fail-closed. Он не начинает production migration, пока не пройдут source validation, Docker build с `mvn -B verify` и rehearsal v9 -> v10 на изолированном clone текущей production БД.

Текущая граница фактической проверки описана в `FINAL_VERIFICATION.md`.
