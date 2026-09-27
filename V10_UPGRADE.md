# Обновление работающего GoMaf v9 -> v10

Этот документ предназначен именно для уже работающего production на Ubuntu 24.04. Переустановка VPS не требуется.

## 1. Предварительная проверка ADMIN

Не переименовывай ADMIN отдельным v9 deploy перед обновлением. Это важно: v9 умеет синхронизировать configured ADMIN, но не является безопасным инструментом атомарного переименования существующего главного аккаунта.

Работающая v9 должна оставаться без изменений до запуска host-upgrade из v10 source tree. `ops/upgrade-server-v10.sh` сам fail-closed проверит живую v9 БД:

- production PostgreSQL контейнер действительно запущен;
- в таблице `users` ровно один legacy `role=ADMIN`;
- `APP_ADMIN_USERNAME` соответствует этому фактическому ADMIN;
- username `admin` не занят другим, не-ADMIN аккаунтом.

После этих проверок host-upgrade делает backup `/etc/gomaf/production.env` и оставляет `APP_ADMIN_USERNAME` без изменения. Если текущий v9 ADMIN ещё называется не строго `admin`, его username сохраняется в защищённом transition-state для безопасного переключения/отката. Если v9 уже использует точный username `admin`, transition-state не создаётся. Это принципиально: работающая v9 до maintenance boundary продолжает использовать ту же конфигурацию, с которой была запущена.

Переключение host-конфигурации `APP_ADMIN_USERNAME` на строго `admin` выполняет уже `ops/deploy.sh` только после остановки публичного v9 app/Caddy и непосредственно перед predeploy backup. Если дальнейший pre-public этап завершается ошибкой, deploy возвращает v9 username и predeploy database backup.

Сам существующий ADMIN переименовывается в `admin` только Flyway V3: сначала на изолированном clone production БД во время migration rehearsal, затем на production после PASS rehearsal и predeploy backup. Между host-upgrade и v10 deploy не запускай повторный v9 deploy/recreate.

## 2. Передача и проверка исходников v10

На основном Ubuntu-компьютере сначала проверь скачанный release:

```bash
cd ~/Downloads
sha256sum -c Cristalix-Mafia-v10-production-ubuntu.zip.sha256
scp Cristalix-Mafia-v10-production-ubuntu.zip \
  Cristalix-Mafia-v10-production-ubuntu.zip.sha256 \
  mafia:/tmp/
```

На сервере под `mafia` проверь тот же SHA-256 и распакуй во временный каталог.
Используется Python stdlib, поэтому отдельный пакет `unzip` не требуется. После
распаковки обязательно восстанови Unix execute bits штатным скриптом из release:

```bash
cd /tmp
sha256sum -c Cristalix-Mafia-v10-production-ubuntu.zip.sha256
rm -rf /tmp/gomaf-v10-upload
install -d -m 0700 /tmp/gomaf-v10-upload
python3 -m zipfile -e \
  Cristalix-Mafia-v10-production-ubuntu.zip \
  /tmp/gomaf-v10-upload
SRC=/tmp/gomaf-v10-upload/Cristalix-Mafia-v10-production-ubuntu
bash "$SRC/ops/normalize-source-permissions.sh" "$SRC"
( cd "$SRC" && sha256sum -c RELEASE-SHA256SUMS.txt )
( cd "$SRC" && ./ops/source-audit.sh )
```

Только после этих PASS синхронизируй source tree. Работающий production при этом
не переключается: он продолжает использовать текущий immutable release snapshot.
`.deploy` намеренно исключается из rsync и сохраняет runtime/rollback metadata:

```bash
rsync -a --delete --exclude '.deploy/' "$SRC/" /srv/gomaf/
cd /srv/gomaf
./ops/source-audit.sh
```

Передавай только source package. Не копируй и не заменяй:

- `/etc/gomaf/production.env`;
- `/srv/gomaf/.deploy`;
- `/srv/gomaf-backups`;
- `/srv/gomaf-logs`;
- Docker volumes.

После распаковки source tree должен находиться в `/srv/gomaf` и принадлежать deployment user `mafia`.

## 3. Host upgrade v10

Host-level изменения выполняй не под deployment user `mafia`, а под системным
администратором `opsadmin`. У `mafia` намеренно нет произвольного sudo. С основного
компьютера:

```bash
ssh opsadmin
sudo bash /srv/gomaf/ops/upgrade-server-v10.sh
exit
```

Скрипт fail-closed:

- требует Ubuntu 24.04;
- требует уже существующих `mafia` и `gomaf`;
- проверяет единственного фактического ADMIN в живой v9 БД и отсутствие конфликта reserved username `admin`;
- проверяет соответствие `APP_ADMIN_USERNAME` текущему v9 ADMIN;
- делает резервную копию production.env и, только если v9 ADMIN ещё не называется строго `admin`, сохраняет его текущий username в защищённом transition-state;
- не переключает `APP_ADMIN_USERNAME` во время host-upgrade: это делает deploy только на maintenance boundary;
- при ошибке host-upgrade восстанавливает production.env и удаляет незавершённый transition-state;
- добавляет только новые non-secret storage/retention параметры;
- создаёт каталоги постоянных логов/exports;
- устанавливает минимальные root helpers;
- обновляет ограниченный sudoers;
- устанавливает storage-maintenance timer;
- обновляет backup timer.

Ожидаемый финальный маркер:

```text
V10 HOST UPGRADE PASS
```

## 4. Source audit до deploy

```bash
sudo -u mafia -H bash -lc 'cd /srv/gomaf && ./ops/source-audit.sh'
```

Любой FAIL блокирует deploy.

## 5. Deploy

```bash
sudo -u mafia -H bash -lc 'cd /srv/gomaf && ./ops/deploy.sh'
```

Для существующего production порядок принципиально такой:

```text
stable immutable source snapshot
-> source/Compose/Caddy validation
-> pinned image pulls
-> Docker build
-> mvn -B verify inside build
-> v9->v10 migration rehearsal on an isolated clone of the LIVE production DB
-> only after rehearsal PASS: maintenance window starts
-> stop public app/Caddy
-> validated predeploy PostgreSQL backup
-> DB role prepare
-> production Flyway V3 migration
-> candidate healthcheck
-> schema/admin/rollback-compat checks
-> internal security smoke tests
-> Caddy start
-> HTTPS/redirect/probe smoke tests
-> commit active/previous release pointers
```

Migration rehearsal uses an internal Docker network, no host ports, temporary credentials and a temporary PostgreSQL volume. Production DB is read through a consistent `pg_dump`; it is not modified by rehearsal.

## 6. После deploy

```bash
cd /srv/gomaf
./ops/status.sh
./ops/production-audit.sh
```

Требуемый итог:

```text
PRODUCTION AUDIT PASS
```

Также проверь вручную вход ADMIN `admin`, `/admin/moderators`, обычного PLAYER и тестового MODERATOR с выключенными scopes.

## 7. Rollback

```bash
cd /srv/gomaf
./ops/rollback.sh
```

До открытия candidate в Internet deploy может вернуть predeploy database dump и старый release.

После того как v10 уже мог принять пользовательские записи, автоматический rollback БД не выполняется, чтобы не потерять новые данные. Предыдущий v9 release запускается в forward-compatible режиме: его Flyway отключается, а Hibernate продолжает `validate` совместимую legacy schema. Поле `users.role` специально остаётся v9-compatible: ADMIN -> ADMIN, MODERATOR/PLAYER -> PLAYER.

Если предыдущий release не проходит healthcheck даже в таком режиме, rollback fail-closed возвращает текущий candidate вместо оставления production в сломанном состоянии.

## 8. Логи при проблеме

```bash
cd /srv/gomaf
./ops/export-logs.sh
```

Helper возвращает путь к архиву в `/srv/gomaf-log-exports`. Он не должен содержать production.env, dumps, SSH keys или source secrets.

## Однокомандный клиентский deploy

Начиная с v10.0.4 `ops/package-release.sh` создаёт рядом с ZIP отдельный клиентский wrapper `gomaf-deploy-<version>.sh`.
Он запускается на основном Ubuntu-компьютере и сам выполняет SHA-проверку, upload через SSH alias `mafia`, проверку/установку source tree, идемпотентный host-support через `opsadmin`, manual backup, `deploy.sh`, `production-audit.sh` и финальный HTTPS healthcheck.
Если исходный production не healthy, обычный wrapper прекращает работу до изменений и требует отдельный recovery flow.
