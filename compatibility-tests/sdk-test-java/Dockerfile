FROM eclipse-temurin:17-jdk
WORKDIR /app

RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src/ src/

ENV FLOCI_ENDPOINT=http://floci:4566

RUN mkdir -p /results
ENTRYPOINT ["bash", "-c", "status=0; mvn test -q -Dorg.slf4j.simpleLogger.defaultLogLevel=warn || status=$?; if [ -d target/surefire-reports ]; then ls -la target/surefire-reports/; cp target/surefire-reports/TEST-*.xml /results/ 2>/dev/null || true; else echo 'No surefire-reports directory found'; fi; exit $status"]
