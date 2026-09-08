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
 * End to end coverage for issue #2521. A {@code ".$"} Parameters field whose JSONPath does not
 * resolve against the input used to resolve to null and the execution succeeded; real AWS and
 * Step Functions Local 2.0.0 fail the execution with {@code States.Runtime}.
 */
@QuarkusTest
class StepFunctionsUnresolvableJsonPathIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** The exact reproduction from issue #2521. */
    @Test
    void unresolvableParametersPathFailsTheExecution() throws Exception {
        var definition = """
                {"StartAt":"Pick","States":{
                  "Pick":{"Type":"Pass","Parameters":{"missing.$":"$.nope.deep"},"End":true}}}
                """;

        var describe = run("unresolvable-jsonpath", definition, "{\"other\":1}");

        assertEquals("FAILED", describe.jsonPath().getString("status"), describe.body().asString());
        assertEquals("States.Runtime", describe.jsonPath().getString("error"));
        assertEquals("An error occurred while executing the state 'Pick' (entered at the event id #2). "
                + "The JSONPath '$.nope.deep' specified for the field 'missing.$' could not "
                + "be found in the input '{\"other\":1}'",
                describe.jsonPath().getString("cause"));
    }

    /**
     * States.Runtime is never caught, matching the intrinsic-argument precedent. A Map carries
     * the Catch because AWS refuses it on a Pass.
     */
    @Test
    void catchAllDoesNotSwallowTheFailure() throws Exception {
        var definition = """
                {"StartAt":"M","States":{
                  "M":{"Type":"Map","ItemsPath":"$.items",
                    "Parameters":{"missing.$":"$.nope"},
                    "Iterator":{"StartAt":"P","States":{"P":{"Type":"Pass","End":true}}},
                    "Catch":[{"ErrorEquals":["States.ALL"],"Next":"Caught"}],"End":true},
                  "Caught":{"Type":"Pass","End":true}}}
                """;

        var describe = run("unresolvable-jsonpath-catch-all", definition, "{\"items\":[1,2]}");

        assertEquals("FAILED", describe.jsonPath().getString("status"), describe.body().asString());
        assertEquals("States.Runtime", describe.jsonPath().getString("error"));
    }

    /** An out-of-range array index still resolves to null and the execution succeeds. */
    @Test
    void outOfRangeArrayIndexStillSucceeds() throws Exception {
        var definition = """
                {"StartAt":"Pick","States":{
                  "Pick":{"Type":"Pass","Parameters":{"idx.$":"$.items[5]"},"End":true}}}
                """;

        var describe = run("unresolvable-jsonpath-array-index", definition, "{\"items\":[1,2]}");

        assertEquals("SUCCEEDED", describe.jsonPath().getString("status"), describe.body().asString());
        assertEquals("{\"idx\":null}", describe.jsonPath().getString("output"));
    }

    /** ResultSelector runs through the same resolver and fails the same way. */
    @Test
    void unresolvableResultSelectorPathFailsTheExecution() throws Exception {
        var definition = """
                {"StartAt":"M","States":{
                  "M":{"Type":"Map","ItemsPath":"$.items",
                    "ItemProcessor":{"StartAt":"P","States":{"P":{"Type":"Pass","End":true}}},
                    "ResultSelector":{"missing.$":"$.nope"},"End":true}}}
                """;

        var describe = run("unresolvable-jsonpath-result-selector", definition, "{\"items\":[1,2]}");

        assertEquals("FAILED", describe.jsonPath().getString("status"), describe.body().asString());
        assertEquals("States.Runtime", describe.jsonPath().getString("error"));
        assertEquals("An error occurred while executing the state 'M' (entered at the event id #2). "
                + "The JSONPath '$.nope' specified for the field 'missing.$' could not be "
                + "found in the input '[1,2]'",
                describe.jsonPath().getString("cause"));
    }

    private Response run(String label, String definition, String input) throws InterruptedException {
        var smArn = create(label + "-" + System.currentTimeMillis(), definition);
        var execArn = start(smArn, input);
        for (var i = 0; i < 50; i++) {
            var resp = describe(execArn);
            var status = resp.jsonPath().getString("status");
            if (!"RUNNING".equals(status)) {
                return resp;
            }
            Thread.sleep(100);
        }
        fail("Execution did not complete");
        return null;
    }

    private String create(String name, String definition) {
        var resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"name\":\"" + name + "\",\"definition\":" + quote(definition)
                        + ",\"roleArn\":\"" + ROLE_ARN + "\"}")
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("stateMachineArn");
    }

    private String start(String smArn, String input) {
        var resp = given()
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
