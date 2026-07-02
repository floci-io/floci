package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ResultSelector is not Task-only in AWS: it also transforms the raw result of Map and Parallel
 * states before ResultPath merges it back into the state input. These tests also exercise the
 * AWS bracket wildcard form ({@code $[*].field}) inside the selector.
 */
@QuarkusTest
class StepFunctionsResultSelectorMapParallelIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";

    private static final String MAP_DEFINITION = """
            {"StartAt":"M","States":{
              "M":{"Type":"Map","ItemsPath":"$.people",
                "ItemProcessor":{"StartAt":"P","States":{"P":{"Type":"Pass","End":true}}},
                "ResultSelector":{"names.$":"$[*].name"},
                "End":true}}}
            """;

    private static final String PARALLEL_DEFINITION = """
            {"StartAt":"P","States":{
              "P":{"Type":"Parallel",
                "Branches":[
                  {"StartAt":"A","States":{"A":{"Type":"Pass","Result":{"v":1},"End":true}}},
                  {"StartAt":"B","States":{"B":{"Type":"Pass","Result":{"v":2},"End":true}}}],
                "ResultSelector":{"values.$":"$[*].v"},
                "End":true}}}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void mapResultSelectorCollectsFieldFromEachIteration() throws Exception {
        assertEquals("{\"names\":[\"a\",\"b\"]}",
                run(MAP_DEFINITION, "{\"people\":[{\"name\":\"a\"},{\"name\":\"b\"}]}"));
    }

    @Test
    void parallelResultSelectorCollectsFieldFromEachBranch() throws Exception {
        assertEquals("{\"values\":[1,2]}", run(PARALLEL_DEFINITION, "{}"));
    }

    private String run(String definition, String input) throws InterruptedException {
        String name = "resultselector-" + System.nanoTime();
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

        for (int i = 0; i < 50; i++) {
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
