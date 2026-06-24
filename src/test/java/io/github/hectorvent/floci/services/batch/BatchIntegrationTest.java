package io.github.hectorvent.floci.services.batch;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class BatchIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/batch/aws4_request";

    @Test
    void submitDescribeAndListImmediateJob() {
        String suffix = uniqueSuffix();
        String queueArn = createQueue("batch-queue-" + suffix, createComputeEnvironment("batch-ce-" + suffix));
        String definitionArn = registerJobDefinition("batch-job-" + suffix,
                "[\"Ref::inputKey\",\"Ref::outputKey\",\"--literal\"]",
                "[{\"name\":\"MODE\",\"value\":\"definition\"}]");

        String jobId = givenJson("""
                {
                  "jobName": "batch-submit-%s",
                  "jobQueue": "%s",
                  "jobDefinition": "%s",
                  "parameters": {
                    "inputKey": "payloads/input.json",
                    "outputKey": "payloads/output.json"
                  },
                  "containerOverrides": {
                    "environment": [{"name":"MODE","value":"override"}]
                  },
                  "timeout": {"attemptDurationSeconds": 60}
                }
                """.formatted(suffix, queueArn, definitionArn))
        .when()
            .post("/v1/submitjob")
        .then()
            .statusCode(200)
            .body("jobId", notNullValue())
            .body("jobArn", containsString(":batch:us-east-1:000000000000:job/"))
            .extract().path("jobId");

        givenJson("{\"jobs\":[\"%s\"]}".formatted(jobId))
        .when()
            .post("/v1/describejobs")
        .then()
            .statusCode(200)
            .body("jobs", hasSize(1))
            .body("jobs[0].status", equalTo("SUCCEEDED"))
            .body("jobs[0].jobQueue", equalTo(queueArn))
            .body("jobs[0].jobDefinition", equalTo(definitionArn))
            .body("jobs[0].container.command[0]", equalTo("payloads/input.json"))
            .body("jobs[0].container.command[1]", equalTo("payloads/output.json"))
            .body("jobs[0].container.command[2]", equalTo("--literal"))
            .body("jobs[0].container.environment.find { it.name == 'MODE' }.value", equalTo("override"))
            .body("jobs[0].attempts", hasSize(1))
            .body("jobs[0].attempts[0].container.exitCode", equalTo(0))
            .body("jobs[0].attempts[0].container.taskArn", nullValue());

        givenJson("{\"jobQueue\":\"%s\",\"jobStatus\":\"SUCCEEDED\"}".formatted(queueArn))
        .when()
            .post("/v1/listjobs")
        .then()
            .statusCode(200)
            .body("jobSummaryList", hasSize(greaterThan(0)))
            .body("jobSummaryList.find { it.jobId == '%s' }.status".formatted(jobId), equalTo("SUCCEEDED"))
            .body("jobSummaryList.find { it.jobId == '%s' }.jobQueue".formatted(jobId), nullValue())
            .body("jobSummaryList.find { it.jobId == '%s' }.container.exitCode".formatted(jobId), equalTo(0))
            .body("jobSummaryList.find { it.jobId == '%s' }.container.logStreamName".formatted(jobId), nullValue())
            .body("jobSummaryList.find { it.jobId == '%s' }.container.taskArn".formatted(jobId), nullValue());
    }

    @Test
    void jobDefinitionRevisionsAndDeregister() {
        String suffix = uniqueSuffix();
        String queueArn = createQueue("batch-rev-queue-" + suffix, createComputeEnvironment("batch-rev-ce-" + suffix));
        String jobName = "batch-rev-job-" + suffix;
        String revisionOne = registerJobDefinition(jobName, "[\"rev1\"]", "[]");
        String revisionTwo = registerJobDefinition(jobName, "[\"rev2\"]", "[]");

        givenJson("{\"jobDefinition\":\"%s\"}".formatted(jobName))
        .when()
            .post("/v1/deregisterjobdefinition")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"));

        givenJson("{\"jobDefinition\":\"%s\"}".formatted(revisionTwo))
        .when()
            .post("/v1/deregisterjobdefinition")
        .then()
            .statusCode(200);

        givenJson("{\"jobDefinitionName\":\"%s\",\"status\":\"ACTIVE\"}".formatted(jobName))
        .when()
            .post("/v1/describejobdefinitions")
        .then()
            .statusCode(200)
            .body("jobDefinitions", hasSize(1))
            .body("jobDefinitions[0].jobDefinitionArn", equalTo(revisionOne))
            .body("jobDefinitions[0].revision", equalTo(1));

        String jobId = givenJson("""
                {"jobName":"latest-active-%s","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(suffix, queueArn, jobName))
        .when()
            .post("/v1/submitjob")
        .then()
            .statusCode(200)
            .extract().path("jobId");

        givenJson("{\"jobs\":[\"%s\"]}".formatted(jobId))
        .when()
            .post("/v1/describejobs")
        .then()
            .statusCode(200)
            .body("jobs[0].jobDefinition", equalTo(revisionOne))
            .body("jobs[0].container.command[0]", equalTo("rev1"));
    }

    @Test
    void submitResolvesJobDefinitionNameRevisionAndArns() {
        String suffix = uniqueSuffix();
        String queueArn = createQueue("batch-resolve-queue-" + suffix, createComputeEnvironment("batch-resolve-ce-" + suffix));
        String jobName = "batch-resolve-job-" + suffix;
        String revisionOne = registerJobDefinition(jobName, "[\"rev1\"]", "[]");
        String revisionTwo = registerJobDefinition(jobName, "[\"rev2\"]", "[]");
        String arnWithoutRevision = revisionTwo.substring(0, revisionTwo.lastIndexOf(':'));

        String byNameRevision = submit(queueArn, jobName + ":1", "batch-resolve-name-rev-" + suffix);
        String byFullArn = submit(queueArn, revisionTwo, "batch-resolve-full-arn-" + suffix);
        String byArnWithoutRevision = submit(queueArn, arnWithoutRevision, "batch-resolve-arn-latest-" + suffix);

        givenJson("{\"jobs\":[\"%s\",\"%s\",\"%s\"]}".formatted(byNameRevision, byFullArn, byArnWithoutRevision))
        .when()
            .post("/v1/describejobs")
        .then()
            .statusCode(200)
            .body("jobs.find { it.jobId == '%s' }.jobDefinition".formatted(byNameRevision), equalTo(revisionOne))
            .body("jobs.find { it.jobId == '%s' }.container.command[0]".formatted(byNameRevision), equalTo("rev1"))
            .body("jobs.find { it.jobId == '%s' }.jobDefinition".formatted(byFullArn), equalTo(revisionTwo))
            .body("jobs.find { it.jobId == '%s' }.container.command[0]".formatted(byFullArn), equalTo("rev2"))
            .body("jobs.find { it.jobId == '%s' }.jobDefinition".formatted(byArnWithoutRevision), equalTo(revisionTwo))
            .body("jobs.find { it.jobId == '%s' }.container.command[0]".formatted(byArnWithoutRevision), equalTo("rev2"));
    }

    @Test
    void listJobsPaginatesNewestFirst() throws Exception {
        String suffix = uniqueSuffix();
        String queueArn = createQueue("batch-page-queue-" + suffix, createComputeEnvironment("batch-page-ce-" + suffix));
        String definitionArn = registerJobDefinition("batch-page-job-" + suffix, "[\"ok\"]", "[]");
        String firstName = "batch-page-first-" + suffix;
        String secondName = "batch-page-second-" + suffix;

        submit(queueArn, definitionArn, firstName);
        Thread.sleep(5);
        submit(queueArn, definitionArn, secondName);

        String nextToken = givenJson("""
                {"jobQueue":"%s","jobStatus":"SUCCEEDED","maxResults":1}
                """.formatted(queueArn))
        .when()
            .post("/v1/listjobs")
        .then()
            .statusCode(200)
            .body("jobSummaryList", hasSize(1))
            .body("jobSummaryList[0].jobName", equalTo(secondName))
            .body("nextToken", equalTo("1"))
            .extract().path("nextToken");

        givenJson("""
                {"jobQueue":"%s","jobStatus":"SUCCEEDED","maxResults":1,"nextToken":"%s"}
                """.formatted(queueArn, nextToken))
        .when()
            .post("/v1/listjobs")
        .then()
            .statusCode(200)
            .body("jobSummaryList", hasSize(1))
            .body("jobSummaryList[0].jobName", equalTo(firstName));
    }

    @Test
    void listJobsUsesAwsFilters() throws Exception {
        String suffix = uniqueSuffix();
        String queueArn = createQueue("batch-dedupe-queue-" + suffix, createComputeEnvironment("batch-dedupe-ce-" + suffix));
        String definitionArn = registerJobDefinition("batch-dedupe-job-" + suffix, "[\"ok\"]", "[]");
        String prefix = "batch-dedupe-match-" + suffix;
        String oldName = prefix + "-old";
        String newName = prefix + "-new";

        submit(queueArn, definitionArn, oldName);
        Thread.sleep(5);
        long createdAfter = System.currentTimeMillis();
        Thread.sleep(5);
        submit(queueArn, definitionArn, newName);
        submit(queueArn, definitionArn, "batch-dedupe-other-" + suffix);

        givenJson("""
                {
                  "jobQueue":"%s",
                  "jobStatus":"RUNNING",
                  "filters":[{"name":"JOB_NAME","values":["%s*"]}]
                }
                """.formatted(queueArn, prefix.toUpperCase()))
        .when()
            .post("/v1/listjobs")
        .then()
            .statusCode(200)
            .body("jobSummaryList", hasSize(2))
            .body("jobSummaryList.find { it.jobName == '%s' }.jobId".formatted(oldName), notNullValue())
            .body("jobSummaryList.find { it.jobName == '%s' }.jobId".formatted(newName), notNullValue());

        givenJson("""
                {
                  "jobQueue":"%s",
                  "jobStatus":"SUCCEEDED",
                  "filters":[{"name":"AFTER_CREATED_AT","values":["%d"]}]
                }
                """.formatted(queueArn, createdAfter))
        .when()
            .post("/v1/listjobs")
        .then()
            .statusCode(200)
            .body("jobSummaryList", hasSize(2))
            .body("jobSummaryList.find { it.jobName == '%s' }.jobId".formatted(newName), notNullValue())
            .body("jobSummaryList.find { it.jobName == 'batch-dedupe-other-%s' }.jobId".formatted(suffix), notNullValue());

        givenJson("""
                {
                  "jobQueue":"%s",
                  "filters":[
                    {"name":"JOB_NAME","values":["%s*"]},
                    {"name":"AFTER_CREATED_AT","values":["%d"]}
                  ]
                }
                """.formatted(queueArn, prefix, createdAfter))
        .when()
            .post("/v1/listjobs")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"));
    }

    @Test
    void describeControlPlaneResourcesPaginateAndValidateJobDefinitionFilters() {
        String suffix = uniqueSuffix();
        String firstCompute = createComputeEnvironment("batch-desc-ce-a-" + suffix);
        String secondCompute = createComputeEnvironment("batch-desc-ce-b-" + suffix);
        createQueue("batch-desc-queue-a-" + suffix, firstCompute);
        createQueue("batch-desc-queue-b-" + suffix, secondCompute);
        String definitionOne = registerJobDefinition("batch-desc-job-a-" + suffix, "[\"a\"]", "[]");
        String definitionTwo = registerJobDefinition("batch-desc-job-b-" + suffix, "[\"b\"]", "[]");

        givenJson("{\"computeEnvironments\":[\"%s\",\"%s\"],\"maxResults\":1}".formatted(firstCompute, secondCompute))
        .when()
            .post("/v1/describecomputeenvironments")
        .then()
            .statusCode(200)
            .body("computeEnvironments", hasSize(1))
            .body("computeEnvironments[0].createdAt", nullValue())
            .body("computeEnvironments[0].region", nullValue())
            .body("computeEnvironments[0].accountId", nullValue())
            .body("nextToken", equalTo("1"));

        givenJson("{\"jobQueues\":[\"batch-desc-queue-a-%s\"],\"maxResults\":1}".formatted(suffix))
        .when()
            .post("/v1/describejobqueues")
        .then()
            .statusCode(200)
            .body("jobQueues", hasSize(1))
            .body("jobQueues[0].createdAt", nullValue())
            .body("jobQueues[0].region", nullValue())
            .body("jobQueues[0].accountId", nullValue());

        givenJson("""
                {"jobDefinitions":["%s","%s"],"maxResults":1}
                """.formatted(definitionOne, definitionTwo))
        .when()
            .post("/v1/describejobdefinitions")
        .then()
            .statusCode(200)
            .body("jobDefinitions", hasSize(1))
            .body("jobDefinitions[0].createdAt", nullValue())
            .body("jobDefinitions[0].region", nullValue())
            .body("jobDefinitions[0].accountId", nullValue())
            .body("jobDefinitions[0].containerProperties.jobRoleArn", nullValue())
            .body("jobDefinitions[0].retryStrategy.evaluateOnExit", nullValue())
            .body("nextToken", equalTo("1"));

        givenJson("""
                {"jobDefinitions":["%s"],"status":"ACTIVE"}
                """.formatted(definitionOne))
        .when()
            .post("/v1/describejobdefinitions")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"));
    }

    @Test
    void validationErrorsUseClientException() {
        String suffix = uniqueSuffix();
        String queueArn = createQueue("batch-error-queue-" + suffix, createComputeEnvironment("batch-error-ce-" + suffix));
        String definitionArn = registerJobDefinition("batch-error-job-" + suffix, "[\"ok\"]", "[]");

        givenJson("{}")
        .when()
            .post("/v1/listjobs")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"));

        givenJson("""
                {"jobName":"missing-queue-%s","jobQueue":"missing","jobDefinition":"%s"}
                """.formatted(suffix, definitionArn))
        .when()
            .post("/v1/submitjob")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"));

        givenJson("""
                {
                  "jobName":"bad-env-%s",
                  "jobQueue":"%s",
                  "jobDefinition":"%s",
                  "containerOverrides": {
                    "environment": [{"name":"AWS_BATCH_BAD","value":"nope"}]
                  }
                }
                """.formatted(suffix, queueArn, definitionArn))
        .when()
            .post("/v1/submitjob")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"));

        givenJson("""
                {
                  "jobDefinitionName": "bad-retry-%s",
                  "type": "container",
                  "containerProperties": {"image":"public.ecr.aws/example/job:latest"},
                  "retryStrategy": {"attempts": 11}
                }
                """.formatted(suffix))
        .when()
            .post("/v1/registerjobdefinition")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"));

        givenJson("""
                {
                  "jobDefinitionName": "bad-timeout-%s",
                  "type": "container",
                  "containerProperties": {"image":"public.ecr.aws/example/job:latest"},
                  "timeout": {"attemptDurationSeconds": 59}
                }
                """.formatted(suffix))
        .when()
            .post("/v1/registerjobdefinition")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"));

        givenJson("""
                {
                  "jobDefinitionName": "bad-type-%s",
                  "type": "multinode"
                }
                """.formatted(suffix))
        .when()
            .post("/v1/registerjobdefinition")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"));

        givenJson("""
                {
                  "computeEnvironmentName": "bad-ce-missing-type-%s"
                }
                """.formatted(suffix))
        .when()
            .post("/v1/createcomputeenvironment")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"));

        givenJson("""
                {
                  "jobDefinitionName": "bad-missing-container-%s",
                  "type": "container"
                }
                """.formatted(suffix))
        .when()
            .post("/v1/registerjobdefinition")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"));

        givenJson("""
                {
                  "jobDefinitionName": "bad-register-env-%s",
                  "type": "container",
                  "containerProperties": {
                    "environment": [{"name":"AWS_BATCH_BAD","value":"nope"}]
                  }
                }
                """.formatted(suffix))
        .when()
            .post("/v1/registerjobdefinition")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"));

        givenJson("""
                {"jobName":"bad/name-%s","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(suffix, queueArn, definitionArn))
        .when()
            .post("/v1/submitjob")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"));

        givenJson("{\"jobDefinition\":\"batch-error-job-%s\"}".formatted(suffix))
        .when()
            .post("/v1/deregisterjobdefinition")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"));

        givenJson("{\"jobDefinitions\":[\"%s\"],\"nextToken\":\"not-a-number\"}".formatted(definitionArn))
        .when()
            .post("/v1/describejobdefinitions")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ClientException"));
    }

    @Test
    void describeUnknownJobsReturnsEmptyList() {
        givenJson("{\"jobs\":[\"does-not-exist\"]}")
        .when()
            .post("/v1/describejobs")
        .then()
            .statusCode(200)
            .body("jobs", hasSize(0));
    }

    private static io.restassured.specification.RequestSpecification givenJson(String body) {
        return given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body(body);
    }

    private String createComputeEnvironment(String name) {
        return givenJson("""
                {
                  "computeEnvironmentName": "%s",
                  "type": "MANAGED",
                  "computeResources": {"type":"FARGATE","maxvCpus":4}
                }
                """.formatted(name))
        .when()
            .post("/v1/createcomputeenvironment")
        .then()
            .statusCode(200)
            .extract().path("computeEnvironmentArn");
    }

    private String createQueue(String name, String computeEnvironmentArn) {
        return givenJson("""
                {
                  "jobQueueName": "%s",
                  "priority": 1,
                  "computeEnvironmentOrder": [{
                    "order": 1,
                    "computeEnvironment": "%s"
                  }]
                }
                """.formatted(name, computeEnvironmentArn))
        .when()
            .post("/v1/createjobqueue")
        .then()
            .statusCode(200)
            .extract().path("jobQueueArn");
    }

    private String registerJobDefinition(String name, String commandJson, String environmentJson) {
        return givenJson("""
                {
                  "jobDefinitionName": "%s",
                  "type": "container",
                  "platformCapabilities": ["FARGATE"],
                  "containerProperties": {
                    "image": "public.ecr.aws/example/job:latest",
                    "command": %s,
                    "environment": %s,
                    "resourceRequirements": [
                      {"type":"VCPU","value":"1"},
                      {"type":"MEMORY","value":"512"}
                    ]
                  },
                  "retryStrategy": {"attempts": 2}
                }
                """.formatted(name, commandJson, environmentJson))
        .when()
            .post("/v1/registerjobdefinition")
        .then()
            .statusCode(200)
            .extract().path("jobDefinitionArn");
    }

    private String submit(String queueArn, String definitionArn, String jobName) {
        return givenJson("""
                {"jobName":"%s","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(jobName, queueArn, definitionArn))
        .when()
            .post("/v1/submitjob")
        .then()
            .statusCode(200)
            .extract().path("jobId");
    }

    private static String uniqueSuffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
