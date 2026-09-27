# GoMaf v10 production deployment

Домен: `gomaf.online`.

Целевая среда: Ubuntu Server 24.04 LTS, Docker Engine + Compose plugin, PostgreSQL 17.11, Spring Boot 4.1.1, Java 21, Caddy 2.11.4.

## 1. Первый setup

Для нового сервера должны быть готовы DNS A record и рабочий SSH public key текущего администратора сервера.

```bash
sudo bash ops/setup-server.sh
sudo /usr/local/sbin/gomaf-set-admin
sudo -u mafia -H bash -lc 'cd /srv/gomaf && ./ops/deploy.sh'
```

`gomaf-set-admin` меняет только пароль единственного ADMIN `admin`. Production secrets создаются вне source tree в `/etc/gomaf/production.env`. `setup-server.sh` устанавливает общий v10 host-support (log/storage directories, ограниченные helpers и timers) напрямую; transition-only `upgrade-server-v10.sh` на чистой установке не вызывается.

## 2. Обновление существующего v9

Используй `V10_UPGRADE.md`. Не запускай first-server setup поверх работающего VPS.

Критическое правило: не делай отдельный v9 deploy ради переименования ADMIN. `ops/upgrade-server-v10.sh` проверяет единственного ADMIN и отсутствие конфликта username `admin` непосредственно в живой v9 БД, затем безопасно подготавливает config; само переименование выполняет V3 только после rehearsal PASS.

## 3. Immutable releases

`/srv/gomaf` является редактируемым source tree. Runtime использует snapshot:

```text
/srv/gomaf/.deploy/releases/<release-id>/
```

Deploy копирует source с checksum stability verification. Изменение `/srv/gomaf` во время работы не меняет запущенный release.

## 4. Fail-closed deploy pipeline

До maintenance window:

1. exclusive deploy lock;
2. stable immutable source snapshot;
3. source audit;
4. Compose validation;
5. Caddy config validation в pinned Caddy image;
6. pull pinned base images;
7. Docker build, внутри которого обязателен `mvn -B verify`;
8. если production DB уже существует: migration rehearsal на clone live DB.

Migration rehearsal:

- снимает консистентный `pg_dump` работающей БД;
- восстанавливает его во временный PostgreSQL 17.11;
- использует отдельную `--internal` Docker network;
- не публикует ports;
- создаёт одноразовые DB credentials;
- запускает candidate read-only с `cap_drop ALL` и `no-new-privileges`;
- применяет Flyway V3 только к clone;
- проверяет Hibernate schema validation, `/healthz`, единственного `admin`, role rollback compatibility и 9 deny-by-default scopes;
- удаляет temp containers/network/volume при завершении.

Только после rehearsal PASS начинается production maintenance window.

Во время maintenance:

1. останавливаются public app/Caddy;
2. production PostgreSQL остаётся доступен локально для backup;
3. создаётся и валидируется predeploy dump;
4. candidate подготавливает ограниченную app DB role;
5. app применяет Flyway и должен пройти healthcheck;
6. отдельно проверяются V3 schema и role compatibility;
7. выполняются internal HTTP/CSRF/forwarded-HTTPS tests;
8. запускается Caddy;
9. выполняется HTTPS smoke test, probe blocking и HTTP -> HTTPS redirect test;
10. только после всех PASS фиксируются current/previous release pointers.

## 5. Failure/rollback semantics

До открытия candidate в Internet deploy имеет право восстановить predeploy DB dump и предыдущий release.

После открытия candidate в Internet база автоматически назад не откатывается: это могло бы удалить новые пользовательские записи. Вместо этого deploy пытается запустить предыдущий release с Flyway disabled, сохраняя Hibernate validation. V3 специально сохраняет v9-compatible legacy `users.role`.

Если старый release несовместим даже в этом режиме, deploy возвращает candidate и сообщает ошибку.

Ручной rollback:

```bash
cd /srv/gomaf
./ops/rollback.sh
```

## 6. Logs

Постоянные логи:

```text
/srv/gomaf-logs/app/application.log
/srv/gomaf-logs/app/audit.log
/srv/gomaf-logs/caddy/access.log
```

Caddy по умолчанию редактирует потенциально чувствительные `Cookie`, `Set-Cookie`, `Authorization` и `Proxy-Authorization` значения в access log. Request bodies не добавляются в лог конфигурацией проекта.

Экспорт:

```bash
./ops/export-logs.sh
```

## 7. Backup policy

Backups: `/srv/gomaf-backups`.

Перед dump `backup-maintenance.sh` учитывает logical DB size и требует свободное место не ниже configured target с резервом под новый dump. Защищаются newest copies, last predeploy/last successful и последние `predeploy`, `prerollback`, `prerestore`.

Storage pressure cleanup идёт в порядке:

```text
старые log exports
-> старые/unprotected backups
-> старые application/access rotated logs
-> audit rotated logs только ниже hard minimum free-space floor
```

Off-site backup в пакет не включён: для него необходим выбранный внешний provider и credentials.

## 8. Проверки

```bash
./ops/source-audit.sh
./ops/status.sh
./ops/production-audit.sh
```

`production-audit.sh` проверяет runtime hardening, DB role, Flyway V3, ADMIN invariant, MODERATOR scope tables, timers, DNS/TLS и HTTPS security smoke tests.
