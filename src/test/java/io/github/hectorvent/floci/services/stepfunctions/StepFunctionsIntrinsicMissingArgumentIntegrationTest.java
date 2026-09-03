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
 * End to end coverage for issue #2870. A {@code $.} argument of a {@code States.*} intrinsic that
 * matches nothing used to resolve to null and the execution succeeded. The causes asserted here
 * come from real AWS in us-east-1, minus the
 * {@code An error occurred while executing the state '<name>' (entered at the event id #<n>).}
 * prefix that AWS puts in front of them.
 */
@QuarkusTest
class StepFunctionsIntrinsicMissingArgumentIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void unresolvableIntrinsicArgumentFailsTheExecution() throws Exception {
        var definition = """
                {"StartAt":"Pick","States":{
                  "Pick":{"Type":"Pass","Parameters":{"v.$":"States.Format('{}', $.nope)"},"End":true}}}
                """;

        var describe = run("missing-intrinsic-arg", definition, "{\"other\":1}");

        assertEquals("FAILED", describe.jsonPath().getString("status"), describe.body().asString());
        assertEquals("States.Runtime", describe.jsonPath().getString("error"));
        assertEquals("The function 'States.Format('{}', $.nope)' had the following error: "
                + "The JsonPath argument for the field '$.nope' could not be found in the input "
                + "'{\"other\":1}'",
                describe.jsonPath().getString("cause"));
    }

    /** The cause names the whole expression, not the inner call that took the failing argument. */
    @Test
    void nestedIntrinsicNamesTheWholeExpression() throws Exception {
        var definition = """
                {"StartAt":"Pick","States":{
                  "Pick":{"Type":"Pass",
                    "Parameters":{"v.$":"States.Format('{}', States.Format('{}', $.nope))"},
                    "End":true}}}
                """;

        var describe = run("nested-intrinsic", definition, "{\"other\":1}");

        assertEquals("FAILED", describe.jsonPath().getString("status"), describe.body().asString());
        assertEquals("The function 'States.Format('{}', States.Format('{}', $.nope))' had the "
                + "following error: The JsonPath argument for the field '$.nope' could not be "
                + "found in the input '{\"other\":1}'",
                describe.jsonPath().getString("cause"));
    }

    /**
     * The same path behaves differently in the two payload template forms on AWS. Written directly
     * as {@code "v.$": "$.items[5]"} it resolves to null and the execution succeeds. Passed to an
     * intrinsic it is a miss.
     */
    @Test
    void intrinsicArgumentIndexingPastTheEndOfAnArrayFails() throws Exception {
        var definition = """
                {"StartAt":"Pick","States":{
                  "Pick":{"Type":"Pass","Parameters":{"v.$":"States.Format('{}', $.items[5])"},"End":true}}}
                """;

        var describe = run("intrinsic-index-out-of-range", definition, "{\"items\":[1,2]}");

        assertEquals("FAILED", describe.jsonPath().getString("status"), describe.body().asString());
        assertEquals("The function 'States.Format('{}', $.items[5])' had the following error: "
                + "The JsonPath argument for the field '$.items[5]' could not be found in the "
                + "input '{\"items\":[1,2]}'",
                describe.jsonPath().getString("cause"));
    }

    /** States.Runtime is never caught. A Map carries the Catch because AWS refuses it on a Pass. */
    @Test
    void catchAllDoesNotSwallowTheFailure() throws Exception {
        var definition = """
                {"StartAt":"M","States":{
                  "M":{"Type":"Map","ItemsPath":"$.items",
                    "Parameters":{"v.$":"States.Format('{}', $.nope)"},
                    "Iterator":{"StartAt":"P","States":{"P":{"Type":"Pass","End":true}}},
                    "Catch":[{"ErrorEquals":["States.ALL"],"Next":"Caught"}],"End":true},
                  "Caught":{"Type":"Pass","End":true}}}
                """;

        var describe = run("intrinsic-catch-all", definition, "{\"items\":[1,2]}");

        assertEquals("FAILED", describe.jsonPath().getString("status"), describe.body().asString());
        assertEquals("States.Runtime", describe.jsonPath().getString("error"));
        assertEquals("The function 'States.Format('{}', $.nope)' had the following error: "
                + "The JsonPath argument for the field '$.nope' could not be found in the input "
                + "'{\"items\":[1,2]}'",
                describe.jsonPath().getString("cause"));
    }

    /** Only absence fails. A present but explicitly null argument still formats as null. */
    @Test
    void explicitNullArgumentStillSucceeds() throws Exception {
        var definition = """
                {"StartAt":"Pick","States":{
                  "Pick":{"Type":"Pass","Parameters":{"v.$":"States.Format('{}', $.nul)"},"End":true}}}
                """;

        var describe = run("explicit-null-arg", definition, "{\"nul\":null}");

        assertEquals("SUCCEEDED", describe.jsonPath().getString("status"), describe.body().asString());
        assertEquals("{\"v\":\"null\"}", describe.jsonPath().getString("output"));
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
