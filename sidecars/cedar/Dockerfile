FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
COPY sidecars/cedar/pom.xml ./pom.xml
RUN mvn -B -DskipTests dependency:go-offline
COPY sidecars/cedar/src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 floci \
    && useradd --system --uid 10001 --gid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin floci
WORKDIR /app
COPY --chown=10001:10001 --from=build /build/target/floci-cedar-sidecar-1.0.0.jar /app/sidecar.jar
COPY --chown=10001:10001 --from=build /build/target/dependency /app/lib
USER 10001:10001
EXPOSE 8180
ENTRYPOINT ["java", "-cp", "/app/sidecar.jar:/app/lib/*", "io.github.hectorvent.floci.cedar.CedarSidecarServer"]
