FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
COPY sidecars/graphql/pom.xml ./pom.xml
RUN mvn -B -DskipTests dependency:go-offline
COPY sidecars/graphql/src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/floci-graphql-sidecar-1.0.0.jar /app/sidecar.jar
COPY --from=build /build/target/dependency /app/lib
EXPOSE 8181
ENTRYPOINT ["java", "-cp", "/app/sidecar.jar:/app/lib/*", "io.github.hectorvent.floci.graphql.GraphqlSidecarServer"]
