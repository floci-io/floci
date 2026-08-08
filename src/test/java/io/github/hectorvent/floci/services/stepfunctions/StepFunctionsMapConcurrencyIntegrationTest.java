package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Wire-level coverage for Map concurrency configuration and ordered results. The scheduler's
 * concurrency, bounded-window, cancellation, and fail-fast behavior is covered deterministically
 * by {@link MapIterationSchedulerTest} rather than with wall-clock assertions.
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
        return mapDefWithConcurrencyField(mc);
    }

    private static String mapDefWithConcurrencyField(String concurrencyField) {
        return "{\"StartAt\":\"M\",\"States\":{"
             + "\"M\":{\"Type\":\"Map\",\"ItemsPath\":\"$.items\"," + concurrencyField
             + "\"ItemProcessor\":{\"StartAt\":\"P\",\"States\":{"
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
    void mapPreservesInputOrderWithDefaultConcurrency() throws Exception {
        assertOrderedItems(run(mapDef(null), items(50)), 50);
    }

    @Test
    void mapAcceptsStaticMaxConcurrency() throws Exception {
        assertOrderedItems(run(mapDef(1), items(5)), 5);
    }

    @Test
    void mapResolvesMaxConcurrencyPathFromItsInput() throws Exception {
        String input = "{\"ignored\":true,\"payload\":{"
                + "\"config\":{\"max-limit\":2},\"items\":["
                + "{\"i\":0},{\"i\":1},{\"i\":2},{\"i\":3}]}}";

        assertOrderedItems(run(mapDefWithConcurrencyField(
                "\"InputPath\":\"$.payload\","
                        + "\"MaxConcurrencyPath\":\"$.config.['max-limit']\","), input), 4);
    }

    @Test
    void jsonataMapEvaluatesMaxConcurrencyExpression() throws Exception {
        String definition = """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "M",
                  "States": {
                    "M": {
                      "Type": "Map",
                      "Items": "{% $states.input.items %}",
                      "MaxConcurrency": "{% $states.input.limit %}",
                      "ItemProcessor": {
                        "StartAt": "P",
                        "States": {"P": {"Type": "Pass", "End": true}}
                      },
                      "End": true
                    }
                  }
                }
                """;

        assertOrderedItems(run(definition,
                "{\"limit\":3,\"items\":[{\"i\":0},{\"i\":1},{\"i\":2},{\"i\":3}]}"), 4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "-1", "1.5", "$states.input.missing"})
    void invalidJsonataMaxConcurrencyIsCatchableAsQueryEvaluationError(String expression) throws Exception {
        String definition = """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "M",
                  "States": {
                    "M": {
                      "Type": "Map",
                      "Items": "{% $states.input.items %}",
                      "MaxConcurrency": "{% __EXPRESSION__ %}",
                      "ItemProcessor": {
                        "StartAt": "P",
                        "States": {"P": {"Type": "Pass", "End": true}}
                      },
                      "Catch": [{
                        "ErrorEquals": ["States.QueryEvaluationError"],
                        "Next": "Caught"
                      }],
                      "Next": "Unexpected"
                    },
                    "Caught": {
                      "Type": "Pass",
                      "Output": {"caught": true},
                      "End": true
                    },
                    "Unexpected": {"Type": "Fail", "Error": "UnexpectedSuccess"}
                  }
                }
                """.replace("__EXPRESSION__", expression);

        JsonNode output = mapper.readTree(run(definition, "{\"items\":[1,2]}"));
        assertTrue(output.path("caught").asBoolean());
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
