package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class EventBridgeBatchIntegrationTest {

    private static final String EVENT_BRIDGE_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String BATCH_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/batch/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void putEventsBatchTargetSubmitsVisibleBatchJob() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String computeArn = createComputeEnvironment("eb-batch-ce-" + suffix);
        String queueArn = createQueue("eb-batch-queue-" + suffix, computeArn);
        String definitionArn = registerJobDefinition("eb-batch-job-" + suffix);
        String ruleName = "eb-batch-rule-" + suffix;
        String submittedJobName = "eb-batch-submitted-" + suffix;

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.PutRule")
            .body("""
                    {"Name":"%s","EventPattern":"{\\"source\\":[\\"local.batch\\"]}"}
                    """.formatted(ruleName))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.PutTargets")
            .body("""
                    {
                      "Rule": "%s",
                      "Targets": [{
                        "Id": "batch",
                        "Arn": "%s",
                        "Input": "{\\"Parameters\\":{\\"inputKey\\":\\"from-event.json\\"}}",
                        "BatchParameters": {
                          "JobDefinition": "%s",
                          "JobName": "%s",
                          "ArrayProperties": {"Size": 2},
                          "RetryStrategy": {"Attempts": 2, "EvaluateOnExit": [{"Action": "EXIT"}]}
                        }
                      }]
                    }
                    """.formatted(ruleName, queueArn, definitionArn, submittedJobName))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.ListTargetsByRule")
            .body("""
                    {"Rule": "%s"}
                    """.formatted(ruleName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Targets[0].BatchParameters.ArrayProperties.Size", equalTo(2))
            .body("Targets[0].BatchParameters.RetryStrategy.Attempts", equalTo(2))
            .body("Targets[0].BatchParameters.RetryStrategy.EvaluateOnExit", nullValue());

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.PutEvents")
            .body("""
                    {
                      "Entries": [{
                        "Source": "local.batch",
                        "DetailType": "BatchRequested",
                        "Detail": "{}"
                      }]
                    }
                    """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String jobId = givenBatchJson("""
                {"jobQueue":"%s","jobStatus":"SUCCEEDED"}
                """.formatted(queueArn))
        .when()
            .post("/v1/listjobs")
        .then()
            .statusCode(200)
            .body("jobSummaryList.find { it.jobName == '%s' }.jobId".formatted(submittedJobName), notNullValue())
            .extract().path("jobSummaryList.find { it.jobName == '%s' }.jobId".formatted(submittedJobName));

        givenBatchJson("{\"jobs\":[\"%s\"]}".formatted(jobId))
        .when()
            .post("/v1/describejobs")
        .then()
            .statusCode(200)
            .body("jobs[0].jobName", equalTo(submittedJobName))
            .body("jobs[0].retryStrategy.attempts", equalTo(2))
            .body("jobs[0].retryStrategy.evaluateOnExit", nullValue())
            .body("jobs[0].container.command[0]", equalTo("from-event.json"));
    }

    private String createComputeEnvironment(String name) {
        return givenBatchJson("""
                {"computeEnvironmentName":"%s","type":"MANAGED"}
                """.formatted(name))
        .when()
            .post("/v1/createcomputeenvironment")
        .then()
            .statusCode(200)
            .extract().path("computeEnvironmentArn");
    }

    private String createQueue(String name, String computeEnvironmentArn) {
        return givenBatchJson("""
                {
                  "jobQueueName":"%s",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(name, computeEnvironmentArn))
        .when()
            .post("/v1/createjobqueue")
        .then()
            .statusCode(200)
            .extract().path("jobQueueArn");
    }

    private String registerJobDefinition(String name) {
        return givenBatchJson("""
                {
                  "jobDefinitionName":"%s",
                  "type":"container",
                  "containerProperties": {
                    "image":"public.ecr.aws/example/job:latest",
                    "command":["Ref::inputKey"]
                  }
                }
                """.formatted(name))
        .when()
            .post("/v1/registerjobdefinition")
        .then()
            .statusCode(200)
            .extract().path("jobDefinitionArn");
    }

    private static io.restassured.specification.RequestSpecification givenBatchJson(String body) {
        return given()
                .header("Authorization", BATCH_AUTH)
                .contentType("application/json")
                .body(body);
    }
}
