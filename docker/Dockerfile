# Stage 1: Build
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /build

RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src/ src/
RUN mvn clean package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:25-jre-noble

ARG VERSION=latest
ENV FLOCI_VERSION=${VERSION}
ENV FLOCI_STORAGE_PERSISTENT_PATH=/app/data

ENV AWS_DEFAULT_REGION=us-east-1
ENV AWS_ACCESS_KEY_ID=test
ENV AWS_SECRET_ACCESS_KEY=test
ENV AWS_CONFIG_FILE=/etc/floci/aws/config

# Privilege drop happens in the entrypoint through coreutils chroot, so no gosu
# download; wget stays for the HEALTHCHECK below.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates passwd python3 python3-pip wget; \
    rm -rf /var/lib/apt/lists/*; \
    pip3 install --no-cache-dir --break-system-packages awscli boto3; \
    aws_bin="$(command -v aws)"; \
    mv "$aws_bin" /usr/bin/aws.real; \
    printf '#!/bin/sh\nexec /usr/bin/aws.real --endpoint-url=http://localhost:4566 "$@"\n' > /usr/bin/aws; \
    chmod +x /usr/bin/aws; \
    useradd -r -u 1001 -g root -d /app -s /sbin/nologin floci

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

EXPOSE 4566

# Need to expose separately due to https://github.com/floci-io/floci/issues/2095.
# This preseres compatibility with https://github.com/docker-java/docker-java/blob/002b6eb7aa2b892be729c7c2a5e95f782924db38/docker-java-api/src/main/java/com/github/dockerjava/api/model/ExposedPort.java#L119
EXPOSE 6379
EXPOSE 6380
EXPOSE 6381
EXPOSE 6382
EXPOSE 6383
EXPOSE 6384
EXPOSE 6385
EXPOSE 6386
EXPOSE 6387
EXPOSE 6388
EXPOSE 6389
EXPOSE 6390
EXPOSE 6391
EXPOSE 6392
EXPOSE 6393
EXPOSE 6394
EXPOSE 6395
EXPOSE 6396
EXPOSE 6397
EXPOSE 6398
EXPOSE 6399

HEALTHCHECK --interval=5s --timeout=3s --retries=5 \
    CMD wget -q --spider http://localhost:4566/_floci/health || exit 1

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/quarkus-app/quarkus-run.jar"]
