package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * CloudFormation reports an {@code AWS::ECS::Service} complete only once the service is stable,
 * so a caller that reads the service right after the stack operation finishes sees its tasks
 * running. Floci used to report the resource complete as soon as CreateService or UpdateService
 * returned, and the tasks only started on a later reconciler tick.
 */
@QuarkusTest
class CloudFormationEcsServiceStableIntegrationTest {

    private static final String ECS_TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String ECS_CT = "application/x-amz-json-1.1";

    private static final String TEMPLATE = """
            {
              "Resources": {
                "Cluster": {
                  "Type": "AWS::ECS::Cluster",
                  "Properties": { "ClusterName": "%1$s" }
                },
                "TaskDef": {
                  "Type": "AWS::ECS::TaskDefinition",
                  "Properties": {
                    "Family": "%1$s-td",
                    "ContainerDefinitions": [
                      { "Name": "app", "Image": "%2$s", "Essential": true, "Memory": 128 }
                    ]
                  }
                },
                "Service": {
                  "Type": "AWS::ECS::Service",
                  "Properties": {
                    "ServiceName": "%1$s-svc",
                    "Cluster": { "Ref": "Cluster" },
                    "TaskDefinition": { "Ref": "TaskDef" },
                    "DesiredCount": %3$d,
                    "LaunchType": "FARGATE"
                  }
                }
              }
            }
            """;

    private static final String AWSVPC_TEMPLATE = """
            {
              "Resources": {
                "Cluster": {
                  "Type": "AWS::ECS::Cluster",
                  "Properties": { "ClusterName": "%1$s" }
                },
                "TaskDef": {
                  "Type": "AWS::ECS::TaskDefinition",
                  "Properties": {
                    "Family": "%1$s-td",
                    "NetworkMode": "awsvpc",
                    "ContainerDefinitions": [
                      { "Name": "app", "Image": "app:v1", "Essential": true, "Memory": 128 }
                    ]
                  }
                },
                "Service": {
                  "Type": "AWS::ECS::Service",
                  "Properties": {
                    "ServiceName": "%1$s-svc",
                    "Cluster": { "Ref": "Cluster" },
                    "TaskDefinition": { "Ref": "TaskDef" },
                    "DesiredCount": %2$d,
                    "LaunchType": "FARGATE",
                    "NetworkConfiguration": {
                      "AwsvpcConfiguration": { "Subnets": ["%3$s"] }
                    }
                  }
                }
              }
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createStack_ecsService_runsItsDesiredTasksWhenTheStackCompletes() {
        String name = "cfn-stable-" + Long.toString(System.nanoTime(), 36);

        stackCall("CreateStack", name, TEMPLATE.formatted(name, "app:v1", 1));

        assertThat(stackStatus(name), equalTo("CREATE_COMPLETE"));
        Response service = describeService(name);
        assertThat(service.path("services[0].desiredCount"), equalTo(1));
        assertThat(service.path("services[0].runningCount"), equalTo(1));
        assertThat(service.path("services[0].deployments"), hasSize(1));
        assertThat(service.path("services[0].deployments[0].runningCount"), equalTo(1));
        assertThat(service.path("services[0].deployments[0].rolloutState"), equalTo("COMPLETED"));
        assertThat(runningTaskDefinitions(name), hasSize(1));
    }

    @Test
    void updateStack_ecsService_runsOnlyTheNewRevisionWhenTheUpdateCompletes() {
        String name = "cfn-stable-upd-" + Long.toString(System.nanoTime(), 36);
        stackCall("CreateStack", name, TEMPLATE.formatted(name, "app:v1", 1));

        stackCall("UpdateStack", name, TEMPLATE.formatted(name, "app:v2", 2));

        assertThat(stackStatus(name), equalTo("UPDATE_COMPLETE"));
        Response service = describeService(name);
        assertThat(service.path("services[0].desiredCount"), equalTo(2));
        assertThat(service.path("services[0].runningCount"), equalTo(2));
        assertThat(service.path("services[0].taskDefinition"), endsWith(":task-definition/" + name + "-td:2"));
        List<String> running = runningTaskDefinitions(name);
        assertThat(running, hasSize(2));
        assertThat("the previous revision's task has stopped", running,
                everyItem(endsWith(":task-definition/" + name + "-td:2")));
    }

    @Test
    void updateStack_ecsServiceThatCannotStabilize_putsThePriorConfigurationBackOnRollback() {
        String name = "cfn-stable-rb-" + Long.toString(System.nanoTime(), 36);
        String subnet = Ec2Service.defaultSubnetId("us-east-1", "a");
        stackCall("CreateStack", name, AWSVPC_TEMPLATE.formatted(name, 1, subnet));
        assertThat(stackStatus(name), equalTo("CREATE_COMPLETE"));

        stackCall("UpdateStack", name, AWSVPC_TEMPLATE.formatted(name, 2, "subnet-does-not-exist"));

        assertThat(stackStatus(name), equalTo("UPDATE_ROLLBACK_COMPLETE"));
        Response service = describeService(name);
        assertThat("the rollback must not leave the failed update live",
                service.path("services[0].desiredCount"), equalTo(1));
        assertThat(service.path("services[0].networkConfiguration.awsvpcConfiguration.subnets"),
                equalTo(List.of(subnet)));
    }

    private static void stackCall(String action, String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));
    }

    private static String stackStatus(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("**.find { it.name() == 'StackStatus' }");
    }

    private static Response describeService(String name) {
        return ecs("DescribeServices",
                "{\"cluster\":\"" + name + "\",\"services\":[\"" + name + "-svc\"]}");
    }

    /** The task definition ARN of every RUNNING task of the stack's service. */
    private static List<String> runningTaskDefinitions(String name) {
        List<String> taskArns = ecs("ListTasks",
                "{\"cluster\":\"" + name + "\",\"serviceName\":\"" + name + "-svc\"}").path("taskArns");
        if (taskArns.isEmpty()) {
            return List.of();
        }
        String quoted = String.join("\",\"", taskArns);
        Response tasks = ecs("DescribeTasks", "{\"cluster\":\"" + name + "\",\"tasks\":[\"" + quoted + "\"]}");
        List<String> statuses = tasks.path("tasks.lastStatus");
        List<String> definitions = tasks.path("tasks.taskDefinitionArn");
        assertThat(statuses, everyItem(equalTo("RUNNING")));
        return definitions;
    }

    private static Response ecs(String action, String body) {
        return given()
            .header("X-Amz-Target", ECS_TARGET + action)
            .contentType(ECS_CT)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().response();
    }
}
