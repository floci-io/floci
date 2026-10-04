package io.github.hectorvent.floci.services.lambda.durable;

import com.github.dockerjava.api.DockerClient;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs a real Python function in a container. Its FAILED envelope closes the execution and the
 * sync Invoke reports it as a function error. The compat suite covers the full checkpoint loop.
 */
@QuarkusTest
class LambdaDurableFunctionsDockerIntegrationTest {

    private static final String LAMBDA = "/2015-03-31";
    private static final String DURABLE = "/2025-12-01";
    private static final String FUNCTION = "durable-docker-fn";
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void requireDockerAndFunction() throws Exception {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for Lambda durable function integration tests");
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "python3.14",
                    "Role": "%s",
                    "Handler": "lambda_function.handler",
                    "Timeout": 30,
                    "Publish": true,
                    "DurableConfig": {"ExecutionTimeout": 120, "RetentionPeriodInDays": 1},
                    "Code": {"ZipFile": "%s"}
                }
                """.formatted(FUNCTION, ROLE, handlerZipBase64()))
        .when()
            .post(LAMBDA + "/functions")
        .then()
            .statusCode(anyOf(is(201), is(409)));
    }

    @Test
    void aFailedExecutionIsReportedAsAFunctionError() {
        Response response = given()
                .header("X-Amz-Durable-Execution-Name", "docker-fail")
                .body("{\"fail\": true}")
                .post(LAMBDA + "/functions/" + FUNCTION + ":1/invocations");

        assertEquals(200, response.statusCode(), response.asString());
        assertEquals("Unhandled", response.getHeader("X-Amz-Function-Error"));
        assertEquals("asked to fail", response.jsonPath().getString("errorMessage"));
        assertEquals("TestFailure", response.jsonPath().getString("errorType"));

        given()
        .when()
            .get(DURABLE + "/functions/" + FUNCTION + "/durable-executions?Qualifier=1&DurableExecutionName=docker-fail")
        .then()
            .statusCode(200)
            .body("DurableExecutions", hasSize(1))
            .body("DurableExecutions[0].Status", equalTo("FAILED"));
    }

    static String handlerSource() {
        return """
            def handler(event, context):
                return {"Status": "FAILED",
                        "Error": {"ErrorMessage": "asked to fail", "ErrorType": "TestFailure"}}
            """;
    }

    private static String handlerZipBase64() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("lambda_function.py"));
            zip.write(handlerSource().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception expected) {
            // Docker is unreachable, so requireDockerAndFunction() skips the test rather than failing it.
            return false;
        }
    }
}
