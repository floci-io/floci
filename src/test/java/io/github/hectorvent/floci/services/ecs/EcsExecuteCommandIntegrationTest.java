package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * {@code ExecuteCommand}'s control-plane half: what a task has to be for a session to be opened at
 * all, and what the caller gets back when it is.
 *
 * <p>Tests run with ECS in mock mode, so the tasks here have container models but nothing running
 * behind them: the session gate is exercised up to the point where a real container would be
 * needed. {@code EcsExecChannelDockerIntegrationTest} covers the rest against Docker.
 */
@QuarkusTest
class EcsExecuteCommandIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";
    private static final String CLUSTER = "exec-cluster";
    private static final String NETWORK =
            "\"networkConfiguration\":{\"awsvpcConfiguration\":{\"subnets\":"
                    + "[\"subnet-default-us-east-1-a\"]}}";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body, int expectedStatus) {
        return given().contentType(CT).header("X-Amz-Target", TARGET + action)
                .body(body)
                .when().post("/")
                .then().statusCode(expectedStatus)
                .extract().response();
    }

    private static String runTask(String family, boolean enableExecuteCommand) {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);
        call("RegisterTaskDefinition", "{\"family\":\"" + family + "\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\",\"containerDefinitions\":"
                + "[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}", 200);
        return call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"launchType\":\"FARGATE\"," + NETWORK + ",\"enableExecuteCommand\":"
                + enableExecuteCommand + "}", 200)
                .jsonPath().getString("tasks[0].taskArn");
    }

    @Test
    void aTaskRunWithExecuteCommandEnabledReportsTheAgent() {
        String taskArn = runTask("exec-enabled", true);

        call("DescribeTasks", "{\"cluster\":\"" + CLUSTER + "\",\"tasks\":[\"" + taskArn + "\"]}", 200)
                .then()
                .body("tasks[0].enableExecuteCommand", equalTo(true))
                .body("tasks[0].containers", hasSize(1))
                .body("tasks[0].containers[0].managedAgents[0].name", equalTo("ExecuteCommandAgent"))
                .body("tasks[0].containers[0].managedAgents[0].lastStatus", equalTo("RUNNING"));
    }

    @Test
    void executeCommandIsRefusedWhenTheTaskWasNotRunWithItEnabled() {
        String taskArn = runTask("exec-disabled", false);

        call("ExecuteCommand", "{\"cluster\":\"" + CLUSTER + "\",\"task\":\"" + taskArn
                + "\",\"container\":\"app\",\"command\":\"/bin/sh\",\"interactive\":true}", 400)
                .then()
                .body("__type", containsString("InvalidParameterException"))
                .body("message", containsString("execute command was not enabled"));
    }

    @Test
    void executeCommandIsRefusedForAContainerThatIsNotInTheTask() {
        String taskArn = runTask("exec-wrong-container", true);

        call("ExecuteCommand", "{\"cluster\":\"" + CLUSTER + "\",\"task\":\"" + taskArn
                + "\",\"container\":\"sidecar\",\"command\":\"/bin/sh\",\"interactive\":true}", 400)
                .then().body("message", containsString("sidecar"));
    }

    @Test
    void executeCommandIsRefusedWhenNothingIsRunningBehindTheContainer() {
        // Mock mode reports the container but has no runtime behind it, which is exactly the
        // state AWS reports as a disconnected target rather than a missing one.
        String taskArn = runTask("exec-no-runtime", true);

        call("ExecuteCommand", "{\"cluster\":\"" + CLUSTER + "\",\"task\":\"" + taskArn
                + "\",\"container\":\"app\",\"command\":\"/bin/sh\",\"interactive\":true}", 400)
                .then()
                .body("__type", containsString("TargetNotConnectedException"));
    }

    @Test
    void executeCommandIsRefusedOnAStoppedTask() {
        String taskArn = runTask("exec-stopped", true);
        call("StopTask", "{\"cluster\":\"" + CLUSTER + "\",\"task\":\"" + taskArn + "\"}", 200);

        call("ExecuteCommand", "{\"cluster\":\"" + CLUSTER + "\",\"task\":\"" + taskArn
                + "\",\"container\":\"app\",\"command\":\"/bin/sh\",\"interactive\":true}", 400)
                .then()
                .body("__type", containsString("TargetNotConnectedException"))
                .body("message", containsString("not running"));
    }

    @Test
    void executeCommandIsRefusedWhenItIsNotInteractive() {
        String taskArn = runTask("exec-non-interactive", true);

        call("ExecuteCommand", "{\"cluster\":\"" + CLUSTER + "\",\"task\":\"" + taskArn
                + "\",\"container\":\"app\",\"command\":\"/bin/sh\",\"interactive\":false}", 400)
                .then()
                .body("__type", containsString("InvalidParameterException"))
                .body("message", containsString("interactive"));
    }

    @Test
    void executeCommandIsRefusedForAClusterThatDoesNotExist() {
        String taskArn = runTask("exec-unknown-cluster", true);

        call("ExecuteCommand", "{\"cluster\":\"no-such-cluster\",\"task\":\"" + taskArn
                + "\",\"container\":\"app\",\"command\":\"/bin/sh\",\"interactive\":true}", 400)
                .then().body("__type", containsString("ClusterNotFoundException"));
    }

    @Test
    void executeCommandIsRefusedForATaskInAnotherCluster() {
        String taskArn = runTask("exec-other-cluster", true);
        call("CreateCluster", "{\"clusterName\":\"exec-bystander\"}", 200);

        call("ExecuteCommand", "{\"cluster\":\"exec-bystander\",\"task\":\"" + taskArn
                + "\",\"container\":\"app\",\"command\":\"/bin/sh\",\"interactive\":true}", 400)
                .then()
                .body("__type", containsString("InvalidParameterException"))
                .body("message", containsString("is not part of the cluster"));
    }

    @Test
    void executeCommandRejectsAnEmptyCommand() {
        String taskArn = runTask("exec-empty-command", true);

        call("ExecuteCommand", "{\"cluster\":\"" + CLUSTER + "\",\"task\":\"" + taskArn
                + "\",\"container\":\"app\",\"command\":\"\",\"interactive\":true}", 400)
                .then().body("message", containsString("command cannot be empty"));
    }
}
