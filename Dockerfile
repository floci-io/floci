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
WORKDIR /app

COPY --from=build /build/target/quarkus-app/ quarkus-app/

RUN mkdir -p /app/data
VOLUME /app/data

EXPOSE 4566 6379-6399

HEALTHCHECK --interval=5s --timeout=3s --retries=5 \
    CMD wget -q --spider http://localhost:4566/_floci/health || exit 1

ARG VERSION=latest
ENV FLOCI_VERSION=${VERSION}
ENV FLOCI_STORAGE_PERSISTENT_PATH=/app/data

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "quarkus-app/quarkus-run.jar"]
