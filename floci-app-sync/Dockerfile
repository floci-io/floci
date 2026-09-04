FROM eclipse-temurin:25-jre-alpine

WORKDIR /work

COPY target/quarkus-app/lib/ /work/lib/
COPY target/quarkus-app/*.jar /work/
COPY target/quarkus-app/app/ /work/app/
COPY target/quarkus-app/quarkus/ /work/quarkus/

EXPOSE 3010

ENTRYPOINT ["java", "-jar", "/work/quarkus-run.jar"]
