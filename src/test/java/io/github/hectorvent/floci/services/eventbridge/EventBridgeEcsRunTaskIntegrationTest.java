package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies that a classic EventBridge rule target pointing at an ECS cluster (with
 * {@code EcsParameters}, the shape Terraform's {@code aws_cloudwatch_event_target}
 * {@code ecs_target} block or {@code aws events put-targets} produces) round-trips
 * through PutTargets/ListTargetsByRule and actually calls ECS RunTask when the rule fires.
 */
@QuarkusTest
class EventBridgeEcsRunTaskIntegrationTest {

    private static final String EVENT_BRIDGE_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ECS_TARGET_PREFIX = "AmazonEC2ContainerServiceV20141113.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void putEventsEcsClusterTargetRunsTaskWithEcsParameters() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String clusterName = "eb-ecs-cluster-" + suffix;
        String family = "eb-ecs-family-" + suffix;
        String ruleName = "eb-ecs-rule-" + suffix;
        String source = "local.ecstest";
        String detailType = "EcsTestTrigger";

        String clusterArn = createCluster(clusterName);
        String taskDefinitionArn = registerTaskDefinition(family);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.PutRule")
            .body("""
                    {"Name":"%s","EventPattern":"{\\"source\\":[\\"%s\\"],\\"detail-type\\":[\\"%s\\"]}"}
                    """.formatted(ruleName, source, detailType))
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
                        "Id": "ecs-target",
                        "Arn": "%s",
                        "EcsParameters": {
                          "TaskDefinitionArn": "%s",
                          "TaskCount": 1,
                          "LaunchType": "FARGATE",
                          "Group": "eb-ecs-group-%s",
                          "NetworkConfiguration": {
                            "awsvpcConfiguration": {
                              "Subnets": ["subnet-1", "subnet-2"],
                              "SecurityGroups": ["sg-1"],
                              "AssignPublicIp": "ENABLED"
                            }
                          }
                        }
                      }]
                    }
                    """.formatted(ruleName, clusterArn, taskDefinitionArn, suffix))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FailedEntryCount", equalTo(0));

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
            .body("Targets[0].Arn", equalTo(clusterArn))
            .body("Targets[0].EcsParameters.TaskDefinitionArn", equalTo(taskDefinitionArn))
            .body("Targets[0].EcsParameters.TaskCount", equalTo(1))
            .body("Targets[0].EcsParameters.LaunchType", equalTo("FARGATE"))
            .body("Targets[0].EcsParameters.Group", equalTo("eb-ecs-group-" + suffix))
            .body("Targets[0].EcsParameters.NetworkConfiguration.awsvpcConfiguration.Subnets",
                    hasItem("subnet-1"))
            .body("Targets[0].EcsParameters.NetworkConfiguration.awsvpcConfiguration.SecurityGroups",
                    hasItem("sg-1"))
            .body("Targets[0].EcsParameters.NetworkConfiguration.awsvpcConfiguration.AssignPublicIp",
                    equalTo("ENABLED"));

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.PutEvents")
            .body("""
                    {
                      "Entries": [{
                        "Source": "%s",
                        "DetailType": "%s",
                        "Detail": "{}"
                      }]
                    }
                    """.formatted(source, detailType))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        ecs("ListTasks")
            .body("""
                    {"cluster": "%s"}
                    """.formatted(clusterName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("taskArns", hasSize(1));

        String taskArn = ecs("ListTasks")
            .body("""
                    {"cluster": "%s"}
                    """.formatted(clusterName))
        .when()
            .post("/")
        .then()
            .extract().path("taskArns[0]");

        ecs("DescribeTasks")
            .body("""
                    {
                        "cluster": "%s",
                        "tasks": ["%s"]
                    }
                    """.formatted(clusterName, taskArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("tasks[0].taskDefinitionArn", equalTo(taskDefinitionArn))
            .body("tasks[0].launchType", equalTo("FARGATE"))
            .body("tasks[0].startedBy", equalTo("eb-ecs-group-" + suffix))
            .body("tasks[0].lastStatus", notNullValue());
    }

    private static String createCluster(String clusterName) {
        return ecs("CreateCluster")
            .body("""
                    {"clusterName": "%s"}
                    """.formatted(clusterName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("cluster.clusterArn", containsString(clusterName))
            .extract().path("cluster.clusterArn");
    }

    private static String registerTaskDefinition(String family) {
        return ecs("RegisterTaskDefinition")
            .body("""
                    {
                        "family": "%s",
                        "containerDefinitions": [
                            {
                                "name": "app",
                                "image": "nginx:latest",
                                "cpu": 256,
                                "memory": 512,
                                "essential": true
                            }
                        ],
                        "requiresCompatibilities": ["FARGATE"],
                        "cpu": "256",
                        "memory": "512",
                        "networkMode": "awsvpc"
                    }
                    """.formatted(family))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("taskDefinition.taskDefinitionArn");
    }

    private static io.restassured.specification.RequestSpecification ecs(String action) {
        return given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", ECS_TARGET_PREFIX + action);
    }
}
