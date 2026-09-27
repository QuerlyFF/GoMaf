#!/usr/bin/env bash
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo "Запусти через sudo." >&2; exit 1; }
cat > /etc/systemd/system/gomaf-backup.service <<'EOF2'
[Unit]
Description=GoMaf PostgreSQL backup
Requires=docker.service
After=docker.service

[Service]
Type=oneshot
User=root
Group=root
UMask=0077
WorkingDirectory=/srv/gomaf
ExecStart=/srv/gomaf/ops/backup.sh daily
Nice=10
IOSchedulingClass=best-effort
IOSchedulingPriority=7
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
ReadWritePaths=/srv/gomaf/.deploy /srv/gomaf-backups
EOF2

cat > /etc/systemd/system/gomaf-backup.timer <<'EOF2'
[Unit]
Description=Daily GoMaf PostgreSQL backup

[Timer]
OnCalendar=*-*-* 03:30:00
Persistent=true
RandomizedDelaySec=15m
Unit=gomaf-backup.service

[Install]
WantedBy=timers.target
EOF2
systemctl daemon-reload
systemctl reset-failed gomaf-backup.service >/dev/null 2>&1 || true
systemctl enable --now gomaf-backup.timer
systemctl status gomaf-backup.timer --no-pager || true
