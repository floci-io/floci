package io.floci.aws.services.glue;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Job runs in the {@code docker} runner mode, over the wire, with the container replaced by a runner that
 * reports how it ended: what a run keeps of its account and Region on the thread that runs its script.
 */
@QuarkusTest
@TestProfile(GlueScriptRunIntegrationTest.DockerRunnerProfile.class)
class GlueScriptRunIntegrationTest {

    public static class DockerRunnerProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.glue.runner-mode", "docker");
        }
    }

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/my-role";
    private static final String SCRIPT = "s3://glue-demo/scripts/job.py";

    @InjectMock
    DockerGlueScriptRunner runner;

    private final List<Boolean> cancelledSeen = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void runnerEndsWithExitZero() {
        cancelledSeen.clear();
        when(runner.isAvailable()).thenReturn(true);
        when(runner.run(any(), any())).thenAnswer(invocation -> {
            GlueScriptRunner.Progress progress = invocation.getArgument(1);
            cancelledSeen.add(progress.cancelled());
            return GlueScriptRunner.Outcome.exitedWith(0);
        });
    }

    private static ValidatableResponse call(String region, String action, String body) {
        return given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/" + region
                        + "/glue/aws4_request, SignedHeaders=host, Signature=x")
                .body(body)
        .when().post("/")
        .then();
    }

    private static String state(String region, String jobName, String runId) {
        return call(region, "GetJobRun", "{ \"JobName\": \"" + jobName + "\", \"RunId\": \"" + runId + "\" }")
                .statusCode(200).extract().path("JobRun.JobRunState");
    }

    private static String awaitEnd(String region, String jobName, String runId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String state = state(region, jobName, runId);
        while ("RUNNING".equals(state) && System.nanoTime() < deadline) {
            Thread.sleep(50);
            state = state(region, jobName, runId);
        }
        return state;
    }

    private static String startRun(String region, String command) {
        String jobName = "script-job-" + UUID.randomUUID().toString().substring(0, 8);
        call(region, "CreateJob", """
                { "Name": "%s", "Role": "%s",
                  "Command": { "Name": "%s", "ScriptLocation": "%s" } }
                """.formatted(jobName, ROLE, command, SCRIPT)).statusCode(200);
        return jobName;
    }

    @Test
    void aSparkJobsScriptRunsAndItsExitSettlesTheRunInTheDefaultRegion() throws InterruptedException {
        String jobName = startRun("us-east-1", "glueetl");
        String runId = call("us-east-1", "StartJobRun", "{ \"JobName\": \"" + jobName + "\" }")
                .statusCode(200).extract().path("JobRunId");

        assertThat(awaitEnd("us-east-1", jobName, runId), equalTo("SUCCEEDED"));
        assertThat(cancelledSeen, equalTo(List.of(false)));
    }

    @Test
    void aRunInAnotherRegionIsNotTakenForCancelledOnTheThreadThatRunsItsScript() throws InterruptedException {
        String jobName = startRun("eu-west-1", "glueetl");
        String runId = call("eu-west-1", "StartJobRun", "{ \"JobName\": \"" + jobName + "\" }")
                .statusCode(200).extract().path("JobRunId");

        assertThat(awaitEnd("eu-west-1", jobName, runId), equalTo("SUCCEEDED"));
        assertThat(cancelledSeen, equalTo(List.of(false)));
        call("eu-west-1", "GetJobRuns", "{ \"JobName\": \"" + jobName + "\" }")
                .statusCode(200)
                .body("JobRuns.Id", hasItem(runId));
    }

    @Test
    void aPythonShellJobKeepsTheSimulationAndNeverReachesTheRunner() {
        String jobName = startRun("us-east-1", "pythonshell");

        String runId = call("us-east-1", "StartJobRun", "{ \"JobName\": \"" + jobName + "\" }")
                .statusCode(200).extract().path("JobRunId");

        assertThat(state("us-east-1", jobName, runId), equalTo("SUCCEEDED"));
        assertThat(cancelledSeen, equalTo(List.of()));
    }

    @Test
    void fallsBackToSimulationWhenDockerIsUnavailable() {
        when(runner.isAvailable()).thenReturn(false);
        String jobName = startRun("us-east-1", "glueetl");

        String runId = call("us-east-1", "StartJobRun", "{ \"JobName\": \"" + jobName + "\" }")
                .statusCode(200).extract().path("JobRunId");

        assertThat(state("us-east-1", jobName, runId), equalTo("SUCCEEDED"));
        assertThat(cancelledSeen, equalTo(List.of()));
    }
}
