package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ASL takes a {@code string_sampler} wherever it takes a Reference Path, and a Context Object path
 * ({@code $$}) is one of its spellings, so the fields below read the Context Object as readily as
 * the state input. Each case narrows the state input with {@code InputPath} or starts from an input
 * that does not carry the value, so a resolver that only ever sees the state input cannot pass.
 */
@QuarkusTest
class StepFunctionsContextObjectPathIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void inputPathSelectsFromTheContextObject() throws Exception {
        String definition = """
                {"StartAt":"P","States":{"P":{"Type":"Pass",
                  "InputPath":"$$.Execution.Input.payload","End":true}}}
                """;

        JsonNode output = mapper.readTree(run(definition, "{\"payload\":{\"kept\":true}}"));

        assertTrue(output.path("kept").asBoolean(), "InputPath must select the context subtree");
    }

    @Test
    void outputPathSelectsFromTheContextObject() throws Exception {
        String definition = """
                {"StartAt":"P","States":{"P":{"Type":"Pass",
                  "OutputPath":"$$.Execution.Input.wanted","End":true}}}
                """;

        JsonNode output = mapper.readTree(run(definition, "{\"wanted\":{\"value\":7},\"noise\":1}"));

        assertEquals(7, output.path("value").asInt());
    }

    /**
     * A context path that does not resolve leaves the wait at zero, which is indistinguishable from
     * a Wait state that never waited, so the duration is what proves the value was read.
     */
    @Test
    void waitSecondsPathReadsTheContextObject() throws Exception {
        String definition = """
                {"StartAt":"W","States":{"W":{"Type":"Wait",
                  "InputPath":"$.payload","SecondsPath":"$$.Execution.Input.delay",
                  "Next":"P"},"P":{"Type":"Pass","End":true}}}
                """;

        Instant started = Instant.now();
        run(definition, "{\"delay\":1,\"payload\":{\"kept\":true}}");
        Duration elapsed = Duration.between(started, Instant.now());

        assertTrue(elapsed.toMillis() >= 900,
                "Wait must honour the context path, waited " + elapsed.toMillis() + "ms");
    }

    @Test
    void waitTimestampPathReadsTheContextObject() throws Exception {
        String timestamp = Instant.now().plusSeconds(1).toString();
        String definition = """
                {"StartAt":"W","States":{"W":{"Type":"Wait",
                  "InputPath":"$.payload","TimestampPath":"$$.Execution.Input.until",
                  "Next":"P"},"P":{"Type":"Pass","End":true}}}
                """;

        Instant started = Instant.now();
        run(definition, "{\"until\":\"" + timestamp + "\",\"payload\":{\"kept\":true}}");
        Duration elapsed = Duration.between(started, Instant.now());

        assertTrue(elapsed.toMillis() >= 500,
                "Wait must honour the context path, waited " + elapsed.toMillis() + "ms");
    }

    private String run(String definition, String input) throws InterruptedException {
        String name = "context-path-" + System.nanoTime();
        Response create = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"name\":\"" + name + "\",\"definition\":" + quote(definition)
                        + ",\"roleArn\":\"" + ROLE_ARN + "\"}")
                .when().post("/");
        create.then().statusCode(200);
        String smArn = create.jsonPath().getString("stateMachineArn");

        Response start = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"stateMachineArn\":\"" + smArn + "\",\"input\":" + quote(input) + "}")
                .when().post("/");
        start.then().statusCode(200);
        String execArn = start.jsonPath().getString("executionArn");

        for (int i = 0; i < 100; i++) {
            Response describe = given()
                    .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                    .contentType(SFN_CONTENT_TYPE)
                    .body("{\"executionArn\":\"" + execArn + "\"}")
                    .when().post("/");
            String status = describe.jsonPath().getString("status");
            if ("SUCCEEDED".equals(status)) {
                return describe.jsonPath().getString("output");
            }
            if ("FAILED".equals(status)) {
                fail("Execution failed: " + describe.body().asString());
            }
            Thread.sleep(100);
        }
        fail("Execution did not complete");
        return null;
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
