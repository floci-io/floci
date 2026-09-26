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
     * ItemBatcher's MaxItemsPerBatchPath is a Reference Path. InputPath narrows the state input to
     * the items, so the batch size exists only on the Context Object.
     */
    @Test
    void itemBatcherMaxItemsPerBatchPathReadsTheContextObject() throws Exception {
        String definition = """
                {"StartAt":"M","States":{"M":{"Type":"Map",
                  "InputPath":"$.payload","ItemsPath":"$.items",
                  "ItemBatcher":{"MaxItemsPerBatchPath":"$$.Execution.Input.batch"},
                  "ItemProcessor":{"ProcessorConfig":{"Mode":"DISTRIBUTED","ExecutionType":"STANDARD"},
                    "StartAt":"P","States":{"P":{"Type":"Pass","End":true}}},
                  "End":true}}}
                """;

        JsonNode batches = mapper.readTree(run(definition,
                "{\"batch\":2,\"payload\":{\"items\":[{\"n\":1},{\"n\":2},{\"n\":3},{\"n\":4},{\"n\":5}]}}"));

        assertEquals(3, batches.size(), "batches: " + batches);
        assertEquals(2, batches.get(0).path("Items").size());
        assertEquals(2, batches.get(1).path("Items").size());
        assertEquals(1, batches.get(2).path("Items").size());
    }

    /**
     * ToleratedFailureCountPath is a Reference Path. One item fails, and the Map succeeds only if
     * the tolerance of one is read from the Context Object; the narrowed input does not carry it.
     */
    @Test
    void toleratedFailureCountPathReadsTheContextObject() throws Exception {
        String definition = """
                {"StartAt":"M","States":{"M":{"Type":"Map",
                  "InputPath":"$.payload","ItemsPath":"$.items",
                  "ToleratedFailureCountPath":"$$.Execution.Input.tolerate",
                  "ItemProcessor":{"ProcessorConfig":{"Mode":"DISTRIBUTED","ExecutionType":"STANDARD"},
                    "StartAt":"Check","States":{
                      "Check":{"Type":"Choice",
                        "Choices":[{"Variable":"$.n","NumericLessThan":0,"Next":"Boom"}],
                        "Default":"Keep"},
                      "Boom":{"Type":"Fail","Error":"ItemFailed"},
                      "Keep":{"Type":"Pass","End":true}}},
                  "End":true}}}
                """;

        JsonNode results = mapper.readTree(run(definition,
                "{\"tolerate\":1,\"payload\":{\"items\":[{\"n\":1},{\"n\":-1},{\"n\":3}]}}"));

        assertEquals(2, results.size(), "results: " + results);
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
