#!/bin/sh
set -eu

# Application and security/audit logs are persisted to a writable bind mount.
# Create the canonical files before Java starts so readiness/audit does not race
# Logback appender initialization. umask keeps them private by default.
umask 027
LOG_DIR="${APP_LOG_DIR:-/tmp/gomaf-logs}"
mkdir -p "$LOG_DIR"
: >> "$LOG_DIR/application.log"
: >> "$LOG_DIR/audit.log"
chmod 0640 "$LOG_DIR/application.log" "$LOG_DIR/audit.log"

exec java -XX:MaxRAMPercentage=75.0 -jar /app/app.jar
