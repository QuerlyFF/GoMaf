FROM node:22.23.2-bookworm-slim@sha256:83f487e0a63425e5b4d146fb5e5be574bcbe1b7b843d3ebafdd95eaf7767a7e5 AS frontend-build
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run lint \
    && npm run typecheck \
    && npm run test \
    && npm run build \
    && test -z "$(find dist -type f -name '*.map' -print -quit)"

FROM maven:3.9.16-eclipse-temurin-21-noble@sha256:a972570be789ee5c9fa23446a8914ac7327560b5c022f662cfa9452aef829f18 AS backend-build
ARG TEMURIN_JDK_SHA256=ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94
ARG TEMURIN_JDK_FILE=OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz
ARG TEMURIN_RELEASE_URL=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1
RUN curl -fL --proto '=https' --tlsv1.2 --retry 4 --retry-all-errors       "$TEMURIN_RELEASE_URL/$TEMURIN_JDK_FILE" -o /tmp/temurin-jdk.tgz \
    && echo "$TEMURIN_JDK_SHA256  /tmp/temurin-jdk.tgz" | sha256sum -c - \
    && rm -rf /opt/java/openjdk \
    && mkdir -p /opt/java/openjdk \
    && tar -xzf /tmp/temurin-jdk.tgz -C /opt/java/openjdk --strip-components=1 \
    && rm -f /tmp/temurin-jdk.tgz \
    && java -version 2>&1 | grep -F '21.0.12.1'
WORKDIR /workspace/backend
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend-build /workspace/frontend/dist/ ./target/generated-resources/spa/
RUN test -f target/generated-resources/spa/index.html \
    && test -n "$(find target/generated-resources/spa/assets -maxdepth 1 -type f -name '*.js' -print -quit)" \
    && mvn -B verify \
    && jar tf target/classic-mafia-3.1.0.jar | grep -q '^BOOT-INF/classes/static/index.html$' \
    && jar tf target/classic-mafia-3.1.0.jar | grep -q '^BOOT-INF/classes/static/assets/.*[.]js$'

FROM eclipse-temurin:21.0.12_8-jre-noble@sha256:d35199d74a3b2dff1bfb435d9adde4ef974e74a7d2a4fcb3079063c55926edb5 AS runtime
ARG TEMURIN_JRE_SHA256=2413149700df0f7d440500a84a8f764c535f21e5a5e87d38328b64eec2c5b500
ARG TEMURIN_JRE_FILE=OpenJDK21U-jre_x64_linux_hotspot_21.0.12.1_1.tar.gz
ARG TEMURIN_RELEASE_URL=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1
WORKDIR /app
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl fontconfig fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fL --proto '=https' --tlsv1.2 --retry 4 --retry-all-errors       "$TEMURIN_RELEASE_URL/$TEMURIN_JRE_FILE" -o /tmp/temurin-jre.tgz \
    && echo "$TEMURIN_JRE_SHA256  /tmp/temurin-jre.tgz" | sha256sum -c - \
    && rm -rf /opt/java/openjdk \
    && mkdir -p /opt/java/openjdk \
    && tar -xzf /tmp/temurin-jre.tgz -C /opt/java/openjdk --strip-components=1 \
    && rm -f /tmp/temurin-jre.tgz \
    && java -version 2>&1 | grep -F '21.0.12.1' \
    && useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin appuser
COPY --from=backend-build /workspace/backend/target/classic-mafia-3.1.0.jar /app/app.jar
COPY --chmod=0555 docker/app-entrypoint.sh /app/entrypoint.sh
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -Djava.io.tmpdir=/tmp"
USER 10001
EXPOSE 8787
ENTRYPOINT ["/app/entrypoint.sh"]
