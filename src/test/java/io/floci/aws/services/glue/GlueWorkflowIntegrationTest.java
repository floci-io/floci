package io.floci.aws.services.glue;

import io.floci.aws.testing.GlueRequests;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class GlueWorkflowIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT_ID + ":role/my-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return GlueRequests.call(action, ACCOUNT_ID, REGION, body);
    }

    @Test
    void aWorkflowRunRunsItsTriggersToCompletion() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String workflow = "wf-" + suffix;
        String extract = "extract-" + suffix;
        String load = "load-" + suffix;
        for (String job : new String[] {extract, load}) {
            call("CreateJob", """
                    { "Name": "%s", "Role": "%s", "Command": { "Name": "glueetl" } }
                    """.formatted(job, ROLE)).statusCode(200);
        }
        call("CreateWorkflow", "{ \"Name\": \"" + workflow + "\" }")
                .statusCode(200)
                .body("Name", equalTo(workflow));
        call("CreateTrigger", """
                { "Name": "start-%s", "Type": "ON_DEMAND", "WorkflowName": "%s", "Actions": [ { "JobName": "%s" } ] }
                """.formatted(suffix, workflow, extract)).statusCode(200);
        call("CreateTrigger", """
                { "Name": "then-%s", "Type": "CONDITIONAL", "WorkflowName": "%s", "StartOnCreation": true,
                  "Actions": [ { "JobName": "%s" } ],
                  "Predicate": { "Conditions": [
                    { "LogicalOperator": "EQUALS", "JobName": "%s", "State": "SUCCEEDED" } ] } }
                """.formatted(suffix, workflow, load, extract)).statusCode(200);

        String runId = call("StartWorkflowRun", "{ \"Name\": \"" + workflow + "\" }")
                .statusCode(200)
                .extract().path("RunId");

        call("GetWorkflowRun", "{ \"Name\": \"" + workflow + "\", \"RunId\": \"" + runId + "\" }")
                .statusCode(200)
                .body("Run.Status", equalTo("COMPLETED"))
                .body("Run.Statistics.SucceededActions", equalTo(2));

        call("DeleteWorkflow", "{ \"Name\": \"" + workflow + "\" }").statusCode(200);
        call("GetWorkflow", "{ \"Name\": \"" + workflow + "\" }")
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }
}
