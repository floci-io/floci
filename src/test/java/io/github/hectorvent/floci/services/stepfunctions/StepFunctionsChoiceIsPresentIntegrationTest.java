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
 * A Choice rule {@code Not { IsPresent: $.absentField }} must take the "absent" branch when the
 * field is missing — previously an absent path resolved to a non-missing null node, so IsPresent
 * wrongly reported the field as present.
 */
@QuarkusTest
class StepFunctionsChoiceIsPresentIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";

    private static final String DEFINITION = """
            {"StartAt":"Check","States":{
              "Check":{"Type":"Choice",
                "Choices":[{"Not":{"Variable":"$.maybe","IsPresent":true},"Next":"Absent"}],
                "Default":"Present"},
              "Absent":{"Type":"Pass","Result":"absent","End":true},
              "Present":{"Type":"Pass","Result":"present","End":true}}}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void absentFieldTakesNotIsPresentBranch() throws Exception {
        assertEquals("\"absent\"", run("{}"));
    }

    @Test
    void presentFieldTakesDefaultBranch() throws Exception {
        assertEquals("\"present\"", run("{\"maybe\":\"here\"}"));
    }

    @Test
    void explicitNullCountsAsPresent() throws Exception {
        // A field that exists with a null value is present in AWS, so it must NOT take the absent branch.
        assertEquals("\"present\"", run("{\"maybe\":null}"));
    }

    private String run(String input) throws InterruptedException {
        String name = "ispresent-" + System.currentTimeMillis() + "-" + input.length();
        Response create = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"name\":\"" + name + "\",\"definition\":" + quote(DEFINITION) + ",\"roleArn\":\"" + ROLE_ARN + "\"}")
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
