package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Regression tests for #1634. Deleting a CloudFormation stack that tracks ECS resources must reach
 * {@code DELETE_COMPLETE} when ECS no longer has those resources (e.g. after a persistent restore
 * dropped the records while the stack still referenced their ARNs). The idempotent delete only
 * swallows the specific "already gone" error, so a genuine failure such as
 * {@code ClusterContainsTasksException} must still settle the stack into {@code DELETE_FAILED}.
 */
@QuarkusTest
class CloudFormationEcsDeleteAfterRestoreIntegrationTest {

    private static final String ECS_TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String ECS_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void deleteStack_withMissingEcsTaskDefinition_reachesDeleteComplete() throws Exception {
        String stack = "cfn-1634-missing-td";
        String cluster = "cfn-1634-missing-td-cluster";
        String family = "cfn-1634-missing-td-family";

        String stackArn = createStack(stack, ecsTemplate(cluster, family));
        awaitStackStatus(stackArn, "CREATE_COMPLETE");

        // Simulate a persistent restore that dropped the ECS task definition: deregister then delete
        // it through the ECS API so ECS no longer has the record the stack still references. These
        // run unauthenticated, like CreateStack/DeleteStack, so they share the same account namespace.
        ecs("DeregisterTaskDefinition", "{\"taskDefinition\":\"" + family + "\"}");
        ecs("DeleteTaskDefinitions", "{\"taskDefinitions\":[\"" + family + "\"]}");

        deleteStack(stack);

        String statusXml = awaitStackSettled(stackArn);
        assertThat(statusXml, containsString("<StackStatus>DELETE_COMPLETE</StackStatus>"));
        assertThat(statusXml, not(containsString("<StackStatus>DELETE_FAILED</StackStatus>")));
    }

    @Test
    void deleteStack_withClusterStillRunningTasks_failsAndDoesNotSwallow() throws Exception {
        String stack = "cfn-1634-running-tasks";
        String cluster = "cfn-1634-running-tasks-cluster";
        String family = "cfn-1634-running-tasks-family";

        String stackArn = createStack(stack, ecsTemplate(cluster, family));
        awaitStackStatus(stackArn, "CREATE_COMPLETE");

        // The cluster still has a running task at delete time (a genuine ClusterContainsTasksException),
        // which must NOT be treated as already-gone. ECS runs in mock mode under test, so the task is
        // in-memory RUNNING with no container behind it.
        ecs("RunTask", "{\"cluster\":\"" + cluster + "\",\"taskDefinition\":\"" + family + "\",\"count\":1}");

        deleteStack(stack);

        String statusXml = awaitStackSettled(stackArn);
        assertThat(statusXml, containsString("<StackStatus>DELETE_FAILED</StackStatus>"));
        assertThat(statusXml, not(containsString("<StackStatus>DELETE_COMPLETE</StackStatus>")));
    }

    private static String ecsTemplate(String clusterName, String family) {
        return """
            {
              "Resources": {
                "EcsCluster": {
                  "Type": "AWS::ECS::Cluster",
                  "Properties": { "ClusterName": "%s" }
                },
                "TaskDef": {
                  "Type": "AWS::ECS::TaskDefinition",
                  "Properties": {
                    "Family": "%s",
                    "NetworkMode": "awsvpc",
                    "ContainerDefinitions": [
                      { "Name": "web", "Image": "nginx:latest", "Essential": true }
                    ]
                  }
                }
              }
            }
            """.formatted(clusterName, family);
    }

    private String createStack(String stackName, String template) {
        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/")
        .then().statusCode(200).body(containsString("<StackId>"))
            .extract().asString();
        return xml.substring(xml.indexOf("<StackId>") + "<StackId>".length(), xml.indexOf("</StackId>"));
    }

    private void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/")
        .then().statusCode(200);
    }

    private void ecs(String action, String body) {
        given()
            .contentType(ECS_CONTENT_TYPE)
            .header("X-Amz-Target", ECS_TARGET + action)
            .body(body)
        .when().post("/")
        .then().statusCode(200);
    }

    private void awaitStackStatus(String stackArn, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (describeStacks(stackArn).contains("<StackStatus>" + status + "</StackStatus>")) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Stack did not reach " + status + ": " + describeStacks(stackArn));
    }

    private String awaitStackSettled(String stackArn) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String xml = describeStacks(stackArn);
            if (xml.contains("<StackStatus>DELETE_COMPLETE</StackStatus>")
                    || xml.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                return xml;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Stack did not settle into DELETE_COMPLETE/DELETE_FAILED: "
                + describeStacks(stackArn));
    }

    private String describeStacks(String stackArn) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackArn)
        .when().post("/")
        .then().statusCode(200).extract().asString();
    }
}
