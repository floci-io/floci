package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StepFunctionsRedshiftDataDockerIntegrationTest {

    private static final String CLUSTER_ID = "it-sfn-rsdata";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Inject
    RedshiftService redshift;

    private boolean clusterCreated;

    @BeforeAll
    void createCluster() {
        Assumptions.assumeTrue(dockerAvailable(), "Docker is required for this test");
        RestAssuredJsonUtils.configureAwsContentTypes();
        redshift.createCluster(CLUSTER_ID, "dc2.large", "admin", "Secret123");
        clusterCreated = true;
    }

    @AfterAll
    void deleteCluster() {
        if (clusterCreated) {
            redshift.deleteCluster(CLUSTER_ID);
        }
    }

    @Test
    void executeThenDescribeStatementOnAProvisionedCluster() throws Exception {
        String definition = """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Run",
                  "States": {
                    "Run": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:redshiftdata:executeStatement",
                      "Arguments": {"ClusterIdentifier": "CLUSTER", "Database": "dev",
                                    "DbUser": "admin", "Sql": "select 1 as one"},
                      "Output": "{% $states.result %}",
                      "Next": "Describe"
                    },
                    "Describe": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:redshiftdata:describeStatement",
                      "Arguments": {"Id": "{% $states.input.Id %}"},
                      "End": true
                    }
                  }
                }
                """.replace("CLUSTER", CLUSTER_ID);

        JsonNode result = OBJECT_MAPPER.readTree(succeedingOutput(definition));
        assertEquals("FINISHED", result.path("Status").asText());
        assertEquals(1, result.path("ResultRows").asInt());
    }

    @Test
    void failedStatementIsNotATaskFailure() throws Exception {
        String definition = """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Run",
                  "States": {
                    "Run": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:redshiftdata:executeStatement",
                      "Arguments": {"ClusterIdentifier": "CLUSTER", "Database": "dev",
                                    "DbUser": "admin", "Sql": "select * from no_such_table"},
                      "Output": "{% $states.result %}",
                      "Next": "Describe"
                    },
                    "Describe": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:redshiftdata:describeStatement",
                      "Arguments": {"Id": "{% $states.input.Id %}"},
                      "End": true
                    }
                  }
                }
                """.replace("CLUSTER", CLUSTER_ID);

        JsonNode result = OBJECT_MAPPER.readTree(succeedingOutput(definition));
        assertEquals("FAILED", result.path("Status").asText());
    }

    private static boolean dockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            // An unavailable Docker daemon is an expected reason to skip this integration test.
            return false;
        }
    }

    private static String succeedingOutput(String definition) {
        String stateMachineArn = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(CONTENT_TYPE)
                .body("{\"name\":\"rsdata-docker-%s\",\"roleArn\":\"%s\",\"definition\":%s}"
                        .formatted(System.nanoTime(), ROLE_ARN, quote(definition)))
                .when().post("/").then().statusCode(200).extract().path("stateMachineArn");
        String executionArn = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(CONTENT_TYPE)
                .body("{\"stateMachineArn\":\"%s\",\"input\":\"{}\"}".formatted(stateMachineArn))
                .when().post("/").then().statusCode(200).extract().path("executionArn");
        for (int i = 0; i < 300; i++) {
            Response response = given()
                    .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                    .contentType(CONTENT_TYPE)
                    .body("{\"executionArn\":\"%s\"}".formatted(executionArn))
                    .when().post("/");
            String status = response.jsonPath().getString("status");
            if (!"RUNNING".equals(status)) {
                assertEquals("SUCCEEDED", status, "cause: " + response.jsonPath().getString("cause"));
                return response.jsonPath().getString("output");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for " + executionArn);
            }
        }
        fail("Execution did not complete: " + executionArn);
        return null;
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
