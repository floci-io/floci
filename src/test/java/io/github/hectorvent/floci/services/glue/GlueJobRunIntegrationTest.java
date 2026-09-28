package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;

@QuarkusTest
class GlueJobRunIntegrationTest {

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
    void jobRunStartsSucceedsAndIsRemovedWithItsJob() {
        String jobName = "run-job-" + UUID.randomUUID().toString().substring(0, 8);
        call("CreateJob", """
                { "Name": "%s", "Role": "%s", "Command": { "Name": "glueetl" } }
                """.formatted(jobName, ROLE)).statusCode(200);

        String runId = call("StartJobRun", "{ \"JobName\": \"" + jobName + "\" }")
                .statusCode(200)
                .body("JobRunId", matchesPattern("jr_[0-9a-f]{64}"))
                .extract().path("JobRunId");

        call("GetJobRun", "{ \"JobName\": \"" + jobName + "\", \"RunId\": \"" + runId + "\" }")
                .statusCode(200)
                .body("JobRun.Id", equalTo(runId))
                .body("JobRun.JobRunState", equalTo("SUCCEEDED"));

        call("ListJobs", "{}")
                .statusCode(200)
                .body("JobNames", hasItem(jobName));

        call("DeleteJob", "{ \"JobName\": \"" + jobName + "\" }").statusCode(200);

        call("GetJobRuns", "{ \"JobName\": \"" + jobName + "\" }")
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }
}
