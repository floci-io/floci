package io.floci.aws.services.lambda;

import io.floci.aws.core.common.docker.ContainerDetector;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Runs a {@code provided.al2023} function under the process executor: its bootstrap, a shell loop
 * that polls the Runtime API with curl, runs on this machine and answers the invocation.
 */
@QuarkusTest
@TestProfile(LambdaProcessExecutorIntegrationTest.ProcessExecutorProfile.class)
@EnabledIf("canRunBootstrapsHere")
class LambdaProcessExecutorIntegrationTest {

    private static final String BASE_PATH = "/2015-03-31";
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";

    private static final String BOOTSTRAP = """
            #!/bin/sh
            set -eu
            api="http://$AWS_LAMBDA_RUNTIME_API/2018-06-01/runtime/invocation"
            while true; do
              headers=$(mktemp)
              event=$(curl -sS -D "$headers" "$api/next")
              request_id=$(grep -i '^lambda-runtime-aws-request-id:' "$headers" | tr -d '\\r' | cut -d' ' -f2)
              rm -f "$headers"
              curl -sS -X POST "$api/$request_id/response" \\
                  -d "{\\"event\\":$event,\\"handler\\":\\"$_HANDLER\\",\\"endpoint\\":\\"$AWS_ENDPOINT_URL\\"}" > /dev/null
            done
            """;

    public static class ProcessExecutorProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.lambda.executor", "process");
        }
    }

    /** The bootstrap needs a POSIX shell and curl, and Floci refuses this executor inside a container. */
    static boolean canRunBootstrapsHere() {
        String path = System.getenv("PATH");
        boolean hasCurl = path != null && Arrays.stream(path.split(File.pathSeparator))
                .anyMatch(dir -> Files.isExecutable(Path.of(dir, "curl")));
        return File.separatorChar == '/' && hasCurl && !new ContainerDetector().isRunningInContainer();
    }

    @Test
    void aProvidedRuntimeFunctionAnswersAnInvocation() throws IOException {
        String functionName = "process-executor-" + UUID.randomUUID().toString().substring(0, 8);
        given()
                .contentType("application/json")
                .body("""
                    {
                      "FunctionName": "%s",
                      "Runtime": "provided.al2023",
                      "Role": "%s",
                      "Handler": "function.handler",
                      "Timeout": 10,
                      "Code": {"ZipFile": "%s"}
                    }
                    """.formatted(functionName, ROLE, bootstrapZipBase64()))
        .when()
                .post(BASE_PATH + "/functions")
        .then()
                .statusCode(201);
        try {
            given()
                    .contentType("application/json")
                    .body("{\"n\":1}")
            .when()
                    .post(BASE_PATH + "/functions/" + functionName + "/invocations")
            .then()
                    .statusCode(200)
                    .body("event.n", equalTo(1))
                    .body("handler", equalTo("function.handler"))
                    .body("endpoint", equalTo("http://127.0.0.1:" + RestAssured.port));
        } finally {
            given().when().delete(BASE_PATH + "/functions/" + functionName);
        }
    }

    private static String bootstrapZipBase64() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("bootstrap"));
            zip.write(BOOTSTRAP.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
