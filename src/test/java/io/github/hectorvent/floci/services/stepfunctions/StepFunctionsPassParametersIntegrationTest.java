package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies a Pass state applies its {@code Parameters} (with intrinsics) — previously they were
 * accepted but ignored, so the input flowed through unchanged.
 */
@QuarkusTest
class StepFunctionsPassParametersIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String DEFINITION = """
            {"StartAt":"Validate","States":{
              "Validate":{"Type":"Pass",
                "Parameters":{"result.$":"States.ArrayContains($.list, $.target)"},
                "ResultPath":"$.validation","End":true}}}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void passStateAppliesParametersWithArrayContains() throws Exception {
        JsonNode hit = mapper.readTree(run("{\"list\":[\"a\",\"b\",\"c\"],\"target\":\"b\"}"));
        assertTrue(hit.path("validation").path("result").asBoolean());

        JsonNode miss = mapper.readTree(run("{\"list\":[\"a\",\"b\",\"c\"],\"target\":\"z\"}"));
        assertFalse(miss.path("validation").path("result").asBoolean());
    }

    private String run(String input) throws InterruptedException {
        String smArn = create("pass-params-" + System.currentTimeMillis());
        String execArn = start(smArn, input);
        for (int i = 0; i < 50; i++) {
            Response resp = describe(execArn);
            String status = resp.jsonPath().getString("status");
            if ("SUCCEEDED".equals(status)) {
                return resp.jsonPath().getString("output");
            }
            if ("FAILED".equals(status) || "ABORTED".equals(status)) {
                fail("Execution " + status + ": " + resp.body().asString());
            }
            Thread.sleep(100);
        }
        fail("Execution did not complete");
        return null;
    }

    private String create(String name) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"name\":\"" + name + "\",\"definition\":" + quote(DEFINITION) + ",\"roleArn\":\"" + ROLE_ARN + "\"}")
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("stateMachineArn");
    }

    private String start(String smArn, String input) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"stateMachineArn\":\"" + smArn + "\",\"input\":" + quote(input) + "}")
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("executionArn");
    }

    private Response describe(String execArn) {
        return given()
                .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"executionArn\":\"" + execArn + "\"}")
                .when().post("/");
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
