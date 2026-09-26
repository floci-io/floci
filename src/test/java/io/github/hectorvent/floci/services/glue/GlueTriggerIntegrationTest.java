package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class GlueTriggerIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/my-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue." + action)
                .body(body)
        .when().post("/")
        .then();
    }

    @Test
    void onDemandAndConditionalTriggersStartTheirJobs() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String extract = "extract-" + suffix;
        String load = "load-" + suffix;
        for (String job : new String[] {extract, load}) {
            call("CreateJob", """
                    { "Name": "%s", "Role": "%s", "Command": { "Name": "glueetl" },
                      "ExecutionProperty": { "MaxConcurrentRuns": 5 } }
                    """.formatted(job, ROLE)).statusCode(200);
        }
        call("CreateTrigger", """
                { "Name": "manual-%s", "Type": "ON_DEMAND", "Actions": [ { "JobName": "%s" } ] }
                """.formatted(suffix, extract)).statusCode(200);
        call("CreateTrigger", """
                { "Name": "chain-%s", "Type": "CONDITIONAL", "StartOnCreation": true,
                  "Actions": [ { "JobName": "%s" } ],
                  "Predicate": { "Conditions": [
                    { "LogicalOperator": "EQUALS", "JobName": "%s", "State": "SUCCEEDED" } ] } }
                """.formatted(suffix, load, extract)).statusCode(200);

        call("StartTrigger", "{ \"Name\": \"manual-" + suffix + "\" }")
                .statusCode(200)
                .body("Name", equalTo("manual-" + suffix));

        call("GetJobRuns", "{ \"JobName\": \"" + extract + "\" }")
                .statusCode(200)
                .body("JobRuns", hasSize(1))
                .body("JobRuns[0].TriggerName", equalTo("manual-" + suffix));
        call("GetJobRuns", "{ \"JobName\": \"" + load + "\" }")
                .statusCode(200)
                .body("JobRuns", hasSize(1))
                .body("JobRuns[0].TriggerName", equalTo("chain-" + suffix));

        call("DeleteTrigger", "{ \"Name\": \"chain-" + suffix + "\" }").statusCode(200);
        call("GetTrigger", "{ \"Name\": \"chain-" + suffix + "\" }")
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }
}
