# Stage 1: Build
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src/ src/
RUN mvn clean package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:25-jre-alpine

ARG VERSION=latest
ENV FLOCI_VERSION=${VERSION}
ENV FLOCI_STORAGE_PERSISTENT_PATH=/app/data

ENV AWS_DEFAULT_REGION=us-east-1
ENV AWS_ACCESS_KEY_ID=test
ENV AWS_SECRET_ACCESS_KEY=test
ENV AWS_CONFIG_FILE=/etc/floci/aws/config

ENV GOSU_VERSION=1.17
ENV GOSU_AMD64_SHA256=bbc4136d03ab138b1ad66fa4fc051bafc6cc7ffae632b069a53657279a450de3
ENV GOSU_ARM64_SHA256=c3805a85d17f4454c23d7059bcb97e1ec1af272b90126e79ed002342de08389b
RUN set -eux; \
    apk add --no-cache shadow ca-certificates python3 py3-pip; \
    pip3 install --no-cache-dir --break-system-packages awscli boto3; \
    mv /usr/bin/aws /usr/bin/aws.real; \
    printf '#!/bin/sh\nexec /usr/bin/aws.real --endpoint-url=http://localhost:4566 "$@"\n' > /usr/bin/aws; \
    chmod +x /usr/bin/aws; \
    useradd -r -u 1001 -g root -d /app -s /sbin/nologin floci; \
    arch="$(apk --print-arch)"; \
    case "$arch" in \
        x86_64) gosuArch='amd64'; gosuSha256="$GOSU_AMD64_SHA256" ;; \
        aarch64) gosuArch='arm64'; gosuSha256="$GOSU_ARM64_SHA256" ;; \
        *) echo >&2 "unsupported arch: $arch"; exit 1 ;; \
    esac; \
    wget -q -O /usr/local/bin/gosu "https://github.com/tianon/gosu/releases/download/${GOSU_VERSION}/gosu-${gosuArch}"; \
    echo "${gosuSha256}  /usr/local/bin/gosu" | sha256sum -c -; \
    chmod +x /usr/local/bin/gosu

WORKDIR /app

RUN mkdir -p /app/data /etc/floci/aws \
    && printf '[default]\nendpoint_url = http://localhost:4566\n' > /etc/floci/aws/config \
    && chown 1001:root /app \
    && chmod "g+rwX" /app \
    && chown 1001:root /app/data

COPY --from=build /build/target/quarkus-app/ quarkus-app/
RUN chown -R 1001:root /app/quarkus-app

COPY --chmod=755 docker/entrypoint.sh /usr/local/bin/docker-entrypoint.sh
COPY --chmod=755 docker/localstack-parity.sh /usr/local/bin/localstack-parity.sh

VOLUME /app/data

EXPOSE 4566 6379-6399

HEALTHCHECK --interval=5s --timeout=3s --retries=5 \
    CMD wget -q --spider http://localhost:4566/_floci/health || exit 1

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["java", "--enable-preview", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/quarkus-app/quarkus-run.jar"]
