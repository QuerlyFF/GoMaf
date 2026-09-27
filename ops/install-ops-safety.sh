#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "$(id -u)" -eq 0 ]] || { echo "install-ops-safety.sh must run as root" >&2; exit 1; }

for cmd in docker systemctl systemd-analyze curl sha256sum awk aide auditctl augenrules fail2ban-client; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "missing required host command: $cmd" >&2; exit 1; }
done

for helper in   gomaf-storage-maintenance   gomaf-security-check   gomaf-aide-check   gomaf-test-memory-guard   gomaf-test-preflight   gomaf-safe-test-run   gomaf-safe-build   gomaf-ops-alert   gomaf-ops-health-check; do
  src="$ROOT/ops/root/$helper"
  [[ -f "$src" && ! -L "$src" ]] || { echo "missing/unsafe helper: $src" >&2; exit 1; }
  install -m 0755 -o root -g root "$src" "/usr/local/sbin/$helper"
done
chmod 0750 /usr/local/sbin/gomaf-security-check

install -d -m 0750 /etc/audit/rules.d
install -d -m 0755 /etc/fail2ban/jail.d /etc/aide
install -m 0640 -o root -g root "$ROOT/ops/host/50-gomaf-audit.rules" /etc/audit/rules.d/50-gomaf.rules
install -m 0600 -o root -g root "$ROOT/ops/host/99-gomaf-hardening.local" /etc/fail2ban/jail.d/99-gomaf-hardening.local
install -m 0600 -o root -g root "$ROOT/ops/host/gomaf-aide.conf" /etc/aide/gomaf-aide.conf

cat > /etc/systemd/system/gomaf-storage-maintenance.service <<'EOF'
[Unit]
Description=GoMaf storage pressure maintenance
OnFailure=gomaf-ops-alert@%n.service

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/gomaf-storage-maintenance
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
ReadOnlyPaths=/etc/gomaf /srv/gomaf/.deploy
ReadWritePaths=/srv/gomaf-logs /srv/gomaf-log-exports /srv/gomaf-backups
EOF

cat > /etc/systemd/system/gomaf-storage-maintenance.timer <<'EOF'
[Unit]
Description=Periodic GoMaf storage pressure check

[Timer]
OnBootSec=10m
OnUnitActiveSec=1h
RandomizedDelaySec=5m
Persistent=true
Unit=gomaf-storage-maintenance.service

[Install]
WantedBy=timers.target
EOF

cat > /etc/systemd/system/gomaf-aide-check.service <<'EOF'
[Unit]
Description=GoMaf targeted AIDE integrity check
After=local-fs.target
OnFailure=gomaf-ops-alert@%n.service

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/gomaf-aide-check
Nice=15
IOSchedulingClass=idle
ProtectSystem=full
ProtectHome=read-only
PrivateTmp=true
NoNewPrivileges=true
EOF

cat > /etc/systemd/system/gomaf-aide-check.timer <<'EOF'
[Unit]
Description=Daily GoMaf AIDE integrity check

[Timer]
OnCalendar=*-*-* 05:20:00 UTC
RandomizedDelaySec=20m
Persistent=true
Unit=gomaf-aide-check.service

[Install]
WantedBy=timers.target
EOF

cat > /etc/systemd/system/gomaf-security-check.service <<'EOF'
[Unit]
Description=GoMaf recurring security and production integrity check
After=docker.service network-online.target auditd.service
Wants=network-online.target
OnFailure=gomaf-ops-alert@%n.service

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/gomaf-security-check
Nice=10
IOSchedulingClass=idle
EOF

cat > /etc/systemd/system/gomaf-security-check.timer <<'EOF'
[Unit]
Description=Daily GoMaf security check

[Timer]
OnCalendar=*-*-* 06:00:00 UTC
RandomizedDelaySec=20m
Persistent=true
Unit=gomaf-security-check.service

[Install]
WantedBy=timers.target
EOF

cat > /etc/systemd/system/gomaf-testing.slice <<'EOF'
[Unit]
Description=GoMaf isolated testing workload slice

[Slice]
MemoryHigh=1280M
MemoryMax=1536M
CPUQuota=150%
TasksMax=2048
EOF

cat > /etc/systemd/system/gomaf-test-memory-guard.service <<'EOF'
[Unit]
Description=GoMaf temporary test workload memory guard
After=docker.service
Requires=docker.service
OnFailure=gomaf-ops-alert@%n.service

[Service]
Type=simple
ExecStart=/usr/local/sbin/gomaf-test-memory-guard
Restart=always
RestartSec=2s
Nice=10
IOSchedulingClass=idle
MemoryMax=64M
OOMScoreAdjust=-300
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/gomaf-ops-alert@.service <<'EOF'
[Unit]
Description=GoMaf persistent failure visibility for %i

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/gomaf-ops-alert %i
UMask=0077
StateDirectory=gomaf-ops-health
StateDirectoryMode=0700
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
EOF

cat > /etc/systemd/system/gomaf-ops-health-check.service <<'EOF'
[Unit]
Description=GoMaf operational health and stale-job check
After=docker.service network-online.target
Wants=network-online.target
OnFailure=gomaf-ops-alert@%n.service

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/gomaf-ops-health-check
TimeoutStartSec=60s
UMask=0077
StateDirectory=gomaf-ops-health
StateDirectoryMode=0700
Nice=15
IOSchedulingClass=idle
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
EOF

cat > /etc/systemd/system/gomaf-ops-health-check.timer <<'EOF'
[Unit]
Description=Periodic GoMaf operational health check

[Timer]
OnBootSec=5m
OnUnitActiveSec=30m
RandomizedDelaySec=2m
Persistent=true
Unit=gomaf-ops-health-check.service

[Install]
WantedBy=timers.target
EOF

# Older rehabilitation revisions used drop-ins for units that now carry
# OnFailure directly in their canonical unit definitions. Remove only those
# known obsolete drop-ins; backup remains a separate installer-owned unit.
for unit in gomaf-storage-maintenance.service gomaf-aide-check.service gomaf-security-check.service gomaf-test-memory-guard.service; do
  rm -f "/etc/systemd/system/$unit.d/90-failure-visibility.conf"
  rmdir "/etc/systemd/system/$unit.d" 2>/dev/null || true
done

install -d -m 0755 /etc/systemd/system/gomaf-backup.service.d
cat > /etc/systemd/system/gomaf-backup.service.d/90-failure-visibility.conf <<'EOF'
[Unit]
OnFailure=gomaf-ops-alert@%n.service
EOF
chmod 0644 /etc/systemd/system/gomaf-backup.service.d/90-failure-visibility.conf

chmod 0644   /etc/systemd/system/gomaf-storage-maintenance.service   /etc/systemd/system/gomaf-storage-maintenance.timer   /etc/systemd/system/gomaf-aide-check.service   /etc/systemd/system/gomaf-aide-check.timer   /etc/systemd/system/gomaf-security-check.service   /etc/systemd/system/gomaf-security-check.timer   /etc/systemd/system/gomaf-testing.slice   /etc/systemd/system/gomaf-test-memory-guard.service   /etc/systemd/system/gomaf-ops-alert@.service   /etc/systemd/system/gomaf-ops-health-check.service   /etc/systemd/system/gomaf-ops-health-check.timer

systemd-analyze verify   /etc/systemd/system/gomaf-storage-maintenance.service   /etc/systemd/system/gomaf-storage-maintenance.timer   /etc/systemd/system/gomaf-aide-check.service   /etc/systemd/system/gomaf-aide-check.timer   /etc/systemd/system/gomaf-security-check.service   /etc/systemd/system/gomaf-security-check.timer   /etc/systemd/system/gomaf-testing.slice   /etc/systemd/system/gomaf-test-memory-guard.service   /etc/systemd/system/gomaf-ops-alert@.service   /etc/systemd/system/gomaf-ops-health-check.service   /etc/systemd/system/gomaf-ops-health-check.timer

systemctl daemon-reload
augenrules --load >/dev/null
fail2ban-client reload >/dev/null

if [[ ! -s /var/lib/aide/gomaf-aide.db ]]; then
  rm -f /var/lib/aide/gomaf-aide.db.new
  aide --init --config=/etc/aide/gomaf-aide.conf >/dev/null
  [[ -s /var/lib/aide/gomaf-aide.db.new ]] || { echo "AIDE init did not create database" >&2; exit 1; }
  install -m 0600 -o root -g root /var/lib/aide/gomaf-aide.db.new /var/lib/aide/gomaf-aide.db
  rm -f /var/lib/aide/gomaf-aide.db.new
fi

systemctl enable   gomaf-storage-maintenance.timer   gomaf-aide-check.timer   gomaf-security-check.timer   gomaf-test-memory-guard.service   gomaf-ops-health-check.timer >/dev/null

systemctl start gomaf-testing.slice
systemctl restart gomaf-test-memory-guard.service
systemctl start gomaf-storage-maintenance.timer
systemctl start gomaf-aide-check.timer

production_running=1
for c in gomaf-app-1 gomaf-db-1 gomaf-caddy-1; do
  docker ps --format '{{.Names}}' | grep -qx "$c" || production_running=0
done

if [[ "$production_running" -eq 1 ]]; then
  systemctl start gomaf-security-check.timer gomaf-ops-health-check.timer
  if [[ ! -e /var/run/reboot-required ]]; then
    systemctl start gomaf-ops-health-check.service
  else
    echo "INFO reboot is pending; immediate ops health check deferred but timer remains enabled"
  fi
else
  echo "INFO production stack is not fully running; security/ops-health timers enabled but immediate checks deferred"
fi

systemctl is-enabled --quiet gomaf-test-memory-guard.service
systemctl is-active --quiet gomaf-test-memory-guard.service
systemctl is-enabled --quiet gomaf-storage-maintenance.timer
systemctl is-enabled --quiet gomaf-aide-check.timer
systemctl is-enabled --quiet gomaf-security-check.timer
systemctl is-enabled --quiet gomaf-ops-health-check.timer

echo "GOMAF OPS SAFETY INSTALL PASS"
