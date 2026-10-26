package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.github.hectorvent.floci.testing.EmrServerlessRequests.emrServerless;
import static io.github.hectorvent.floci.testing.StepFunctionsExecutions.historyInRegion;
import static io.github.hectorvent.floci.testing.StepFunctionsExecutions.runInRegion;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code emr-serverless:} optimized integrations against Floci's EMR Serverless, from a state
 * machine in a Region other than the default, so every call has to reach the applications of the
 * state machine's own.
 */
@QuarkusTest
class StepFunctionsEmrServerlessIntegrationTest {

    private static final String REGION = "eu-west-3";
    private static final String JOB_ROLE_ARN = "arn:aws:iam::000000000000:role/emr-serverless-job";
    private static final String RESOURCE = "arn:aws:states:::emr-serverless:";
    private static final String APPLICATION = """
            {"Name": "sfn-emr-serverless", "ReleaseLabel": "emr-7.5.0", "Type": "SPARK",
             "Tags": {"Team": "data"},
             "InitialCapacity": {"DRIVER": {"WorkerCount": 1,
                                            "WorkerConfiguration": {"Cpu": "2vCPU", "Memory": "4GB"}}},
             "MaximumCapacity": {"Cpu": "8vCPU", "Memory": "32GB"},
             "RuntimeConfiguration": [{"Classification": "spark-defaults",
                                       "Properties": {"spark.driver.cores": "2"}}],
             "MonitoringConfiguration": {"S3MonitoringConfiguration": {"LogUri": "s3://bucket/app-logs/"}}}
            """;
    private static final String JOB_DRIVER = """
            {"SparkSubmit": {"EntryPoint": "s3://bucket/jobs/app.py", "EntryPointArguments": ["1"],
                             "SparkSubmitParameters": "--conf spark.executor.cores=4"}}
            """;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anApplicationIsCreatedStartedRunsAJobAndIsStoppedAndDeleted() throws Exception {
        // Stop names its application as the API does, applicationId, which a Task may write as well.
        String definition = """
                {"StartAt": "Create", "States": {
                  "Create": {"Type": "Task", "Resource": "%1$screateApplication.sync", "Parameters": %2$s,
                             "ResultPath": "$.app", "Next": "Start"},
                  "Start": {"Type": "Task", "Resource": "%1$sstartApplication.sync",
                            "Parameters": {"ApplicationId.$": "$.app.ApplicationId"},
                            "ResultPath": "$.started", "Next": "Run"},
                  "Run": {"Type": "Task", "Resource": "%1$sstartJobRun.sync",
                          "Parameters": {"ApplicationId.$": "$.app.ApplicationId", "Name": "nightly",
                                         "ExecutionRoleArn": "%3$s", "JobDriver": %4$s,
                                         "ConfigurationOverrides": {
                                           "ApplicationConfiguration": [{"Classification": "spark-defaults",
                                             "Properties": {"spark.executor.memory": "4g"}}],
                                           "MonitoringConfiguration": {
                                             "S3MonitoringConfiguration": {"LogUri": "s3://bucket/logs/"}}},
                                         "Tags": {"Run": "nightly"}},
                          "ResultPath": "$.run", "Next": "Stop"},
                  "Stop": {"Type": "Task", "Resource": "%1$sstopApplication.sync",
                           "Parameters": {"applicationId.$": "$.app.ApplicationId"},
                           "ResultPath": "$.stopped", "Next": "Delete"},
                  "Delete": {"Type": "Task", "Resource": "%1$sdeleteApplication.sync",
                             "Parameters": {"ApplicationId.$": "$.app.ApplicationId"},
                             "ResultPath": "$.deleted", "End": true}}}
                """.formatted(RESOURCE, APPLICATION, JOB_ROLE_ARN, JOB_DRIVER);

        Response execution = run(definition);

        assertEquals("SUCCEEDED", execution.jsonPath().getString("status"), execution.asString());
        JsonNode output = MAPPER.readTree(execution.jsonPath().getString("output"));
        JsonNode app = output.path("app");
        String applicationId = app.path("ApplicationId").asText();
        assertEquals(List.of("ApplicationId", "Name", "Arn"), fieldNames(app));
        assertEquals("sfn-emr-serverless", app.path("Name").asText());
        assertEquals(MAPPER.createObjectNode().put("ApplicationId", applicationId), output.path("started"));
        assertEquals(MAPPER.createObjectNode().put("ApplicationId", applicationId), output.path("stopped"));
        assertEquals(MAPPER.createObjectNode().put("ApplicationId", applicationId), output.path("deleted"));
        JsonNode run = output.path("run");
        String jobRunId = run.path("JobRunId").asText();
        assertEquals(List.of("ApplicationId", "JobRunId", "Arn"), fieldNames(run));
        assertEquals(applicationId, run.path("ApplicationId").asText());

        // The application and the run as the API reports them: members in camelCase, and every map
        // with the keys the state machine wrote.
        emrServerless(REGION).when().get("/applications/" + applicationId)
                .then().statusCode(200)
                .body("application.state", equalTo("TERMINATED"))
                .body("application.arn", equalTo(app.path("Arn").asText()))
                .body("application.tags.Team", equalTo("data"))
                .body("application.initialCapacity.DRIVER.workerCount", equalTo(1))
                .body("application.initialCapacity.DRIVER.workerConfiguration.cpu", equalTo("2vCPU"))
                .body("application.maximumCapacity.memory", equalTo("32GB"))
                .body("application.runtimeConfiguration[0].classification", equalTo("spark-defaults"))
                .body("application.runtimeConfiguration[0].properties.'spark.driver.cores'", equalTo("2"))
                .body("application.monitoringConfiguration.s3MonitoringConfiguration.logUri",
                        equalTo("s3://bucket/app-logs/"));
        emrServerless(REGION).when().get("/applications/" + applicationId + "/jobruns/" + jobRunId)
                .then().statusCode(200)
                .body("jobRun.state", equalTo("SUCCESS"))
                .body("jobRun.name", equalTo("nightly"))
                .body("jobRun.arn", equalTo(run.path("Arn").asText()))
                .body("jobRun.executionRole", equalTo(JOB_ROLE_ARN))
                .body("jobRun.jobDriver.sparkSubmit.entryPoint", equalTo("s3://bucket/jobs/app.py"))
                .body("jobRun.jobDriver.sparkSubmit.entryPointArguments[0]", equalTo("1"))
                .body("jobRun.configurationOverrides.applicationConfiguration[0].classification",
                        equalTo("spark-defaults"))
                .body("jobRun.configurationOverrides.applicationConfiguration[0].properties.'spark.executor.memory'",
                        equalTo("4g"))
                .body("jobRun.configurationOverrides.monitoringConfiguration.s3MonitoringConfiguration.logUri",
                        equalTo("s3://bucket/logs/"))
                .body("jobRun.tags.Run", equalTo("nightly"));
        emrServerless("us-east-1").when().get("/applications/" + applicationId)
                .then().statusCode(404);

        List<JsonNode> submitted = history(execution).stream()
                .filter(event -> "TaskSubmitted".equals(event.path("type").asText())).toList();
        assertEquals(5, submitted.size());
        JsonNode runSubmitted = submitted.get(2).path("taskSubmittedEventDetails");
        assertEquals("emr-serverless", runSubmitted.path("resourceType").asText());
        assertEquals("startJobRun.sync", runSubmitted.path("resource").asText());
        assertEquals(run, MAPPER.readTree(runSubmitted.path("output").asText()));
    }

    @Test
    void aStreamingRunIsCancelledAndWaitedOn() throws Exception {
        String definition = """
                {"StartAt": "Create", "States": {
                  "Create": {"Type": "Task", "Resource": "%1$screateApplication", "Parameters": %2$s,
                             "ResultPath": "$.app", "Next": "Run"},
                  "Run": {"Type": "Task", "Resource": "%1$sstartJobRun",
                          "Parameters": {"ApplicationId.$": "$.app.ApplicationId", "Mode": "STREAMING",
                                         "ExecutionRoleArn": "%3$s", "JobDriver": %4$s},
                          "ResultPath": "$.run", "Next": "Cancel"},
                  "Cancel": {"Type": "Task", "Resource": "%1$scancelJobRun.sync",
                             "Parameters": {"ApplicationId.$": "$.app.ApplicationId", "JobRunId.$": "$.run.JobRunId"},
                             "ResultPath": "$.cancelled", "End": true}}}
                """.formatted(RESOURCE, APPLICATION, JOB_ROLE_ARN, JOB_DRIVER);

        Response execution = run(definition);

        assertEquals("SUCCEEDED", execution.jsonPath().getString("status"), execution.asString());
        JsonNode output = MAPPER.readTree(execution.jsonPath().getString("output"));
        String applicationId = output.path("app").path("ApplicationId").asText();
        String jobRunId = output.path("run").path("JobRunId").asText();
        assertEquals(MAPPER.createObjectNode().put("ApplicationId", applicationId).put("JobRunId", jobRunId),
                output.path("cancelled"));
        emrServerless(REGION).when().get("/applications/" + applicationId + "/jobruns/" + jobRunId)
                .then().statusCode(200)
                .body("jobRun.state", equalTo("CANCELLED"))
                .body("jobRun.mode", equalTo("STREAMING"));
    }

    @Test
    void emrServerlessErrorsKeepTheirCodeUnderTheServicesPrefix() {
        assertFails(task("startApplication.sync", "{\"ApplicationId\": \"00nosuchapp00000\"}"),
                "EmrServerless.ResourceNotFoundException", "Application 00nosuchapp00000 not found");
        String finishedRun = """
                {"StartAt": "Create", "States": {
                  "Create": {"Type": "Task", "Resource": "%1$screateApplication", "Parameters": %2$s,
                             "ResultPath": "$.app", "Next": "Run"},
                  "Run": {"Type": "Task", "Resource": "%1$sstartJobRun.sync",
                          "Parameters": {"ApplicationId.$": "$.app.ApplicationId",
                                         "ExecutionRoleArn": "%3$s", "JobDriver": %4$s},
                          "ResultPath": "$.run", "Next": "Cancel"},
                  "Cancel": {"Type": "Task", "Resource": "%1$scancelJobRun",
                             "Parameters": {"ApplicationId.$": "$.app.ApplicationId", "JobRunId.$": "$.run.JobRunId"},
                             "End": true}}}
                """.formatted(RESOURCE, APPLICATION, JOB_ROLE_ARN, JOB_DRIVER);
        assertFails(finishedRun, "EmrServerless.ValidationException", "Job run is not in a cancellable state");
    }

    @Test
    void aTaskWithoutTheFieldsItsApiActsOnOrWithAnUnsupportedPatternFails() {
        assertFails(task("deleteApplication", "{}"), "States.TaskFailed", "The field ApplicationId is required.");
        assertFails(task("startJobRun", "{\"ExecutionRoleArn\": \"" + JOB_ROLE_ARN + "\", \"JobDriver\": "
                + JOB_DRIVER + "}"), "States.TaskFailed", "The field ApplicationId is required.");
        assertFails(task("cancelJobRun.sync", "{\"ApplicationId\": \"00anyapp00000000\"}"),
                "States.TaskFailed", "The field JobRunId is required.");
        assertFails(task("startJobRun.sync:2", "{\"ApplicationId\": \"00anyapp00000000\"}"),
                "States.TaskFailed", "Unsupported resource: " + RESOURCE + "startJobRun.sync:2");
        assertFails(task("listApplications", "{}"),
                "States.TaskFailed", "Unsupported resource: " + RESOURCE + "listApplications");
    }

    private static String task(String api, String parameters) {
        return """
                {"StartAt": "EmrServerless", "States": {"EmrServerless": {"Type": "Task", "Resource": "%s%s",
                  "Parameters": %s, "End": true}}}
                """.formatted(RESOURCE, api, parameters);
    }

    private void assertFails(String definition, String error, String cause) {
        Response execution = run(definition);
        assertEquals("FAILED", execution.jsonPath().getString("status"), execution.asString());
        assertEquals(error, execution.jsonPath().getString("error"));
        assertEquals(cause, execution.jsonPath().getString("cause"));
    }

    private static Response run(String definition) {
        return runInRegion(REGION, definition);
    }

    private static List<JsonNode> history(Response execution) throws Exception {
        return historyInRegion(REGION, execution);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
