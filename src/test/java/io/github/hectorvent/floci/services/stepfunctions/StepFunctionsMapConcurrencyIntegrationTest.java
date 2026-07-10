package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies a Map state runs its iterations concurrently (honoring MaxConcurrency) while preserving
 * item order in the results. Each iteration Waits one second; with the default (unlimited)
 * concurrency N iterations finish in ~1s rather than ~Ns, and MaxConcurrency=1 runs them strictly
 * sequentially. Order preservation is asserted independently of timing.
 */
@QuarkusTest
class StepFunctionsMapConcurrencyIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String mapDef(Integer maxConcurrency) {
        String mc = maxConcurrency == null ? "" : "\"MaxConcurrency\":" + maxConcurrency + ",";
        return "{\"StartAt\":\"M\",\"States\":{"
             + "\"M\":{\"Type\":\"Map\",\"ItemsPath\":\"$.items\"," + mc
             + "\"ItemProcessor\":{\"StartAt\":\"W\",\"States\":{"
             + "\"W\":{\"Type\":\"Wait\",\"Seconds\":1,\"Next\":\"P\"},"
             + "\"P\":{\"Type\":\"Pass\",\"End\":true}}},"
             + "\"End\":true}}}";
    }

    private static String items(int n) {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"i\":").append(i).append('}');
        }
        return sb.append("]}").toString();
    }

    private void assertOrderedItems(String output, int n) throws Exception {
        JsonNode arr = mapper.readTree(output);
        assertEquals(n, arr.size());
        for (int i = 0; i < n; i++) {
            assertEquals(i, arr.get(i).path("i").asInt(), "results must be in input order");
        }
    }

    @Test
    void mapRunsIterationsConcurrentlyByDefaultAndPreservesOrder() throws Exception {
        long start = System.currentTimeMillis();
        String output = run(mapDef(null), items(4));
        long elapsedMs = System.currentTimeMillis() - start;

        assertOrderedItems(output, 4);
        // Four one-second Waits complete in ~1s when run concurrently; strictly sequential would be ~4s.
        assertTrue(elapsedMs < 3000,
                "Map iterations should run concurrently (elapsed " + elapsedMs + "ms, expected < 3000)");
    }

    @Test
    void mapWithMaxConcurrencyOneRunsSequentially() throws Exception {
        long start = System.currentTimeMillis();
        String output = run(mapDef(1), items(3));
        long elapsedMs = System.currentTimeMillis() - start;

        assertOrderedItems(output, 3);
        // MaxConcurrency=1 serialises the three 1s Waits (~3s), unlike the ~1s of the concurrent path.
        assertTrue(elapsedMs >= 2500,
                "MaxConcurrency=1 should run sequentially (elapsed " + elapsedMs + "ms, expected >= 2500)");
    }

    private String run(String definition, String input) throws InterruptedException {
        String name = "map-concurrency-" + System.nanoTime();
        Response create = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"name\":\"" + name + "\",\"definition\":" + quote(definition) + ",\"roleArn\":\"" + ROLE_ARN + "\"}")
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
            Response d = given()
                    .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                    .contentType(SFN_CONTENT_TYPE)
                    .body("{\"executionArn\":\"" + execArn + "\"}")
                    .when().post("/");
            String status = d.jsonPath().getString("status");
            if ("SUCCEEDED".equals(status)) {
                return d.jsonPath().getString("output");
            }
            if ("FAILED".equals(status)) {
                fail("Execution failed: " + d.body().asString());
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
