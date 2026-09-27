#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
fail(){ printf 'WP11-023 ONLINE PREPARE FAIL: %s\n' "$*" >&2; exit 1; }
log(){ printf '[wp11-023-online] %s\n' "$*" >&2; }
for c in docker curl python3 sha256sum; do command -v "$c" >/dev/null 2>&1 || fail "$c missing"; done
docker info >/dev/null 2>&1 || fail 'Docker daemon unavailable'

ARCH="$(docker info --format '{{.Architecture}}' 2>/dev/null || true)"
case "$ARCH" in
  x86_64|amd64) ADOPTIUM_ARCH=x64 ;;
  *) fail "unsupported host architecture: $ARCH; WP11-023 production candidate is amd64-only" ;;
esac

resolve_ref() {
  local tag="$1" expected_repo="$2" json ref
  log "resolve immutable digest: $tag"
  docker pull "$tag" >/dev/null
  json="$(docker image inspect "$tag" --format '{{json .RepoDigests}}')"
  ref="$(python3 - "$expected_repo" "$json" <<'PY'
import json,re,sys
repo=sys.argv[1]
vals=json.loads(sys.argv[2])
for item in vals:
    if item.startswith(repo+'@sha256:') and re.fullmatch(re.escape(repo)+r'@sha256:[0-9a-f]{64}', item):
        print(item); break
else:
    raise SystemExit(1)
PY
)" || fail "cannot resolve digest for $tag"
  # Preserve the audited tag for traceability while binding it to the
  # registry digest. Docker accepts name:tag@sha256:<digest> for pull/run/FROM.
  printf '%s@%s' "$tag" "${ref##*@}"
}

NODE_TAG='node:22.23.2-bookworm-slim'
MAVEN_TAG='maven:3.9.16-eclipse-temurin-21-noble'
RUNTIME_TAG='eclipse-temurin:21.0.12_8-jre-noble'
POSTGRES_TAG='postgres:17.11-alpine3.24'
CADDY_TAG='caddy:2.11.4-alpine'
PLAYWRIGHT_TAG='mcr.microsoft.com/playwright:v1.55.0-noble'
SYFT_TAG='anchore/syft:latest'
TRIVY_TAG='aquasec/trivy:latest'

NODE_IMAGE="$(resolve_ref "$NODE_TAG" node)"
MAVEN_IMAGE="$(resolve_ref "$MAVEN_TAG" maven)"
RUNTIME_BASE_IMAGE="$(resolve_ref "$RUNTIME_TAG" eclipse-temurin)"
POSTGRES_IMAGE="$(resolve_ref "$POSTGRES_TAG" postgres)"
CADDY_IMAGE="$(resolve_ref "$CADDY_TAG" caddy)"
PLAYWRIGHT_IMAGE="$(resolve_ref "$PLAYWRIGHT_TAG" mcr.microsoft.com/playwright)"
SYFT_IMAGE="$(resolve_ref "$SYFT_TAG" anchore/syft)"
TRIVY_IMAGE="$(resolve_ref "$TRIVY_TAG" aquasec/trivy)"

REL_ENCODED='jdk-21.0.12.1%2B1'
REL_TEXT='jdk-21.0.12.1+1'
BASE="https://github.com/adoptium/temurin21-binaries/releases/download/$REL_ENCODED"
JDK="OpenJDK21U-jdk_${ADOPTIUM_ARCH}_linux_hotspot_21.0.12.1_1.tar.gz"
JRE="OpenJDK21U-jre_${ADOPTIUM_ARCH}_linux_hotspot_21.0.12.1_1.tar.gz"

fetch_vendor_sha() {
  local name="$1" line hash
  line="$(curl -fL --proto '=https' --tlsv1.2 --retry 4 --retry-all-errors \
      "$BASE/$name.sha256.txt")" || fail "cannot fetch Adoptium checksum for $name"
  hash="$(printf '%s\n' "$line" | awk 'NF {print $1; exit}')"
  [[ "$hash" =~ ^[0-9a-fA-F]{64}$ ]] || fail "invalid Adoptium SHA-256 for $name"
  printf '%s' "${hash,,}"
}

JDK_SHA256="$(fetch_vendor_sha "$JDK")"
JRE_SHA256="$(fetch_vendor_sha "$JRE")"
TMP="$(mktemp -d)"
cleanup(){ rm -rf -- "$TMP"; }
trap cleanup EXIT INT TERM
for spec in "$JDK:$JDK_SHA256" "$JRE:$JRE_SHA256"; do
  name="${spec%%:*}"; sha="${spec##*:}"
  log "verify official Adoptium asset: $name"
  curl -fL --proto '=https' --tlsv1.2 --retry 4 --retry-all-errors "$BASE/$name" -o "$TMP/$name"
  printf '%s  %s\n' "$sha" "$TMP/$name" | sha256sum -c - >/dev/null \
    || fail "Adoptium asset checksum mismatch: $name"
done
rm -rf -- "$TMP"; trap - EXIT INT TERM

# Regenerate package-lock through the already digest-pinned Node image. No host
# Node/npm installation is required and the resulting lockfile is part of the
# candidate archive.
SRC_UID="$(stat -c %u "$ROOT")"; SRC_GID="$(stat -c %g "$ROOT")"
log 'update Vite to 6.4.3 and regenerate package-lock'
docker run --rm --user "$SRC_UID:$SRC_GID" \
  -e HOME=/tmp -v "$ROOT/frontend:/work" -w /work \
  "$NODE_IMAGE" \
  npm install --package-lock-only --save-dev --save-exact vite@6.4.3 --no-audit --no-fund
python3 - "$ROOT/frontend/package-lock.json" <<'PY'
import json,sys
p=json.load(open(sys.argv[1], encoding='utf-8'))
root=p['packages']['']['devDependencies']['vite']
locked=p['packages']['node_modules/vite']['version']
esbuild=p['packages']['node_modules/esbuild']['version']
if root!='6.4.3' or locked!='6.4.3': raise SystemExit('Vite lock is not exactly 6.4.3')
parts=[int(x) for x in esbuild.split('.')[:3]]
if parts < [0,25,0]: raise SystemExit('esbuild remains below 0.25.0: '+esbuild)
print(f'lock verified: vite={locked} esbuild={esbuild}')
PY

cat > "$ROOT/.wp11-023-security-tools.env" <<ENV
FORMAT=1
ADOPTIUM_RELEASE=$REL_TEXT
ADOPTIUM_BASE_URL=$BASE
TEMURIN_JDK_FILE=$JDK
TEMURIN_JDK_SHA256=$JDK_SHA256
TEMURIN_JRE_FILE=$JRE
TEMURIN_JRE_SHA256=$JRE_SHA256
NODE_IMAGE=$NODE_IMAGE
MAVEN_IMAGE=$MAVEN_IMAGE
RUNTIME_BASE_IMAGE=$RUNTIME_BASE_IMAGE
POSTGRES_IMAGE=$POSTGRES_IMAGE
CADDY_IMAGE=$CADDY_IMAGE
PLAYWRIGHT_IMAGE=$PLAYWRIGHT_IMAGE
SYFT_IMAGE=$SYFT_IMAGE
TRIVY_IMAGE=$TRIVY_IMAGE
ENV
chmod 0644 "$ROOT/.wp11-023-security-tools.env"

cat > "$ROOT/Dockerfile" <<EOF_DOCKER
FROM $NODE_IMAGE AS frontend-build
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run lint \\
    && npm run typecheck \\
    && npm run test \\
    && npm run build \\
    && test -z "\$(find dist -type f -name '*.map' -print -quit)"

FROM $MAVEN_IMAGE AS backend-build
ARG TEMURIN_JDK_SHA256=$JDK_SHA256
ARG TEMURIN_JDK_FILE=$JDK
ARG TEMURIN_RELEASE_URL=$BASE
RUN curl -fL --proto '=https' --tlsv1.2 --retry 4 --retry-all-errors \
      "\$TEMURIN_RELEASE_URL/\$TEMURIN_JDK_FILE" -o /tmp/temurin-jdk.tgz \\
    && echo "\$TEMURIN_JDK_SHA256  /tmp/temurin-jdk.tgz" | sha256sum -c - \\
    && rm -rf /opt/java/openjdk \\
    && mkdir -p /opt/java/openjdk \\
    && tar -xzf /tmp/temurin-jdk.tgz -C /opt/java/openjdk --strip-components=1 \\
    && rm -f /tmp/temurin-jdk.tgz \\
    && java -version 2>&1 | grep -F '21.0.12.1'
WORKDIR /workspace/backend
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend-build /workspace/frontend/dist/ ./target/generated-resources/spa/
RUN test -f target/generated-resources/spa/index.html \\
    && test -n "\$(find target/generated-resources/spa/assets -maxdepth 1 -type f -name '*.js' -print -quit)" \\
    && mvn -B verify \\
    && jar tf target/classic-mafia-3.1.0.jar | grep -q '^BOOT-INF/classes/static/index.html\$' \\
    && jar tf target/classic-mafia-3.1.0.jar | grep -q '^BOOT-INF/classes/static/assets/.*[.]js\$'

FROM $RUNTIME_BASE_IMAGE AS runtime
ARG TEMURIN_JRE_SHA256=$JRE_SHA256
ARG TEMURIN_JRE_FILE=$JRE
ARG TEMURIN_RELEASE_URL=$BASE
WORKDIR /app
RUN apt-get update \\
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl fontconfig fonts-dejavu-core \\
    && rm -rf /var/lib/apt/lists/* \\
    && curl -fL --proto '=https' --tlsv1.2 --retry 4 --retry-all-errors \
      "\$TEMURIN_RELEASE_URL/\$TEMURIN_JRE_FILE" -o /tmp/temurin-jre.tgz \\
    && echo "\$TEMURIN_JRE_SHA256  /tmp/temurin-jre.tgz" | sha256sum -c - \\
    && rm -rf /opt/java/openjdk \\
    && mkdir -p /opt/java/openjdk \\
    && tar -xzf /tmp/temurin-jre.tgz -C /opt/java/openjdk --strip-components=1 \\
    && rm -f /tmp/temurin-jre.tgz \\
    && java -version 2>&1 | grep -F '21.0.12.1' \\
    && useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin appuser
COPY --from=backend-build /workspace/backend/target/classic-mafia-3.1.0.jar /app/app.jar
COPY --chmod=0555 docker/app-entrypoint.sh /app/entrypoint.sh
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -Djava.io.tmpdir=/tmp"
USER 10001
EXPOSE 8787
ENTRYPOINT ["/app/entrypoint.sh"]
EOF_DOCKER

# Pin all production Compose infrastructure images too. The deployment and
# migration scripts read the same signed image-lock manifest.
python3 - "$ROOT/compose.prod.yaml" "$POSTGRES_IMAGE" "$CADDY_IMAGE" <<'PY'
from pathlib import Path
import sys,re
p=Path(sys.argv[1]); postgres=sys.argv[2]; caddy=sys.argv[3]
s=p.read_text('utf-8')
s=re.sub(r'(?m)^(\s*image:\s*)postgres:17\.11-alpine3\.24(?:@sha256:[0-9a-f]{64})?\s*$', lambda m:m.group(1)+postgres, s)
s=re.sub(r'(?m)^(\s*image:\s*)caddy:2\.11\.4-alpine(?:@sha256:[0-9a-f]{64})?\s*$', lambda m:m.group(1)+caddy, s)
p.write_text(s, encoding='utf-8')
PY

# Python's stdlib ZIP extractor does not preserve Unix executable bits.
# Restore only the repository's allow-listed script surfaces before gates that
# intentionally verify executability. This is bounded by normalize-source-permissions.sh.
bash ops/normalize-source-permissions.sh "$ROOT" >/dev/null

log 'run source/UI/WP11-023 security audits on finalized source'
bash ops/source-audit.sh
bash ops/ui-ux-audit.sh
bash ops/wp11-023-security-audit.sh
printf 'WP11-023 ONLINE PREPARE PASS\n'
