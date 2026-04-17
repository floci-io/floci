FROM maven:3.9.9-eclipse-temurin-17-slim
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src/ src/
RUN mvn test-compile -q

ENV FLOCI_ENDPOINT=http://floci:4566

RUN mkdir -p /results
ENTRYPOINT ["bash", "-c", "status=0; mvn test -q -Dorg.slf4j.simpleLogger.defaultLogLevel=warn || status=$?; if [ -d target/surefire-reports ]; then cp target/surefire-reports/TEST-*.xml /results/ 2>/dev/null || true; fi; exit $status"]
