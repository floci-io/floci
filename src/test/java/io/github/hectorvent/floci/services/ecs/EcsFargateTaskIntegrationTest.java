package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RunTask and DescribeTasks for the Fargate launch type: the members an SDK client, a waiter or
 * the CLI reads off a task, and the placement rules that decide whether a task runs on FARGATE or
 * FARGATE_SPOT.
 */
@QuarkusTest
class EcsFargateTaskIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";
    private static final String CLUSTER = "fargate-task-cluster";
    private static final String SUBNET = "subnet-default-us-east-1-a";
    private static final String NETWORK =
            "\"networkConfiguration\":{\"awsvpcConfiguration\":{\"subnets\":[\"" + SUBNET + "\"]}}";

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

    private static String seed(String family) {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);
        call("RegisterTaskDefinition", "{\"family\":\"" + family + "\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}", 200);
        return family;
    }

    private static Response runTask(String family, String extraMembers) {
        return call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family + "\","
                + "\"launchType\":\"FARGATE\"," + NETWORK + extraMembers + "}", 200);
    }

    @Test
    void runTaskReportsThePlatformAndConnectivityMembersOfAFargateTask() {
        String family = seed("fargate-task-platform");
        Response response = runTask(family, "");

        response.then()
                .body("tasks", hasSize(1))
                .body("tasks[0].launchType", equalTo("FARGATE"))
                .body("tasks[0].platformVersion", equalTo("1.4.0"))
                .body("tasks[0].platformFamily", equalTo("Linux"))
                .body("tasks[0].connectivity", equalTo("CONNECTED"))
                .body("tasks[0].healthStatus", equalTo("UNKNOWN"))
                .body("tasks[0].enableExecuteCommand", equalTo(false))
                .body("tasks[0].ephemeralStorage.sizeInGiB", equalTo(20))
                .body("tasks[0].group", equalTo("family:" + family))
                .body("tasks[0].cpu", equalTo("256"))
                .body("tasks[0].memory", equalTo("512"))
                .body("tasks[0].attributes[0].name", equalTo("ecs.cpu-architecture"));
        assertTrue(response.jsonPath().getLong("tasks[0].version") >= 1, "version must be reported");
        assertTrue(response.jsonPath().getDouble("tasks[0].connectivityAt") > 0,
                "connectivityAt must be reported");
    }

    @Test
    void runTaskGivesAnAwsvpcTaskItsOwnEni() {
        String family = seed("fargate-task-eni");
        Response response = runTask(family, "");

        // The ENI is what a client waits on to find a Fargate task's address, so it is reported
        // whether or not security-group enforcement is on and whether or not Docker is in play.
        response.then()
                .body("tasks[0].attachments", hasSize(1))
                .body("tasks[0].attachments[0].type", equalTo("ElasticNetworkInterface"))
                .body("tasks[0].attachments[0].status", equalTo("ATTACHED"))
                .body("tasks[0].availabilityZone", equalTo("us-east-1a"));

        String eniId = attachmentDetail(response, "networkInterfaceId");
        String privateIp = attachmentDetail(response, "privateIPv4Address");
        assertTrue(eniId != null && eniId.startsWith("eni-"), "the task must report its ENI id");
        assertTrue(privateIp != null && !privateIp.isBlank(), "the task must report a private address");
        assertEquals(SUBNET, attachmentDetail(response, "subnetId"));

        // The ENI is a real one in EC2, in the subnet the task asked for.
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeNetworkInterfaces")
                .formParam("Version", "2016-11-15")
                .formParam("NetworkInterfaceId.1", eniId)
                .when().post("/")
                .then().statusCode(200)
                .body(containsString(SUBNET));

        String taskArn = response.jsonPath().getString("tasks[0].taskArn");
        call("StopTask", "{\"cluster\":\"" + CLUSTER + "\",\"task\":\"" + taskArn + "\"}", 200)
                .then().body("task.attachments[0].status", equalTo("DELETED"));
    }

    private static String attachmentDetail(Response response, String name) {
        List<String> values = response.jsonPath()
                .getList("tasks[0].attachments[0].details.findAll { it.name == '" + name + "' }.value");
        return values.isEmpty() ? null : values.getFirst();
    }

    @Test
    void runTaskRejectsASubnetThatDoesNotExist() {
        String family = seed("fargate-task-bad-subnet");
        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"launchType\":\"FARGATE\",\"networkConfiguration\":{\"awsvpcConfiguration\":"
                + "{\"subnets\":[\"subnet-does-not-exist\"]}}}", 400)
                .then().body("message", containsString("subnet-does-not-exist"));
    }

    @Test
    void runTaskResolvesTheLatestPlatformVersionToAConcreteOne() {
        String family = seed("fargate-task-latest");
        runTask(family, ",\"platformVersion\":\"LATEST\"").then()
                .body("tasks[0].platformVersion", equalTo("1.4.0"));
        runTask(family, ",\"platformVersion\":\"1.3.0\"").then()
                .body("tasks[0].platformVersion", equalTo("1.3.0"));
    }

    @Test
    void runTaskEchoesTheOverridesItWasGiven() {
        String family = seed("fargate-task-overrides");
        runTask(family, ",\"overrides\":{\"cpu\":\"512\",\"memory\":\"1024\","
                + "\"ephemeralStorage\":{\"sizeInGiB\":40},"
                + "\"containerOverrides\":[{\"name\":\"app\",\"command\":[\"sleep\",\"1\"],"
                + "\"environment\":[{\"name\":\"MODE\",\"value\":\"test\"}]}]}").then()
                .body("tasks[0].cpu", equalTo("512"))
                .body("tasks[0].memory", equalTo("1024"))
                .body("tasks[0].ephemeralStorage.sizeInGiB", equalTo(40))
                .body("tasks[0].overrides.cpu", equalTo("512"))
                .body("tasks[0].overrides.ephemeralStorage.sizeInGiB", equalTo(40))
                .body("tasks[0].overrides.containerOverrides[0].name", equalTo("app"))
                .body("tasks[0].overrides.containerOverrides[0].command[0]", equalTo("sleep"))
                .body("tasks[0].overrides.containerOverrides[0].environment[0].name", equalTo("MODE"));
    }

    @Test
    void runTaskCarriesEnableExecuteCommandAndTags() {
        String family = seed("fargate-task-exec");
        runTask(family, ",\"enableExecuteCommand\":true,\"tags\":[{\"key\":\"owner\",\"value\":\"platform\"}]")
                .then()
                .body("tasks[0].enableExecuteCommand", equalTo(true))
                .body("tasks[0].tags[0].key", equalTo("owner"))
                .body("tasks[0].tags[0].value", equalTo("platform"));
    }

    @Test
    void stopTaskReportsTheStopCodeAndAdvancesTheVersion() {
        String family = seed("fargate-task-stop");
        Response launched = runTask(family, "");
        String taskArn = launched.jsonPath().getString("tasks[0].taskArn");
        long versionWhenRunning = launched.jsonPath().getLong("tasks[0].version");

        call("StopTask", "{\"cluster\":\"" + CLUSTER + "\",\"task\":\"" + taskArn
                + "\",\"reason\":\"done\"}", 200).then()
                .body("task.stopCode", equalTo("UserInitiated"))
                .body("task.stoppedReason", equalTo("done"));

        Response described = call("DescribeTasks", "{\"cluster\":\"" + CLUSTER + "\",\"tasks\":[\""
                + taskArn + "\"]}", 200);
        described.then()
                .body("tasks[0].lastStatus", equalTo("STOPPED"))
                .body("tasks[0].stopCode", equalTo("UserInitiated"));
        assertTrue(described.jsonPath().getLong("tasks[0].version") > versionWhenRunning,
                "stopping a task must advance its version");
        assertTrue(described.jsonPath().getDouble("tasks[0].executionStoppedAt") > 0,
                "executionStoppedAt must be reported once the task has stopped");
    }

    @Test
    void runTaskPlacesThroughACapacityProviderStrategyByBaseThenWeight() {
        String family = seed("fargate-task-strategy");
        Response response = call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\""
                + family + "\",\"count\":5," + NETWORK + ",\"capacityProviderStrategy\":["
                + "{\"capacityProvider\":\"FARGATE\",\"base\":1,\"weight\":1},"
                + "{\"capacityProvider\":\"FARGATE_SPOT\",\"weight\":3}]}", 200);

        List<String> providers = response.jsonPath().getList("tasks.capacityProviderName");
        assertEquals(5, providers.size());
        assertEquals(2, providers.stream().filter("FARGATE"::equals).count(),
                "one task for the base plus its quarter of the remaining four");
        assertEquals(3, providers.stream().filter("FARGATE_SPOT"::equals).count(),
                "three quarters of the remaining four");
        // A task reports both the provider it was placed through and the launch type that
        // provider resolves to, unlike a service, which reports only one of the two.
        response.then().body("tasks[0].launchType", equalTo("FARGATE"));
    }

    @Test
    void runTaskRejectsALaunchTypeAndACapacityProviderStrategyTogether() {
        String family = seed("fargate-task-both");
        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"launchType\":\"FARGATE\"," + NETWORK + ",\"capacityProviderStrategy\":"
                + "[{\"capacityProvider\":\"FARGATE_SPOT\",\"weight\":1}]}", 400)
                .then().body("message", containsString("cannot specify both"));
    }

    @Test
    void runTaskRejectsAnUnknownCapacityProvider() {
        String family = seed("fargate-task-unknown-provider");
        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family + "\","
                + NETWORK + ",\"capacityProviderStrategy\":"
                + "[{\"capacityProvider\":\"NOT_A_PROVIDER\",\"weight\":1}]}", 400)
                .then().body("message", containsString("NOT_A_PROVIDER"));
    }

    @Test
    void runTaskRejectsAnAwsvpcTaskWithNoNetworkConfiguration() {
        String family = seed("fargate-task-no-network");
        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"launchType\":\"FARGATE\"}", 400)
                .then().body("message", containsString("Network Configuration must be provided"));
    }

    @Test
    void createServiceReportsThePlatformAndExecCommandMembers() {
        String family = seed("fargate-service-members");
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"fargate-members-svc\","
                + "\"taskDefinition\":\"" + family + "\",\"desiredCount\":0,\"launchType\":\"FARGATE\","
                + NETWORK + ",\"platformVersion\":\"LATEST\",\"enableExecuteCommand\":true,"
                + "\"enableECSManagedTags\":true,\"propagateTags\":\"SERVICE\","
                + "\"healthCheckGracePeriodSeconds\":30,"
                + "\"deploymentConfiguration\":{\"maximumPercent\":200,\"minimumHealthyPercent\":100}}", 200)
                .then()
                .body("service.platformVersion", equalTo("1.4.0"))
                .body("service.platformFamily", equalTo("Linux"))
                .body("service.enableExecuteCommand", equalTo(true))
                .body("service.enableECSManagedTags", equalTo(true))
                .body("service.propagateTags", equalTo("SERVICE"))
                .body("service.healthCheckGracePeriodSeconds", equalTo(30))
                .body("service.deploymentConfiguration.maximumPercent", equalTo(200))
                .body("service.deploymentConfiguration.minimumHealthyPercent", equalTo(100));

        call("DescribeServices", "{\"cluster\":\"" + CLUSTER
                + "\",\"services\":[\"fargate-members-svc\"]}", 200)
                .then()
                .body("services[0].platformVersion", equalTo("1.4.0"))
                .body("services[0].enableExecuteCommand", equalTo(true))
                .body("services[0].deploymentConfiguration.minimumHealthyPercent", equalTo(100));
    }

    @Test
    void createServiceOnACapacityProviderStrategyReportsItInsteadOfALaunchType() {
        String family = seed("fargate-service-strategy");
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"fargate-spot-svc\","
                + "\"taskDefinition\":\"" + family + "\",\"desiredCount\":0," + NETWORK + ","
                + "\"capacityProviderStrategy\":[{\"capacityProvider\":\"FARGATE_SPOT\",\"weight\":1}]}", 200)
                .then()
                .body("service.capacityProviderStrategy[0].capacityProvider", equalTo("FARGATE_SPOT"))
                .body("service.capacityProviderStrategy[0].weight", equalTo(1))
                .body("service.launchType", nullValue());
    }
}
