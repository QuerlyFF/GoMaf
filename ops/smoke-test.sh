#!/usr/bin/env bash
set -Eeuo pipefail
source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
need_cmd curl
validate_prod_config
DOMAIN="$(env_get DOMAIN)"
BASE="https://$DOMAIN"

for path in / /news /rules /players /schedule /games /tournaments /rating /login /register /healthz; do
  code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 "$BASE$path")"
  [[ "$code" == 200 ]] || die "$path вернул HTTP $code"
done

headers="$(mktemp)"
captcha_headers="$(mktemp)"
redirect_headers="$(mktemp)"
http_headers="$(mktemp)"
spa_html="$(mktemp)"
asset_headers="$(mktemp)"
api_headers="$(mktemp)"
trap 'rm -f "$headers" "$captcha_headers" "$redirect_headers" "$http_headers" "$spa_html" "$asset_headers" "$api_headers"' EXIT

curl -fsS -D "$headers" -o "$spa_html" --max-time 15 "$BASE/news"
grep -qi '^strict-transport-security: max-age=31536000' "$headers" || die "Нет HSTS от Caddy."
grep -qi '^content-security-policy:' "$headers" || die "Нет CSP от приложения."
grep -qi "^content-security-policy:.*script-src 'self'" "$headers" || die "CSP не разрешает self-hosted SPA scripts."
if grep -Eqi 'unsafe-inline|unsafe-eval' "$headers"; then die "CSP содержит unsafe-inline/unsafe-eval."; fi
grep -qi '^x-frame-options: DENY' "$headers" || die "Нет X-Frame-Options: DENY."
grep -qi '^x-content-type-options: nosniff' "$headers" || die "Нет X-Content-Type-Options: nosniff."
if grep -qi '^server:' "$headers"; then die "Публичный ответ раскрывает Server header."; fi

asset_path="$(grep -oE '/assets/[^"'"'"' ]+[.]js' "$spa_html" | head -n1 || true)"
[[ -n "$asset_path" ]] || die "SPA index не содержит fingerprinted JS asset."
curl -fsS -D "$asset_headers" -o /dev/null --max-time 15 "$BASE$asset_path"
grep -qi '^content-type:.*javascript' "$asset_headers" || die "SPA JS asset имеет неожиданный Content-Type."
grep -Eqi '^cache-control:.*max-age=31536000.*immutable' "$asset_headers" || die "Fingerprint asset не имеет immutable cache policy."

curl -fsS -D "$api_headers" -o /dev/null --max-time 15 "$BASE/api/v1/session"
grep -qi '^content-type: application/json' "$api_headers" || die "Session API не отдаёт JSON."
api_unknown_headers="$(curl -sS -D - -o /dev/null --max-time 15 --max-redirs 0 "$BASE/api/v1/definitely-not-a-route" || true)"
printf '%s\n' "$api_unknown_headers" | grep -qi '^content-type: application/json' || die "Unknown API path был преобразован не в API-ответ."
if printf '%s\n' "$api_unknown_headers" | grep -qi '^content-type: text/html'; then die "Unknown API path попал в SPA fallback."; fi

curl -fsS -D "$captcha_headers" -o /dev/null --max-time 15 "$BASE/captcha/login"
grep -qi '^content-type: image/png' "$captcha_headers" || die "CAPTCHA не отдана как image/png."
grep -qi '^cache-control:.*no-store' "$captcha_headers" || die "Для CAPTCHA отсутствует no-store."

for path in /profile /admin /admin/moderators; do
  : > "$redirect_headers"
  code="$(curl -sS -D "$redirect_headers" -o /dev/null -w '%{http_code}' --max-time 15 --max-redirs 0 "$BASE$path" || true)"
  [[ "$code" == 302 || "$code" == 303 ]] || die "$path без авторизации должен перенаправлять на login, код $code."
  location="$(awk 'BEGIN{IGNORECASE=1} /^Location:/ {sub(/^[^:]+:[[:space:]]*/, ""); sub(/\r$/, ""); print; exit}' "$redirect_headers")"
  [[ "$location" == https://$DOMAIN/* ]] || die "$path сформировал не-HTTPS Location: ${location:-missing}."
done

for path in /.env /.env.local /.git/config /actuator /actuator/health /wp-admin /phpmyadmin /server-status; do
  code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 --max-redirs 0 "$BASE$path" || true)"
  [[ "$code" == 404 ]] || die "Edge probe block: $path ожидал 404, получено $code."
done

code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 "$BASE/forbidden" || true)"
[[ "$code" == 403 ]] || die "/forbidden должен вернуть HTTP 403, получено $code."

csrf_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 -X POST \
  -H 'Content-Type: application/json' \
  --data '{"username":"csrf_probe","password":"strong-password","passwordConfirm":"strong-password","captcha":"ABCDE"}' \
  "$BASE/api/v1/auth/register")"
[[ "$csrf_code" == 403 ]] || die "POST /api/v1/auth/register без CSRF должен вернуть 403, получено $csrf_code."

http_code="$(curl -sS -D "$http_headers" -o /dev/null -w '%{http_code}' --max-time 15 --max-redirs 0 "http://$DOMAIN/login" || true)"
[[ "$http_code" == 301 || "$http_code" == 308 ]] || die "HTTP не перенаправляется на HTTPS (код $http_code)."
http_location="$(awk 'BEGIN{IGNORECASE=1} /^Location:/ {sub(/^[^:]+:[[:space:]]*/, ""); sub(/\r$/, ""); print; exit}' "$http_headers")"
[[ "$http_location" == https://$DOMAIN/* ]] || die "HTTP redirect ведёт не на HTTPS: ${http_location:-missing}."

log "Production SPA/API smoke-test PASS для $DOMAIN"
