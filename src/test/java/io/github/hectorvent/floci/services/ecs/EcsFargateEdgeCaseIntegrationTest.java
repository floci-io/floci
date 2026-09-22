package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edges of the Fargate rules: the boundary values of the documented ranges, the parameters
 * that are only invalid in combination, and the limits AWS puts on a single call.
 */
@QuarkusTest
class EcsFargateEdgeCaseIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";
    private static final String CLUSTER = "fargate-edge-cluster";
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

    private static void registerSize(String family, String cpu, String memory, int expectedStatus) {
        call("RegisterTaskDefinition", "{\"family\":\"" + family + "\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"" + cpu + "\",\"memory\":\"" + memory + "\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}",
                expectedStatus);
    }

    private static String seed(String family) {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);
        registerSize(family, "256", "512", 200);
        return family;
    }

    // ── Task size ─────────────────────────────────────────────────────────────

    @Test
    void theEdgesOfEveryFargateSizeRangeAreAccepted() {
        registerSize("edge-256-low", "256", "512", 200);
        registerSize("edge-256-high", "256", "2048", 200);
        registerSize("edge-512-low", "512", "1024", 200);
        registerSize("edge-512-high", "512", "4096", 200);
        registerSize("edge-2048-low", "2048", "4096", 200);
        registerSize("edge-2048-high", "2048", "16384", 200);
        registerSize("edge-4096-high", "4096", "30720", 200);
        registerSize("edge-8192-low", "8192", "16384", 200);
        registerSize("edge-16384-high", "16384", "122880", 200);
    }

    @Test
    void justOutsideEveryFargateSizeRangeIsRejected() {
        registerSize("edge-256-below", "256", "256", 400);
        registerSize("edge-256-above", "256", "3072", 400);
        registerSize("edge-512-below", "512", "512", 400);
        registerSize("edge-512-above", "512", "5120", 400);
        registerSize("edge-4096-above", "4096", "31744", 400);
        registerSize("edge-16384-above", "16384", "131072", 400);
        registerSize("edge-unknown-cpu", "384", "1024", 400);
    }

    @Test
    void theThirtyTwoVcpuSizesAreTheThreeFargateOffers() {
        registerSize("edge-32vcpu-60", "32768", "61440", 200);
        registerSize("edge-32vcpu-120", "32768", "122880", 200);
        registerSize("edge-32vcpu-244", "32768", "249856", 200);
        // 32 vCPU is not a range: anything between the three offers is not a configuration.
        registerSize("edge-32vcpu-between", "32768", "65536", 400);
    }

    @Test
    void theStringFormsOfCpuAndMemoryAreAccepted() {
        registerSize("edge-vcpu-string", "0.5 vCPU", "1 GB", 200);
        registerSize("edge-vcpu-lowercase", "1 vcpu", "2GB", 200);
    }

    // ── Ephemeral storage ─────────────────────────────────────────────────────

    @Test
    void ephemeralStorageIsAcceptedAtBothEndsOfItsRange() {
        String body = "{\"family\":\"edge-storage-%s\",\"requiresCompatibilities\":[\"FARGATE\"],"
                + "\"networkMode\":\"awsvpc\",\"cpu\":\"256\",\"memory\":\"512\","
                + "\"ephemeralStorage\":{\"sizeInGiB\":%d},"
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}";

        call("RegisterTaskDefinition", body.formatted("min", 21), 200);
        call("RegisterTaskDefinition", body.formatted("max", 200), 200);
        call("RegisterTaskDefinition", body.formatted("below", 20), 400);
        call("RegisterTaskDefinition", body.formatted("above", 201), 400);
    }

    // ── Parameters Fargate does not support ───────────────────────────────────

    @Test
    void everyFargateUnsupportedParameterIsRejected() {
        String container = "{\"family\":\"edge-unsupported-%s\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\",\"containerDefinitions\":"
                + "[{\"name\":\"app\",\"image\":\"nginx:latest\",%s}]}";

        call("RegisterTaskDefinition",
                container.formatted("dso", "\"dockerSecurityOptions\":[\"label:user:jdoe\"]"), 400)
                .then().body("message", containsString("dockerSecurityOptions"));
        call("RegisterTaskDefinition",
                container.formatted("hosts", "\"extraHosts\":[{\"hostname\":\"db\",\"ipAddress\":\"10.0.0.9\"}]"), 400)
                .then().body("message", containsString("extraHosts"));
        call("RegisterTaskDefinition",
                container.formatted("gpu", "\"resourceRequirements\":[{\"type\":\"GPU\",\"value\":\"1\"}]"), 400)
                .then().body("message", containsString("gpu"));
        call("RegisterTaskDefinition",
                container.formatted("swap", "\"linuxParameters\":{\"maxSwap\":128}"), 400)
                .then().body("message", containsString("maxSwap"));
        call("RegisterTaskDefinition",
                container.formatted("swappiness", "\"linuxParameters\":{\"swappiness\":10}"), 400)
                .then().body("message", containsString("swappiness"));

        call("RegisterTaskDefinition", "{\"family\":\"edge-unsupported-placement\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\","
                + "\"placementConstraints\":[{\"type\":\"memberOf\",\"expression\":\"attribute:ecs.os-type==linux\"}],"
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}", 400)
                .then().body("message", containsString("placementConstraints"));
    }

    @Test
    void pidModeTaskIsTheOnlyOneFargateAccepts() {
        String body = "{\"family\":\"edge-pidmode-%s\",\"requiresCompatibilities\":[\"FARGATE\"],"
                + "\"networkMode\":\"awsvpc\",\"cpu\":\"256\",\"memory\":\"512\",\"pidMode\":\"%s\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}";

        call("RegisterTaskDefinition", body.formatted("task", "task"), 200);
        call("RegisterTaskDefinition", body.formatted("host", "host"), 400)
                .then().body("message", containsString("pidMode"));
    }

    @Test
    void aDependencyCycleIsRejectedAtRegistration() {
        call("RegisterTaskDefinition", "{\"family\":\"edge-cycle\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\",\"containerDefinitions\":["
                + "{\"name\":\"a\",\"image\":\"busybox\","
                + "\"dependsOn\":[{\"containerName\":\"b\",\"condition\":\"START\"}]},"
                + "{\"name\":\"b\",\"image\":\"busybox\","
                + "\"dependsOn\":[{\"containerName\":\"a\",\"condition\":\"START\"}]}]}", 400)
                .then().body("message", containsString("cycle"));
    }

    // ── RunTask limits ────────────────────────────────────────────────────────

    @Test
    void runTaskPlacesAtMostTenTasksPerCall() {
        String family = seed("edge-count");

        Response ten = call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\""
                + family + "\",\"count\":10,\"launchType\":\"FARGATE\"," + NETWORK + "}", 200);
        ten.then().body("tasks", hasSize(10));

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"count\":11,\"launchType\":\"FARGATE\"," + NETWORK + "}", 400)
                .then().body("message", containsString("between 1 and 10"));
        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"count\":0,\"launchType\":\"FARGATE\"," + NETWORK + "}", 400);

        ten.jsonPath().getList("tasks.taskArn", String.class).forEach(taskArn ->
                call("StopTask", "{\"cluster\":\"" + CLUSTER + "\",\"task\":\"" + taskArn + "\"}", 200));
    }

    @Test
    void propagatingServiceTagsIsRefusedForAStandaloneTask() {
        String family = seed("edge-propagate");

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"launchType\":\"FARGATE\"," + NETWORK + ",\"propagateTags\":\"SERVICE\"}", 400)
                .then().body("message", containsString("SERVICE"));
    }

    @Test
    void propagatingTaskDefinitionTagsCopiesThemOntoTheTask() {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);
        call("RegisterTaskDefinition", "{\"family\":\"edge-td-tags\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\",\"tags\":[{\"key\":\"owner\",\"value\":\"platform\"}],"
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}", 200);

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"edge-td-tags\","
                + "\"launchType\":\"FARGATE\"," + NETWORK + ",\"propagateTags\":\"TASK_DEFINITION\"}", 200)
                .then()
                .body("tasks[0].tags[0].key", equalTo("owner"))
                .body("tasks[0].tags[0].value", equalTo("platform"));
    }

    /**
     * The ENI allocation reaches into EC2, whose not-found codes RunTask does not declare. An SDK
     * given {@code InvalidSubnetID.NotFound} sees an unmodelled failure instead of the
     * InvalidParameterException AWS returns for a subnet that is not there.
     */
    @Test
    void runTaskRejectsAnUnknownSubnetAsAnInvalidParameter() {
        String family = seed("edge-unknown-subnet");

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"launchType\":\"FARGATE\",\"networkConfiguration\":{\"awsvpcConfiguration\":"
                + "{\"subnets\":[\"subnet-does-not-exist\"]}}}", 400)
                .then()
                .body("__type", containsString("InvalidParameterException"))
                .body("message", containsString("subnet-does-not-exist"));
    }

    @Test
    void runTaskRejectsAnUnknownSecurityGroupAsAnInvalidParameter() {
        String family = seed("edge-unknown-sg");

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"launchType\":\"FARGATE\",\"networkConfiguration\":{\"awsvpcConfiguration\":"
                + "{\"subnets\":[\"subnet-default-us-east-1-a\"],\"securityGroups\":[\"sg-nope\"]}}}", 400)
                .then()
                .body("__type", containsString("InvalidParameterException"))
                .body("message", containsString("sg-nope"));
    }

    /**
     * MANAGED_INSTANCES joined LaunchType and Compatibility in the ECS model. Rejecting it would
     * 400 a request the SDK considers valid.
     */
    @Test
    void managedInstancesIsAcceptedAsACompatibilityAndALaunchType() {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);
        call("RegisterTaskDefinition", "{\"family\":\"edge-managed-instances\","
                + "\"requiresCompatibilities\":[\"EC2\",\"MANAGED_INSTANCES\"],"
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\","
                + "\"memory\":512}]}", 200)
                .then().body("taskDefinition.requiresCompatibilities", hasItems("MANAGED_INSTANCES"));

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"edge-managed-instances\","
                + "\"launchType\":\"MANAGED_INSTANCES\"}", 200)
                .then().body("tasks[0].launchType", equalTo("MANAGED_INSTANCES"));
    }

    // ── Capacity provider strategy ────────────────────────────────────────────

    @Test
    void aStrategyWithTwoBasesIsRejected() {
        String family = seed("edge-two-bases");

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family + "\","
                + NETWORK + ",\"capacityProviderStrategy\":["
                + "{\"capacityProvider\":\"FARGATE\",\"base\":1,\"weight\":1},"
                + "{\"capacityProvider\":\"FARGATE_SPOT\",\"base\":1,\"weight\":1}]}", 400)
                .then().body("message", containsString("base"));
    }

    @Test
    void aLoneProviderOfWeightZeroStillPlacesEveryTask() {
        String family = seed("edge-zero-weight");

        Response response = call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\""
                + family + "\",\"count\":3," + NETWORK + ",\"capacityProviderStrategy\":"
                + "[{\"capacityProvider\":\"FARGATE_SPOT\",\"weight\":0}]}", 200);

        List<String> providers = response.jsonPath().getList("tasks.capacityProviderName");
        assertEquals(3, providers.size(), "a weightless strategy must still place every task");
        assertTrue(providers.stream().allMatch("FARGATE_SPOT"::equals), providers.toString());
    }

    @Test
    void aStrategyWhoseProvidersAllWeighZeroIsRejected() {
        String family = seed("edge-all-zero-weights");

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family + "\","
                + NETWORK + ",\"capacityProviderStrategy\":["
                + "{\"capacityProvider\":\"FARGATE\",\"weight\":0},"
                + "{\"capacityProvider\":\"FARGATE_SPOT\",\"weight\":0}]}", 400)
                .then().body("message", containsString("weight greater than zero"));
    }

    @Test
    void aStrategyEntryOutsideTheWeightAndBaseRangesIsRejected() {
        String family = seed("edge-strategy-ranges");

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family + "\","
                + NETWORK + ",\"capacityProviderStrategy\":"
                + "[{\"capacityProvider\":\"FARGATE\",\"weight\":1001}]}", 400)
                .then().body("message", containsString("between 0 and 1000"));
        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family + "\","
                + NETWORK + ",\"capacityProviderStrategy\":"
                + "[{\"capacityProvider\":\"FARGATE\",\"weight\":1,\"base\":100001}]}", 400)
                .then().body("message", containsString("between 0 and 100000"));

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family + "\","
                + NETWORK + ",\"capacityProviderStrategy\":"
                + "[{\"capacityProvider\":\"FARGATE\",\"weight\":1000,\"base\":100000}]}", 200)
                .then().body("tasks[0].capacityProviderName", equalTo("FARGATE"));
    }

    @Test
    void aStrategyOfMoreThanTwentyProvidersIsRejected() {
        String family = seed("edge-strategy-size");
        StringBuilder strategy = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            strategy.append(i == 0 ? "" : ",")
                    .append("{\"capacityProvider\":\"FARGATE\",\"weight\":1}");
        }

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family + "\","
                + NETWORK + ",\"capacityProviderStrategy\":[" + strategy + "]}", 400)
                .then().body("message", containsString("maximum of 20 capacity providers"));
    }

    @Test
    void anEmptyStrategyFallsBackToTheLaunchType() {
        String family = seed("edge-empty-strategy");

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"launchType\":\"FARGATE\"," + NETWORK + ",\"capacityProviderStrategy\":[]}", 200)
                .then().body("tasks[0].launchType", equalTo("FARGATE"));
    }

    // ── Task shape ────────────────────────────────────────────────────────────

    @Test
    void aStoppedTaskReportsTheTimestampsAndCodesAwsReports() {
        String family = seed("edge-stopped-shape");
        String taskArn = call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\""
                + family + "\",\"launchType\":\"FARGATE\"," + NETWORK + "}", 200)
                .jsonPath().getString("tasks[0].taskArn");

        Response stopped = call("StopTask", "{\"cluster\":\"" + CLUSTER + "\",\"task\":\""
                + taskArn + "\",\"reason\":\"edge\"}", 200);

        stopped.then()
                .body("task.stopCode", equalTo("UserInitiated"))
                .body("task.lastStatus", equalTo("STOPPED"));
        assertTrue(stopped.jsonPath().getDouble("task.stoppingAt") > 0,
                "stoppingAt must be reported for a task that went through STOPPING");
        assertTrue(stopped.jsonPath().getDouble("task.executionStoppedAt") > 0,
                "executionStoppedAt must be reported");
    }

    @Test
    void aFargateTaskReportsBothEphemeralStorageMembersAndZeroCpuContainers() {
        String family = seed("edge-storage-members");

        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"launchType\":\"FARGATE\"," + NETWORK + "}", 200)
                .then()
                .body("tasks[0].ephemeralStorage.sizeInGiB", equalTo(20))
                .body("tasks[0].fargateEphemeralStorage.sizeInGiB", equalTo(20))
                // A container definition without cpu units reports zero, not nothing.
                .body("tasks[0].containers[0].cpu", equalTo("0"));
    }

    @Test
    void describeTasksReportsTagsOnlyWhenTheyAreAskedFor() {
        String family = seed("edge-describe-tags");
        String taskArn = call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\""
                + family + "\",\"launchType\":\"FARGATE\"," + NETWORK
                + ",\"tags\":[{\"key\":\"owner\",\"value\":\"platform\"}]}", 200)
                .jsonPath().getString("tasks[0].taskArn");

        call("DescribeTasks", "{\"cluster\":\"" + CLUSTER + "\",\"tasks\":[\"" + taskArn + "\"]}", 200)
                .then().body("tasks[0].tags", nullValue());

        call("DescribeTasks", "{\"cluster\":\"" + CLUSTER + "\",\"tasks\":[\"" + taskArn
                + "\"],\"include\":[\"TAGS\"]}", 200)
                .then().body("tasks[0].tags[0].key", equalTo("owner"));
    }

    @Test
    void aTaskAlwaysReportsAnOverrideEntryPerContainer() {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);
        call("RegisterTaskDefinition", "{\"family\":\"edge-overrides\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\",\"containerDefinitions\":["
                + "{\"name\":\"app\",\"image\":\"nginx:latest\"},"
                + "{\"name\":\"sidecar\",\"image\":\"busybox\",\"essential\":false}]}", 200);

        // Nothing was overridden, and AWS still lists every container under overrides.
        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"edge-overrides\","
                + "\"launchType\":\"FARGATE\"," + NETWORK + "}", 200)
                .then()
                .body("tasks[0].overrides.containerOverrides", hasSize(2))
                .body("tasks[0].overrides.containerOverrides[0].name", equalTo("app"))
                .body("tasks[0].overrides.containerOverrides[1].name", equalTo("sidecar"));
    }

    @Test
    void anAwsvpcTaskReportsTheAttachmentDetailsAwsReports() {
        String family = seed("edge-attachment-details");
        Response response = call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\""
                + family + "\",\"launchType\":\"FARGATE\"," + NETWORK + "}", 200);

        List<String> names = response.jsonPath().getList("tasks[0].attachments[0].details.name");
        assertTrue(names.containsAll(List.of("subnetId", "networkInterfaceId", "macAddress",
                        "privateDnsName", "privateIPv4Address")),
                "the attachment must carry the details AWS reports, got: " + names);
    }

    @Test
    void aCreatedServiceReportsTheDocumentedDefaults() {
        String family = seed("edge-service-defaults");

        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"edge-defaults-svc\","
                + "\"taskDefinition\":\"" + family + "\",\"desiredCount\":0,\"launchType\":\"FARGATE\","
                + NETWORK + "}", 200)
                .then()
                .body("service.propagateTags", equalTo("NONE"))
                .body("service.healthCheckGracePeriodSeconds", equalTo(0))
                .body("service.enableExecuteCommand", equalTo(false))
                .body("service.enableECSManagedTags", equalTo(false));
    }

    @Test
    void aServiceRoleIsOnlyPermittedWithALoadBalancerAndWithoutAwsvpc() {
        String family = seed("edge-service-role");

        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"edge-role-svc\","
                + "\"taskDefinition\":\"" + family + "\",\"desiredCount\":0,\"launchType\":\"FARGATE\","
                + NETWORK + ",\"role\":\"arn:aws:iam::000000000000:role/ecsServiceRole\"}", 400)
                .then().body("message", containsString("role parameter"));
    }

    // ── ListTasks filters ─────────────────────────────────────────────────────

    @Test
    void listTasksDefaultsToTheRunningTasks() {
        String family = seed("edge-list-default");
        String running = call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\""
                + family + "\",\"launchType\":\"FARGATE\"," + NETWORK + "}", 200)
                .jsonPath().getString("tasks[0].taskArn");
        String stopped = call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\""
                + family + "\",\"launchType\":\"FARGATE\"," + NETWORK + "}", 200)
                .jsonPath().getString("tasks[0].taskArn");
        call("StopTask", "{\"cluster\":\"" + CLUSTER + "\",\"task\":\"" + stopped + "\"}", 200);

        // Without a desiredStatus the listing is RUNNING only, which is AWS's default filter.
        List<String> byDefault = call("ListTasks", "{\"cluster\":\"" + CLUSTER + "\",\"family\":\""
                + family + "\"}", 200).jsonPath().getList("taskArns");
        assertTrue(byDefault.contains(running), "a running task must be listed by default");
        assertFalse(byDefault.contains(stopped), "a stopped task must not be listed by default");

        List<String> stoppedOnly = call("ListTasks", "{\"cluster\":\"" + CLUSTER + "\",\"family\":\""
                + family + "\",\"desiredStatus\":\"STOPPED\"}", 200).jsonPath().getList("taskArns");
        assertTrue(stoppedOnly.contains(stopped), "STOPPED must list the stopped task");
        assertFalse(stoppedOnly.contains(running), "STOPPED must not list the running task");

        // ECS never sets a desired status of PENDING, so that filter matches nothing.
        assertTrue(call("ListTasks", "{\"cluster\":\"" + CLUSTER + "\",\"family\":\"" + family
                + "\",\"desiredStatus\":\"PENDING\"}", 200)
                .jsonPath().getList("taskArns").isEmpty(), "PENDING must match nothing");
    }

    @Test
    void listTasksFiltersByLaunchTypeAndStartedBy() {
        String family = seed("edge-list-filters");
        String tagged = call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\""
                + family + "\",\"launchType\":\"FARGATE\"," + NETWORK
                + ",\"startedBy\":\"edge-batch-7\"}", 200)
                .jsonPath().getString("tasks[0].taskArn");

        assertTrue(call("ListTasks", "{\"cluster\":\"" + CLUSTER + "\",\"family\":\"" + family
                + "\",\"launchType\":\"FARGATE\"}", 200)
                .jsonPath().getList("taskArns").contains(tagged));
        assertTrue(call("ListTasks", "{\"cluster\":\"" + CLUSTER + "\",\"family\":\"" + family
                + "\",\"launchType\":\"EC2\"}", 200)
                .jsonPath().getList("taskArns").isEmpty(), "no task here runs on EC2");

        assertTrue(call("ListTasks", "{\"startedBy\":\"edge-batch-7\"}", 200)
                .jsonPath().getList("taskArns").contains(tagged));
        // The cluster scopes the listing rather than filtering it, so it pairs with startedBy.
        assertTrue(call("ListTasks", "{\"cluster\":\"" + CLUSTER + "\",\"startedBy\":\"edge-batch-7\"}", 200)
                .jsonPath().getList("taskArns").contains(tagged));
        // Any other filter alongside startedBy is still refused.
        call("ListTasks", "{\"cluster\":\"" + CLUSTER + "\",\"family\":\"" + family
                + "\",\"startedBy\":\"edge-batch-7\"}", 400)
                .then().body("message", containsString("only filter"));
    }

    @Test
    void listTasksPaginates() {
        String family = seed("edge-list-paging");
        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"count\":3,\"launchType\":\"FARGATE\"," + NETWORK + "}", 200);

        Response first = call("ListTasks", "{\"cluster\":\"" + CLUSTER + "\",\"family\":\"" + family
                + "\",\"maxResults\":2}", 200);
        first.then().body("taskArns", hasSize(2));
        String token = first.jsonPath().getString("nextToken");
        assertTrue(token != null && !token.isBlank(), "a truncated listing must return a token");

        Response second = call("ListTasks", "{\"cluster\":\"" + CLUSTER + "\",\"family\":\"" + family
                + "\",\"maxResults\":2,\"nextToken\":\"" + token + "\"}", 200);
        second.then().body("taskArns", hasSize(1));
        assertEquals(null, second.jsonPath().getString("nextToken"),
                "the last page carries no token");
    }

    @Test
    void onlyTheDocumentedUpdatesRollTheServiceDeployment() {
        String family = seed("edge-update-rolls");
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"edge-roll-svc\","
                + "\"taskDefinition\":\"" + family + "\",\"desiredCount\":0,\"launchType\":\"FARGATE\","
                + NETWORK + "}", 200);

        String initial = deploymentId("edge-roll-svc");

        // desiredCount and the exec switch are documented as not triggering a deployment.
        call("UpdateService", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"edge-roll-svc\","
                + "\"desiredCount\":0,\"enableExecuteCommand\":true}", 200);
        assertEquals(initial, deploymentId("edge-roll-svc"),
                "desiredCount and enableExecuteCommand must not roll the deployment");

        // A changed network configuration starts new tasks, so it rolls.
        call("UpdateService", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"edge-roll-svc\","
                + "\"networkConfiguration\":{\"awsvpcConfiguration\":{\"subnets\":"
                + "[\"subnet-default-us-east-1-b\"]}}}", 200);
        assertNotEquals(initial, deploymentId("edge-roll-svc"),
                "a changed network configuration must roll the deployment");

        // platformVersion is documented as triggering a deployment, but only where it changes:
        // LATEST on a service already running the version LATEST resolves to is not a change.
        String rolled = deploymentId("edge-roll-svc");
        call("UpdateService", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"edge-roll-svc\","
                + "\"platformVersion\":\"LATEST\"}", 200);
        assertEquals(rolled, deploymentId("edge-roll-svc"),
                "LATEST on a service already at the resolved version must not roll the deployment");

        call("UpdateService", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"edge-roll-svc\","
                + "\"platformVersion\":\"1.3.0\"}", 200)
                .then().body("service.platformVersion", equalTo("1.3.0"));
        assertNotEquals(rolled, deploymentId("edge-roll-svc"),
                "a changed platformVersion must roll the deployment");
    }

    @Test
    void anUpdateReplacesOnlyTheMembersItNames() {
        String family = seed("edge-update-members");
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"edge-members-svc\","
                + "\"taskDefinition\":\"" + family + "\",\"desiredCount\":0,\"launchType\":\"FARGATE\","
                + NETWORK + ",\"placementConstraints\":[{\"type\":\"distinctInstance\"}]}", 200)
                .then().body("service.placementConstraints[0].type", equalTo("distinctInstance"));

        call("UpdateService", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"edge-members-svc\","
                + "\"placementStrategy\":[{\"type\":\"spread\","
                + "\"field\":\"attribute:ecs.availability-zone\"}]}", 200)
                .then().body("service.placementStrategy[0].type", equalTo("spread"))
                .body("service.placementConstraints[0].type", equalTo("distinctInstance"));

        call("DescribeServices", "{\"cluster\":\"" + CLUSTER
                + "\",\"services\":[\"edge-members-svc\"]}", 200)
                .then().body("services[0].placementConstraints[0].type", equalTo("distinctInstance"))
                .body("services[0].placementStrategy[0].type", equalTo("spread"));

        // A member the update does name is replaced outright, which is how an empty array clears it.
        call("UpdateService", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"edge-members-svc\","
                + "\"placementConstraints\":[]}", 200)
                .then().body("service.placementConstraints", hasSize(0))
                .body("service.placementStrategy[0].type", equalTo("spread"));
    }

    private static String deploymentId(String serviceName) {
        return call("DescribeServices", "{\"cluster\":\"" + CLUSTER + "\",\"services\":[\""
                + serviceName + "\"]}", 200)
                .jsonPath().getString("services[0].deployments[0].id");
    }

    // ── Task definition lifecycle ─────────────────────────────────────────────

    @Test
    void aDeletedRevisionStaysDescribableAsDeleteInProgress() {
        registerSize("edge-delete-lifecycle", "256", "512", 200);

        // Deleting before deregistering is refused, and a family without a revision is not a
        // reference this API takes.
        call("DeleteTaskDefinitions", "{\"taskDefinitions\":[\"edge-delete-lifecycle:1\"]}", 400)
                .then().body("message", containsString("INACTIVE"));
        call("DeregisterTaskDefinition", "{\"taskDefinition\":\"edge-delete-lifecycle:1\"}", 200)
                .then().body("taskDefinition.status", equalTo("INACTIVE"));
        call("DeleteTaskDefinitions", "{\"taskDefinitions\":[\"edge-delete-lifecycle\"]}", 400)
                .then().body("message", containsString("revision"));

        call("DeleteTaskDefinitions", "{\"taskDefinitions\":[\"edge-delete-lifecycle:1\"]}", 200)
                .then().body("taskDefinitions[0].status", equalTo("DELETE_IN_PROGRESS"));

        // The revision is still describable, and carries the lifecycle timestamps.
        Response described = call("DescribeTaskDefinition",
                "{\"taskDefinition\":\"edge-delete-lifecycle:1\"}", 200);
        described.then().body("taskDefinition.status", equalTo("DELETE_IN_PROGRESS"));
        assertTrue(described.jsonPath().getDouble("taskDefinition.deregisteredAt") > 0,
                "a deregistered revision reports deregisteredAt");
        assertTrue(described.jsonPath().getDouble("taskDefinition.deleteRequestedAt") > 0,
                "a revision being deleted reports deleteRequestedAt");

        // And it can no longer start anything new.
        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"edge-delete-lifecycle:1\","
                + "\"launchType\":\"FARGATE\"," + NETWORK + "}", 400)
                .then().body("message", containsString("being deleted"));
    }

    @Test
    void listTaskDefinitionsDefaultsToActiveAndOrdersByRevision() {
        for (int i = 0; i < 11; i++) {
            registerSize("edge-list-td", "256", "512", 200);
        }
        call("DeregisterTaskDefinition", "{\"taskDefinition\":\"edge-list-td:1\"}", 200);

        List<String> active = call("ListTaskDefinitions",
                "{\"familyPrefix\":\"edge-list-td\"}", 200).jsonPath().getList("taskDefinitionArns");
        assertFalse(active.stream().anyMatch(arn -> arn.endsWith("edge-list-td:1")),
                "a deregistered revision is not listed by default");
        // Ascending by revision, so revision 11 comes after revision 2 rather than before it.
        assertTrue(active.getFirst().endsWith(":2"), "the lowest active revision comes first: " + active);
        assertTrue(active.getLast().endsWith(":11"), "the newest revision comes last: " + active);

        List<String> descending = call("ListTaskDefinitions",
                "{\"familyPrefix\":\"edge-list-td\",\"sort\":\"DESC\"}", 200)
                .jsonPath().getList("taskDefinitionArns");
        assertTrue(descending.getFirst().endsWith(":11"), "DESC lists the newest first: " + descending);

        List<String> inactive = call("ListTaskDefinitions",
                "{\"familyPrefix\":\"edge-list-td\",\"status\":\"INACTIVE\"}", 200)
                .jsonPath().getList("taskDefinitionArns");
        assertEquals(1, inactive.size(), "only the deregistered revision is INACTIVE");

        Response firstPage = call("ListTaskDefinitions",
                "{\"familyPrefix\":\"edge-list-td\",\"maxResults\":3}", 200);
        firstPage.then().body("taskDefinitionArns", hasSize(3));
        assertTrue(firstPage.jsonPath().getString("nextToken") != null,
                "a truncated listing returns a token");
    }

    @Test
    void listServicesFiltersAndPagesTheWayAwsDoes() {
        String family = seed("edge-list-services");
        for (int i = 0; i < 12; i++) {
            call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"edge-svc-" + i
                    + "\",\"taskDefinition\":\"" + family + "\",\"desiredCount\":0,"
                    + "\"launchType\":\"FARGATE\"," + NETWORK + "}", 200);
        }

        // ListServices pages ten at a time by default, unlike the other listings.
        Response defaultPage = call("ListServices", "{\"cluster\":\"" + CLUSTER + "\"}", 200);
        defaultPage.then().body("serviceArns", hasSize(10));
        assertTrue(defaultPage.jsonPath().getString("nextToken") != null,
                "a truncated listing returns a token");

        assertTrue(call("ListServices", "{\"cluster\":\"" + CLUSTER
                + "\",\"launchType\":\"EC2\",\"maxResults\":100}", 200)
                .jsonPath().getList("serviceArns").isEmpty(), "no service here runs on EC2");
        assertFalse(call("ListServices", "{\"cluster\":\"" + CLUSTER
                + "\",\"launchType\":\"FARGATE\",\"maxResults\":100}", 200)
                .jsonPath().getList("serviceArns").isEmpty(), "the Fargate services are listed");
        assertTrue(call("ListServices", "{\"cluster\":\"" + CLUSTER
                + "\",\"schedulingStrategy\":\"DAEMON\",\"maxResults\":100}", 200)
                .jsonPath().getList("serviceArns").isEmpty(), "none of these are daemons");
    }

    @Test
    void listTaskDefinitionFamiliesFiltersByWhetherARevisionIsActive() {
        registerSize("edge-family-active", "256", "512", 200);
        registerSize("edge-family-gone", "256", "512", 200);
        call("DeregisterTaskDefinition", "{\"taskDefinition\":\"edge-family-gone:1\"}", 200);

        // Both are listed without a status, which is AWS's default.
        List<String> all = call("ListTaskDefinitionFamilies",
                "{\"familyPrefix\":\"edge-family-\",\"maxResults\":100}", 200)
                .jsonPath().getList("families");
        assertTrue(all.containsAll(List.of("edge-family-active", "edge-family-gone")), all.toString());

        assertEquals(List.of("edge-family-active"), call("ListTaskDefinitionFamilies",
                "{\"familyPrefix\":\"edge-family-\",\"status\":\"ACTIVE\",\"maxResults\":100}", 200)
                .jsonPath().getList("families"));
        assertEquals(List.of("edge-family-gone"), call("ListTaskDefinitionFamilies",
                "{\"familyPrefix\":\"edge-family-\",\"status\":\"INACTIVE\",\"maxResults\":100}", 200)
                .jsonPath().getList("families"));
    }

    @Test
    void describeClustersWithholdsEverythingTheIncludeDidNotAskFor() {
        String cluster = "edge-include-cluster";
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\","
                + "\"settings\":[{\"name\":\"containerInsights\",\"value\":\"enabled\"}],"
                + "\"tags\":[{\"key\":\"team\",\"value\":\"platform\"}],"
                + "\"configuration\":{\"executeCommandConfiguration\":{\"logging\":\"DEFAULT\"}}}", 200);

        Response bare = call("DescribeClusters", "{\"clusters\":[\"" + cluster + "\"]}", 200);
        bare.then()
                .body("clusters[0].clusterName", equalTo(cluster))
                .body("clusters[0].settings", nullValue())
                .body("clusters[0].tags", nullValue())
                .body("clusters[0].statistics", nullValue())
                .body("clusters[0].attachments", nullValue())
                .body("clusters[0].configuration", nullValue());

        call("DescribeClusters", "{\"clusters\":[\"" + cluster + "\"],"
                + "\"include\":[\"SETTINGS\",\"TAGS\",\"STATISTICS\",\"ATTACHMENTS\",\"CONFIGURATIONS\"]}", 200)
                .then()
                .body("clusters[0].settings", hasSize(1))
                .body("clusters[0].tags", hasSize(1))
                .body("clusters[0].attachments", hasSize(0))
                .body("clusters[0].configuration.executeCommandConfiguration.logging", equalTo("DEFAULT"))
                .body("clusters[0].statistics", hasSize(8))
                .body("clusters[0].statistics.find { it.name == 'runningFargateTasksCount' }.value",
                        equalTo("0"));

        call("DescribeClusters", "{\"clusters\":[\"" + cluster + "\"],\"include\":[\"NOPE\"]}", 400);
    }

    @Test
    void describeClustersReportsAnUnknownClusterAsMissingRatherThanDroppingIt() {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);

        call("DescribeClusters", "{\"clusters\":[\"" + CLUSTER + "\",\"edge-no-such-cluster\"]}", 200)
                .then()
                .body("clusters", hasSize(1))
                .body("failures", hasSize(1))
                .body("failures[0].reason", equalTo("MISSING"))
                .body("failures[0].arn", containsString("cluster/edge-no-such-cluster"));
    }

    @Test
    void describeTasksReportsAnUnknownTaskAsMissingRatherThanDroppingIt() {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);

        call("DescribeTasks", "{\"cluster\":\"" + CLUSTER + "\",\"tasks\":[\"00000000000000000000000000000000\"]}", 200)
                .then()
                .body("tasks", hasSize(0))
                .body("failures", hasSize(1))
                .body("failures[0].reason", equalTo("MISSING"));
    }

    @Test
    void listClustersPagesTheWayAwsDoes() {
        for (int i = 0; i < 3; i++) {
            call("CreateCluster", "{\"clusterName\":\"edge-page-cluster-" + i + "\"}", 200);
        }

        Response first = call("ListClusters", "{\"maxResults\":1}", 200);
        first.then().body("clusterArns", hasSize(1)).body("nextToken", notNullValue());

        String token = first.jsonPath().getString("nextToken");
        Response second = call("ListClusters", "{\"maxResults\":1,\"nextToken\":\"" + token + "\"}", 200);
        second.then().body("clusterArns", hasSize(1));
        assertNotEquals(first.jsonPath().getString("clusterArns[0]"),
                second.jsonPath().getString("clusterArns[0]"));

        // No maxResults pages a hundred at a time, so a handful of clusters comes back whole.
        call("ListClusters", "{}", 200).then().body("nextToken", nullValue());
    }

    @Test
    void clusterStatisticsCountRunningFargateTasks() {
        String cluster = "edge-stats-cluster";
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\"}", 200);
        registerSize("edge-stats", "256", "512", 200);
        call("RunTask", "{\"cluster\":\"" + cluster + "\",\"taskDefinition\":\"edge-stats\","
                + "\"launchType\":\"FARGATE\"," + NETWORK + "}", 200);

        List<Map<String, String>> statistics = call("DescribeClusters",
                "{\"clusters\":[\"" + cluster + "\"],\"include\":[\"STATISTICS\"]}", 200)
                .jsonPath().getList("clusters[0].statistics");

        assertEquals("1", statistics.stream()
                .filter(s -> "runningFargateTasksCount".equals(s.get("name")))
                .findFirst().orElseThrow().get("value"));
        assertEquals("0", statistics.stream()
                .filter(s -> "runningEC2TasksCount".equals(s.get("name")))
                .findFirst().orElseThrow().get("value"));
    }

    // ── Task sets ─────────────────────────────────────────────────────────────

    /** Creates an EXTERNAL-controller service, which is the only kind that can hold task sets. */
    private static String seedExternalService(String name) {
        String family = seed(name + "-td");
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"" + name + "\","
                + "\"taskDefinition\":\"" + family + "\",\"desiredCount\":3,"
                + "\"deploymentController\":{\"type\":\"EXTERNAL\"},"
                + "\"launchType\":\"FARGATE\"," + NETWORK + "}", 200);
        return family;
    }

    @Test
    void aTaskSetNeedsAServiceThatUsesAnExternalDeploymentController() {
        String family = seed("edge-rolling-td");
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"edge-rolling-svc\","
                + "\"taskDefinition\":\"" + family + "\",\"desiredCount\":1,"
                + "\"launchType\":\"FARGATE\"," + NETWORK + "}", 200);

        call("CreateTaskSet", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"edge-rolling-svc\","
                + "\"taskDefinition\":\"" + family + "\"}", 400)
                .then().body("message", containsString("EXTERNAL"));
    }

    @Test
    void aTaskSetRoundTripsItsMembersAndComputesItsDesiredCount() {
        String family = seedExternalService("edge-taskset-svc");

        Response created = call("CreateTaskSet", "{\"cluster\":\"" + CLUSTER
                + "\",\"service\":\"edge-taskset-svc\",\"taskDefinition\":\"" + family + "\","
                + "\"launchType\":\"FARGATE\",\"externalId\":\"d-EXAMPLE\","
                + "\"scale\":{\"value\":50,\"unit\":\"PERCENT\"},"
                + "\"networkConfiguration\":{\"awsvpcConfiguration\":{\"subnets\":"
                + "[\"subnet-default-us-east-1-a\"]}},"
                + "\"loadBalancers\":[{\"targetGroupArn\":\"arn:aws:elasticloadbalancing:us-east-1:"
                + "000000000000:targetgroup/tg/1\",\"containerName\":\"app\",\"containerPort\":80}],"
                + "\"tags\":[{\"key\":\"team\",\"value\":\"platform\"}]}", 200);

        created.then()
                .body("taskSet.status", equalTo("ACTIVE"))
                .body("taskSet.launchType", equalTo("FARGATE"))
                .body("taskSet.platformVersion", equalTo("1.4.0"))
                .body("taskSet.platformFamily", equalTo("Linux"))
                .body("taskSet.externalId", equalTo("d-EXAMPLE"))
                .body("taskSet.scale.value", equalTo(50.0f))
                .body("taskSet.scale.unit", equalTo("PERCENT"))
                // 3 desired at 50 percent is 1.5, and AWS always rounds the computed count up.
                .body("taskSet.computedDesiredCount", equalTo(2))
                // Floci places no tasks for a task set, so it is steady rather than for ever
                // stabilising towards a count that nothing will ever reach.
                .body("taskSet.stabilityStatus", equalTo("STEADY_STATE"))
                .body("taskSet.stabilityStatusAt", notNullValue())
                .body("taskSet.networkConfiguration.awsvpcConfiguration.subnets", hasSize(1))
                .body("taskSet.loadBalancers[0].containerPort", equalTo(80));

        String id = created.jsonPath().getString("taskSet.id");

        // Tags come back from DescribeTaskSets only when the request asks for them.
        call("DescribeTaskSets", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"edge-taskset-svc\"}", 200)
                .then()
                .body("taskSets[0].tags", nullValue())
                .body("taskSets[0].stabilityStatus", equalTo("STEADY_STATE"));
        call("DescribeTaskSets", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"edge-taskset-svc\","
                + "\"include\":[\"TAGS\"]}", 200)
                .then().body("taskSets[0].tags", hasSize(1));

        // A scale change recomputes the desired count.
        call("UpdateTaskSet", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"edge-taskset-svc\","
                + "\"taskSet\":\"" + id + "\",\"scale\":{\"value\":100,\"unit\":\"PERCENT\"}}", 200)
                .then()
                .body("taskSet.computedDesiredCount", equalTo(3))
                .body("taskSet.stabilityStatus", equalTo("STEADY_STATE"));

        // Deleting a task set that has not been scaled to zero needs force.
        call("DeleteTaskSet", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"edge-taskset-svc\","
                + "\"taskSet\":\"" + id + "\"}", 400)
                .then().body("message", containsString("scaled down to zero"));
        call("DeleteTaskSet", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"edge-taskset-svc\","
                + "\"taskSet\":\"" + id + "\",\"force\":true}", 200)
                .then().body("taskSet.status", equalTo("DRAINING"));
    }

    @Test
    void describeTaskSetsReportsAnUnknownTaskSetAsMissing() {
        seedExternalService("edge-taskset-missing");

        call("DescribeTaskSets", "{\"cluster\":\"" + CLUSTER + "\","
                + "\"service\":\"edge-taskset-missing\",\"taskSets\":[\"ecs-svc/nope\"]}", 200)
                .then()
                .body("taskSets", hasSize(0))
                .body("failures", hasSize(1))
                .body("failures[0].reason", equalTo("MISSING"));

        call("UpdateTaskSet", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"edge-taskset-missing\","
                + "\"taskSet\":\"ecs-svc/nope\",\"scale\":{\"value\":100,\"unit\":\"PERCENT\"}}", 400)
                .then().body("__type", containsString("TaskSetNotFoundException"));
    }

    @Test
    void aTaskSetCannotNameBothALaunchTypeAndACapacityProviderStrategy() {
        String family = seedExternalService("edge-taskset-both");

        call("CreateTaskSet", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"edge-taskset-both\","
                + "\"taskDefinition\":\"" + family + "\",\"launchType\":\"FARGATE\","
                + "\"capacityProviderStrategy\":[{\"capacityProvider\":\"FARGATE_SPOT\",\"weight\":1}]}", 400)
                .then().body("message", containsString("capacity provider strategy"));
    }

    // ── Account settings and attributes ───────────────────────────────────────

    @Test
    void accountSettingsAreValidatedPerName() {
        call("PutAccountSetting", "{\"name\":\"nonsenseSetting\",\"value\":\"enabled\"}", 400)
                .then().body("message", containsString("Invalid account setting name"));
        call("PutAccountSetting", "{\"name\":\"containerInsights\",\"value\":\"maybe\"}", 400)
                .then().body("message", containsString("Invalid value"));

        // Two settings are not opt-in flags: a wait period in days and a log delivery mode.
        call("PutAccountSetting", "{\"name\":\"fargateTaskRetirementWaitPeriod\",\"value\":\"14\"}", 200);
        call("PutAccountSetting", "{\"name\":\"fargateTaskRetirementWaitPeriod\",\"value\":\"3\"}", 400);
        call("PutAccountSetting", "{\"name\":\"defaultLogDriverMode\",\"value\":\"blocking\"}", 200);
        call("PutAccountSetting", "{\"name\":\"defaultLogDriverMode\",\"value\":\"enabled\"}", 400);
        // enhanced is a containerInsights value only.
        call("PutAccountSetting", "{\"name\":\"containerInsights\",\"value\":\"enhanced\"}", 200);
        call("PutAccountSetting", "{\"name\":\"awsvpcTrunking\",\"value\":\"enhanced\"}", 400);
    }

    @Test
    void anAccountSettingReportsItsPrincipalAndType() {
        call("PutAccountSetting", "{\"name\":\"awsvpcTrunking\",\"value\":\"enabled\","
                + "\"principalArn\":\"arn:aws:iam::000000000000:user/edge\"}", 200)
                .then()
                .body("setting.name", equalTo("awsvpcTrunking"))
                .body("setting.value", equalTo("enabled"))
                .body("setting.principalArn", equalTo("arn:aws:iam::000000000000:user/edge"))
                .body("setting.type", equalTo("user"));

        // A principal sees only what it set; effectiveSettings fills in the rest.
        call("ListAccountSettings", "{\"principalArn\":\"arn:aws:iam::000000000000:user/edge\","
                + "\"name\":\"containerInsights\"}", 200)
                .then().body("settings", hasSize(0));
        call("ListAccountSettings", "{\"principalArn\":\"arn:aws:iam::000000000000:user/edge\","
                + "\"name\":\"containerInsights\",\"effectiveSettings\":true}", 200)
                .then().body("settings", hasSize(1));

        // GuardDuty owns its setting on the account's behalf.
        call("ListAccountSettings", "{\"name\":\"guardDutyActivate\",\"effectiveSettings\":true}", 200)
                .then().body("settings[0].type", equalTo("aws_managed"));
    }

    @Test
    void effectiveAccountSettingsFallBackToTheAccountDefaultThenTheBuiltIn() {
        String principal = "arn:aws:iam::000000000000:user/edge-fallback";

        // Untouched, the built-in default applies.
        call("ListAccountSettings", "{\"principalArn\":\"" + principal + "\","
                + "\"name\":\"awsvpcTrunking\",\"effectiveSettings\":true}", 200)
                .then().body("settings[0].value", equalTo("disabled"));

        // The account default lives on the root user, which is where AWS keeps it too.
        call("PutAccountSettingDefault", "{\"name\":\"awsvpcTrunking\",\"value\":\"enabled\"}", 200)
                .then().body("setting.principalArn", containsString(":root"));
        call("ListAccountSettings", "{\"principalArn\":\"" + principal + "\","
                + "\"name\":\"awsvpcTrunking\",\"effectiveSettings\":true}", 200)
                .then().body("settings[0].value", equalTo("enabled"));

        // The principal's own setting wins over the account default.
        call("PutAccountSetting", "{\"name\":\"awsvpcTrunking\",\"value\":\"disabled\","
                + "\"principalArn\":\"" + principal + "\"}", 200);
        call("ListAccountSettings", "{\"principalArn\":\"" + principal + "\","
                + "\"name\":\"awsvpcTrunking\",\"effectiveSettings\":true}", 200)
                .then().body("settings[0].value", equalTo("disabled"));

        // Deleting it falls back to the account default again.
        call("DeleteAccountSetting", "{\"name\":\"awsvpcTrunking\",\"principalArn\":\""
                + principal + "\"}", 200)
                .then().body("setting.value", equalTo("disabled"));
        call("ListAccountSettings", "{\"principalArn\":\"" + principal + "\","
                + "\"name\":\"awsvpcTrunking\",\"effectiveSettings\":true}", 200)
                .then().body("settings[0].value", equalTo("enabled"));

        // The full effective listing is eleven settings, which pages ten at a time.
        Response page = call("ListAccountSettings",
                "{\"principalArn\":\"" + principal + "\",\"effectiveSettings\":true}", 200);
        page.then().body("settings", hasSize(10)).body("nextToken", notNullValue());
        call("ListAccountSettings", "{\"principalArn\":\"" + principal + "\","
                + "\"effectiveSettings\":true,\"nextToken\":\"" + page.jsonPath().getString("nextToken")
                + "\"}", 200)
                .then().body("settings", hasSize(1)).body("nextToken", nullValue());
    }

    @Test
    void attributesAreScopedToTheirClusterAndNeedARegisteredTarget() {
        String cluster = "edge-attr-cluster";
        String other = "edge-attr-other";
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\"}", 200);
        call("CreateCluster", "{\"clusterName\":\"" + other + "\"}", 200);

        String instance = call("RegisterContainerInstance", "{\"cluster\":\"" + cluster + "\"}", 200)
                .jsonPath().getString("containerInstance.containerInstanceArn");

        // An attribute on a target the cluster does not have is a TargetNotFoundException.
        call("PutAttributes", "{\"cluster\":\"" + cluster + "\",\"attributes\":"
                + "[{\"name\":\"stack\",\"value\":\"prod\",\"targetType\":\"container-instance\","
                + "\"targetId\":\"nope\"}]}", 400)
                .then().body("__type", containsString("TargetNotFoundException"));

        call("PutAttributes", "{\"cluster\":\"" + cluster + "\",\"attributes\":"
                + "[{\"name\":\"stack\",\"value\":\"prod\",\"targetType\":\"container-instance\","
                + "\"targetId\":\"" + instance + "\"}]}", 200)
                .then().body("attributes", hasSize(1));

        // targetType is required, and container-instance is its only value.
        call("ListAttributes", "{\"cluster\":\"" + cluster + "\"}", 400)
                .then().body("message", containsString("targetType"));
        call("ListAttributes", "{\"cluster\":\"" + cluster + "\",\"targetType\":\"task\"}", 400);

        call("ListAttributes", "{\"cluster\":\"" + cluster + "\","
                + "\"targetType\":\"container-instance\"}", 200)
                .then().body("attributes.find { it.name == 'stack' }.value", equalTo("prod"));

        // The other cluster never sees it.
        call("ListAttributes", "{\"cluster\":\"" + other + "\","
                + "\"targetType\":\"container-instance\"}", 200)
                .then().body("attributes.findAll { it.name == 'stack' }", hasSize(0));
    }

    @Test
    void aTargetTakesAtMostTenCustomAttributes() {
        String cluster = "edge-attr-limit";
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\"}", 200);
        String instance = call("RegisterContainerInstance", "{\"cluster\":\"" + cluster + "\"}", 200)
                .jsonPath().getString("containerInstance.containerInstanceArn");

        StringBuilder ten = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i > 0) { ten.append(','); }
            ten.append("{\"name\":\"a").append(i).append("\",\"value\":\"v\",")
                    .append("\"targetType\":\"container-instance\",\"targetId\":\"")
                    .append(instance).append("\"}");
        }
        call("PutAttributes", "{\"cluster\":\"" + cluster + "\",\"attributes\":[" + ten + "]}", 200);

        call("PutAttributes", "{\"cluster\":\"" + cluster + "\",\"attributes\":"
                + "[{\"name\":\"a10\",\"value\":\"v\",\"targetType\":\"container-instance\","
                + "\"targetId\":\"" + instance + "\"}]}", 400)
                .then().body("__type", containsString("AttributeLimitExceededException"));
    }

    // ── Container instances ───────────────────────────────────────────────────

    @Test
    void aRegisteredContainerInstanceReportsWhatTheAgentSent() {
        String cluster = "edge-ci-register";
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\"}", 200);

        call("RegisterContainerInstance", "{\"cluster\":\"" + cluster + "\","
                + "\"versionInfo\":{\"agentVersion\":\"1.79.0\",\"agentHash\":\"abc1234\","
                + "\"dockerVersion\":\"DockerVersion: 24.0.5\"},"
                + "\"totalResources\":[{\"name\":\"CPU\",\"type\":\"INTEGER\",\"integerValue\":4096},"
                + "{\"name\":\"MEMORY\",\"type\":\"INTEGER\",\"integerValue\":7482}],"
                + "\"tags\":[{\"key\":\"fleet\",\"value\":\"batch\"}]}", 200)
                .then()
                .body("containerInstance.status", equalTo("ACTIVE"))
                .body("containerInstance.registeredAt", notNullValue())
                .body("containerInstance.version", equalTo(1))
                // versionInfo is where the agent version lives; there is no top-level agentVersion.
                .body("containerInstance.agentVersion", nullValue())
                .body("containerInstance.versionInfo.agentVersion", equalTo("1.79.0"))
                .body("containerInstance.versionInfo.dockerVersion", equalTo("DockerVersion: 24.0.5"))
                .body("containerInstance.registeredResources", hasSize(2))
                .body("containerInstance.remainingResources", hasSize(2))
                .body("containerInstance.tags", hasSize(1));

        call("DescribeClusters", "{\"clusters\":[\"" + cluster + "\"]}", 200)
                .then().body("clusters[0].registeredContainerInstancesCount", equalTo(1));
    }

    @Test
    void aDeregisteredContainerInstanceStaysDescribableAsInactive() {
        String cluster = "edge-ci-deregister";
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\"}", 200);
        String instance = call("RegisterContainerInstance", "{\"cluster\":\"" + cluster + "\"}", 200)
                .jsonPath().getString("containerInstance.containerInstanceArn");

        call("DeregisterContainerInstance", "{\"cluster\":\"" + cluster + "\","
                + "\"containerInstance\":\"" + instance + "\"}", 200)
                .then().body("containerInstance.status", equalTo("INACTIVE"));

        // AWS keeps answering a describe for a deregistered instance.
        call("DescribeContainerInstances", "{\"cluster\":\"" + cluster + "\","
                + "\"containerInstances\":[\"" + instance + "\"]}", 200)
                .then()
                .body("containerInstances", hasSize(1))
                .body("containerInstances[0].status", equalTo("INACTIVE"));

        // The default listing excludes it, and only ACTIVE and DRAINING instances are registered.
        call("ListContainerInstances", "{\"cluster\":\"" + cluster + "\"}", 200)
                .then().body("containerInstanceArns", hasSize(0));
        call("DescribeClusters", "{\"clusters\":[\"" + cluster + "\"]}", 200)
                .then().body("clusters[0].registeredContainerInstancesCount", equalTo(0));
    }

    @Test
    void containerInstanceStateOnlyMovesBetweenActiveAndDraining() {
        String cluster = "edge-ci-state";
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\"}", 200);
        String instance = call("RegisterContainerInstance", "{\"cluster\":\"" + cluster + "\"}", 200)
                .jsonPath().getString("containerInstance.containerInstanceArn");

        call("UpdateContainerInstancesState", "{\"cluster\":\"" + cluster + "\","
                + "\"containerInstances\":[\"" + instance + "\"],\"status\":\"REGISTERING\"}", 400)
                .then().body("message", containsString("ACTIVE and DRAINING"));

        call("UpdateContainerInstancesState", "{\"cluster\":\"" + cluster + "\","
                + "\"containerInstances\":[\"" + instance + "\"],\"status\":\"DRAINING\"}", 200)
                .then().body("containerInstances[0].status", equalTo("DRAINING"));

        // A DRAINING instance is not ACTIVE, so it cannot be set to DRAINING again.
        call("UpdateContainerInstancesState", "{\"cluster\":\"" + cluster + "\","
                + "\"containerInstances\":[\"" + instance + "\"],\"status\":\"DRAINING\"}", 400)
                .then().body("message", containsString("must be ACTIVE"));

        // A DRAINING instance still counts as registered and still lists by default.
        call("ListContainerInstances", "{\"cluster\":\"" + cluster + "\"}", 200)
                .then().body("containerInstanceArns", hasSize(1));
        call("ListContainerInstances", "{\"cluster\":\"" + cluster + "\",\"status\":\"ACTIVE\"}", 200)
                .then().body("containerInstanceArns", hasSize(0));
        call("ListContainerInstances", "{\"cluster\":\"" + cluster + "\",\"status\":\"nonsense\"}", 400);

        call("UpdateContainerInstancesState", "{\"cluster\":\"" + cluster + "\","
                + "\"containerInstances\":[\"" + instance + "\"],\"status\":\"ACTIVE\"}", 200)
                .then().body("containerInstances[0].status", equalTo("ACTIVE"));
    }

    @Test
    void anUnknownContainerInstanceIsAFailureNotAnError() {
        String cluster = "edge-ci-missing";
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\"}", 200);

        call("DescribeContainerInstances", "{\"cluster\":\"" + cluster + "\","
                + "\"containerInstances\":[\"no-such-instance\"]}", 200)
                .then()
                .body("containerInstances", hasSize(0))
                .body("failures", hasSize(1))
                .body("failures[0].reason", equalTo("MISSING"));

        call("UpdateContainerInstancesState", "{\"cluster\":\"" + cluster + "\","
                + "\"containerInstances\":[\"no-such-instance\"],\"status\":\"DRAINING\"}", 200)
                .then()
                .body("containerInstances", hasSize(0))
                .body("failures[0].reason", equalTo("MISSING"));
    }

    // ── Derived compatibilities and requiresAttributes ────────────────────────

    @Test
    void compatibilitiesAreDerivedFromTheDefinitionNotJustEchoed() {
        // Fargate-valid but never asked for FARGATE: ECS still reports the launch types it works on.
        call("RegisterTaskDefinition", "{\"family\":\"edge-compat-implicit\",\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\",\"containerDefinitions\":"
                + "[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}", 200)
                .then().body("taskDefinition.compatibilities", equalTo(List.of("EC2", "FARGATE")));

        // awsvpc rules out EXTERNAL; bridge rules out FARGATE and allows EXTERNAL.
        call("RegisterTaskDefinition", "{\"family\":\"edge-compat-bridge\",\"networkMode\":\"bridge\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\",\"memory\":128}]}", 200)
                .then().body("taskDefinition.compatibilities", equalTo(List.of("EC2", "EXTERNAL")));

        // awsvpc without a Fargate size pair is EC2 only.
        call("RegisterTaskDefinition", "{\"family\":\"edge-compat-nosize\",\"networkMode\":\"awsvpc\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\",\"memory\":128}]}", 200)
                .then().body("taskDefinition.compatibilities", equalTo(List.of("EC2")));
    }

    @Test
    void requiresAttributesNamesTheCapabilitiesTheDefinitionUses() {
        call("RegisterTaskDefinition", "{\"family\":\"edge-attrs\",\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\","
                + "\"taskRoleArn\":\"arn:aws:iam::000000000000:role/task\","
                + "\"containerDefinitions\":[{\"name\":\"app\","
                + "\"image\":\"000000000000.dkr.ecr.us-east-1.amazonaws.com/app:1\","
                + "\"logConfiguration\":{\"logDriver\":\"awslogs\",\"options\":{}}}]}", 200)
                .then()
                .body("taskDefinition.requiresAttributes.name", hasItems(
                        "com.amazonaws.ecs.capability.docker-remote-api.1.18",
                        "ecs.capability.task-eni",
                        "com.amazonaws.ecs.capability.task-iam-role",
                        "com.amazonaws.ecs.capability.ecr-auth",
                        "com.amazonaws.ecs.capability.logging-driver.awslogs"));

        // A task role under host networking has its own capability name.
        call("RegisterTaskDefinition", "{\"family\":\"edge-attrs-host\",\"networkMode\":\"host\","
                + "\"taskRoleArn\":\"arn:aws:iam::000000000000:role/task\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\",\"memory\":128}]}", 200)
                .then()
                .body("taskDefinition.requiresAttributes.name", hasItems(
                        "com.amazonaws.ecs.capability.task-iam-role-network-host"))
                .body("taskDefinition.requiresAttributes.name", not(hasItems("ecs.capability.task-eni")));
    }

    @Test
    void startTaskRequiresContainerInstances() {
        String family = seed("edge-start-task");

        call("StartTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family + "\"}", 400)
                .then().body("message", containsString("containerInstances"));
    }

    // ── The Service shape's own members ──────────────────────────────────────

    @Test
    void aServiceReportsItsEventsAndTaskSetsAndWhatItsDeploymentRunsOn() {
        String family = seed("edge-svc-shape-td");
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"edge-svc-shape\","
                + "\"taskDefinition\":\"" + family + "\",\"desiredCount\":0,"
                + "\"launchType\":\"FARGATE\"," + NETWORK + "}", 200);

        call("DescribeServices", "{\"cluster\":\"" + CLUSTER
                + "\",\"services\":[\"edge-svc-shape\"]}", 200)
                .then()
                // Both members are always present, so a client can tell an empty answer from one
                // the emulator never wrote.
                .body("services[0].taskSets", hasSize(0))
                .body("services[0].events", hasSize(1))
                .body("services[0].events[0].message",
                        containsString("(service edge-svc-shape) has reached a steady state."))
                .body("services[0].events[0].id", notNullValue())
                .body("services[0].events[0].createdAt", notNullValue())
                // The deployment carries the placement the service runs under, not just its id.
                .body("services[0].deployments[0].launchType", equalTo("FARGATE"))
                .body("services[0].deployments[0].platformVersion", equalTo("1.4.0"))
                .body("services[0].deployments[0].platformFamily", equalTo("Linux"))
                .body("services[0].deployments[0].networkConfiguration.awsvpcConfiguration.subnets",
                        hasSize(1));
    }

    @Test
    void aServicesTaskSetsAreVisibleOnTheServiceItself() {
        String family = seedExternalService("edge-svc-tasksets");
        call("CreateTaskSet", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"edge-svc-tasksets\","
                + "\"taskDefinition\":\"" + family + "\",\"launchType\":\"FARGATE\"}", 200);

        call("DescribeServices", "{\"cluster\":\"" + CLUSTER
                + "\",\"services\":[\"edge-svc-tasksets\"]}", 200)
                .then()
                .body("services[0].taskSets", hasSize(1))
                .body("services[0].taskSets[0].status", equalTo("ACTIVE"));
    }

    // ── Cluster deletion ─────────────────────────────────────────────────────

    @Test
    void aClusterWithRegisteredContainerInstancesCannotBeDeleted() {
        String cluster = "edge-delete-with-instances";
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\"}", 200);
        String instance = call("RegisterContainerInstance", "{\"cluster\":\"" + cluster + "\"}", 200)
                .jsonPath().getString("containerInstance.containerInstanceArn");

        call("DeleteCluster", "{\"cluster\":\"" + cluster + "\"}", 400)
                .then().body("__type", containsString("ClusterContainsContainerInstancesException"));

        call("DeregisterContainerInstance", "{\"cluster\":\"" + cluster + "\","
                + "\"containerInstance\":\"" + instance + "\"}", 200);
        call("DeleteCluster", "{\"cluster\":\"" + cluster + "\"}", 200)
                .then().body("cluster.status", equalTo("INACTIVE"));
    }

    // ── Capacity providers ───────────────────────────────────────────────────

    @Test
    void capacityProviderNamesCannotUseTheReservedPrefixes() {
        for (String reserved : List.of("aws-edge-cp", "ecs-edge-cp", "fargate-edge-cp")) {
            call("CreateCapacityProvider", "{\"name\":\"" + reserved + "\","
                    + "\"autoScalingGroupProvider\":{\"autoScalingGroupArn\":\"arn:aws:autoscaling:"
                    + "us-east-1:000000000000:autoScalingGroup:x:autoScalingGroupName/asg-x\"}}", 400)
                    .then().body("message", containsString("prefixed"));
        }
        call("CreateCapacityProvider", "{\"name\":\"edge cp with spaces\","
                + "\"autoScalingGroupProvider\":{\"autoScalingGroupArn\":\"arn:aws:autoscaling:"
                + "us-east-1:000000000000:autoScalingGroup:x:autoScalingGroupName/asg-x\"}}", 400);
    }

    @Test
    void aCapacityProviderRoundTripsItsGroupAndGatesItsTags() {
        String name = "edge-cp-roundtrip";
        call("CreateCapacityProvider", "{\"name\":\"" + name + "\","
                + "\"autoScalingGroupProvider\":{\"autoScalingGroupArn\":\"arn:aws:autoscaling:"
                + "us-east-1:000000000000:autoScalingGroup:x:autoScalingGroupName/asg-x\","
                + "\"managedScaling\":{\"status\":\"ENABLED\",\"targetCapacity\":75}},"
                + "\"tags\":[{\"key\":\"owner\",\"value\":\"platform\"}]}", 200)
                .then()
                .body("capacityProvider.type", equalTo("EC2_AUTOSCALING"))
                .body("capacityProvider.capacityProviderArn", containsString("capacity-provider/" + name));

        call("DescribeCapacityProviders", "{\"capacityProviders\":[\"" + name + "\"]}", 200)
                .then()
                .body("capacityProviders[0].autoScalingGroupProvider.managedScaling.targetCapacity",
                        equalTo(75))
                // Tags are held back until the request asks for them.
                .body("capacityProviders[0].tags", nullValue());

        call("DescribeCapacityProviders", "{\"capacityProviders\":[\"" + name
                + "\"],\"include\":[\"TAGS\"]}", 200)
                .then().body("capacityProviders[0].tags", hasSize(1));
    }

    @Test
    void anUnknownCapacityProviderIsAFailureAndTheFargateOnesCannotBeDeleted() {
        call("DescribeCapacityProviders", "{\"capacityProviders\":[\"edge-cp-nope\"]}", 200)
                .then()
                .body("capacityProviders", hasSize(0))
                .body("failures", hasSize(1))
                .body("failures[0].reason", equalTo("MISSING"))
                .body("failures[0].arn", containsString("capacity-provider/edge-cp-nope"));

        call("DescribeCapacityProviders", "{\"capacityProviders\":[\"FARGATE\"]}", 200)
                .then()
                .body("capacityProviders[0].type", equalTo("FARGATE"))
                .body("capacityProviders[0].capacityProviderArn",
                        containsString("capacity-provider/FARGATE"));

        call("DeleteCapacityProvider", "{\"capacityProvider\":\"FARGATE\"}", 400)
                .then().body("message", containsString("reserved"));
    }

    @Test
    void aCapacityProviderAttachedToAClusterCannotBeDeleted() {
        String cluster = "edge-cp-attached-cluster";
        String name = "edge-cp-attached";
        call("CreateCapacityProvider", "{\"name\":\"" + name + "\","
                + "\"autoScalingGroupProvider\":{\"autoScalingGroupArn\":\"arn:aws:autoscaling:"
                + "us-east-1:000000000000:autoScalingGroup:x:autoScalingGroupName/asg-x\"}}", 200);
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\",\"capacityProviders\":[\""
                + name + "\"]}", 200);

        call("DeleteCapacityProvider", "{\"capacityProvider\":\"" + name + "\"}", 400)
                .then().body("message", containsString(cluster));

        call("PutClusterCapacityProviders", "{\"cluster\":\"" + cluster + "\","
                + "\"capacityProviders\":[],\"defaultCapacityProviderStrategy\":[]}", 200);
        call("DeleteCapacityProvider", "{\"capacityProvider\":\"" + name + "\"}", 200)
                .then().body("capacityProvider.updateStatus", equalTo("DELETE_IN_PROGRESS"))
                // DELETE_IN_PROGRESS is an updateStatus, never one of the four status values.
                .body("capacityProvider.status", equalTo("ACTIVE"));
    }

    @Test
    void aCapacityProviderInAServiceStrategyCannotBeDeleted() {
        String family = seed("edge-cp-in-service");
        String name = "edge-cp-in-strategy";
        call("CreateCapacityProvider", "{\"name\":\"" + name + "\","
                + "\"autoScalingGroupProvider\":{\"autoScalingGroupArn\":\"arn:aws:autoscaling:"
                + "us-east-1:000000000000:autoScalingGroup:y:autoScalingGroupName/asg-y\"}}", 200);
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"edge-cp-svc\","
                + "\"taskDefinition\":\"" + family + "\",\"desiredCount\":0," + NETWORK + ","
                + "\"capacityProviderStrategy\":[{\"capacityProvider\":\"" + name
                + "\",\"weight\":1}]}", 200);

        // The provider is in no cluster, so only the service's strategy holds the delete back.
        call("DeleteCapacityProvider", "{\"capacityProvider\":\"" + name + "\"}", 400)
                .then().body("message", containsString("edge-cp-svc"));

        call("DeleteService", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"edge-cp-svc\"}", 200);
        call("DeleteCapacityProvider", "{\"capacityProvider\":\"" + name + "\"}", 200)
                .then().body("capacityProvider.updateStatus", equalTo("DELETE_IN_PROGRESS"));
    }

    // ── Round trips the parser used to drop ──────────────────────────────────

    @Test
    void aPortMappingRoundTripsItsServiceConnectMembers() {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);
        call("RegisterTaskDefinition", "{\"family\":\"edge-portmapping\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"256\",\"memory\":\"512\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\","
                + "\"portMappings\":[{\"containerPort\":8080,\"protocol\":\"tcp\","
                + "\"name\":\"api\",\"appProtocol\":\"http\"}]}]}", 200);

        // name and appProtocol are what a service's serviceConnectConfiguration references, so
        // losing them on the round trip breaks Service Connect.
        call("DescribeTaskDefinition", "{\"taskDefinition\":\"edge-portmapping\"}", 200)
                .then()
                .body("taskDefinition.containerDefinitions[0].portMappings[0].name", equalTo("api"))
                .body("taskDefinition.containerDefinitions[0].portMappings[0].appProtocol",
                        equalTo("http"))
                .body("taskDefinition.containerDefinitions[0].portMappings[0].containerPort",
                        equalTo(8080));
    }

    @Test
    void aVolumeRoundTripsTheConfigurationsFlociDoesNotBack() {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}", 200);
        call("RegisterTaskDefinition", "{\"family\":\"edge-volume-roundtrip\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}],"
                + "\"volumes\":[{\"name\":\"docker-vol\",\"configuredAtLaunch\":false,"
                + "\"dockerVolumeConfiguration\":{\"scope\":\"shared\",\"autoprovision\":true,"
                + "\"driver\":\"local\"}}]}", 200);

        call("DescribeTaskDefinition", "{\"taskDefinition\":\"edge-volume-roundtrip\"}", 200)
                .then()
                .body("taskDefinition.volumes[0].name", equalTo("docker-vol"))
                .body("taskDefinition.volumes[0].dockerVolumeConfiguration.scope", equalTo("shared"))
                .body("taskDefinition.volumes[0].dockerVolumeConfiguration.driver", equalTo("local"))
                .body("taskDefinition.volumes[0].configuredAtLaunch", equalTo(false));
    }

    // ── Request validation ───────────────────────────────────────────────────

    @Test
    void anEnumValueTheApiDoesNotHaveIsRejectedRatherThanIgnored() {
        String family = seed("edge-bad-enum");

        // Silently treating this as absent would place the task on Floci's default launch type,
        // which is not what the caller asked for.
        call("RunTask", "{\"cluster\":\"" + CLUSTER + "\",\"taskDefinition\":\"" + family
                + "\",\"launchType\":\"EC22\"," + NETWORK + "}", 400)
                .then().body("message", containsString("launchType"));

        call("RegisterTaskDefinition", "{\"family\":\"edge-bad-network-mode\","
                + "\"networkMode\":\"AWSVPC\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}", 400)
                .then().body("message", containsString("networkMode"));

        call("ListContainerInstances", "{\"cluster\":\"" + CLUSTER + "\",\"status\":\"BOGUS\"}", 400)
                .then().body("message", containsString("status"));
    }

    @Test
    void aResourceTakesAtMostFiftyTags() {
        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            tags.append(i > 0 ? "," : "")
                    .append("{\"key\":\"k").append(i).append("\",\"value\":\"v\"}");
        }
        call("CreateCluster", "{\"clusterName\":\"edge-too-many-tags\",\"tags\":[" + tags + "]}", 400)
                .then().body("message", containsString("50"));

        call("CreateCluster", "{\"clusterName\":\"edge-empty-tag-key\","
                + "\"tags\":[{\"key\":\"\",\"value\":\"v\"}]}", 400)
                .then().body("message", containsString("Tag keys"));
    }

    // ── Container instance health ────────────────────────────────────────────

    @Test
    void containerInstanceHealthIsWithheldUntilTheRequestAsksForIt() {
        String cluster = "edge-ci-health";
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\"}", 200);
        String instance = call("RegisterContainerInstance", "{\"cluster\":\"" + cluster + "\"}", 200)
                .jsonPath().getString("containerInstance.containerInstanceArn");
        String describe = "{\"cluster\":\"" + cluster + "\",\"containerInstances\":[\""
                + instance + "\"]";

        call("DescribeContainerInstances", describe + "}", 200)
                .then().body("containerInstances[0].healthStatus", nullValue());

        call("DescribeContainerInstances", describe + ",\"include\":[\"CONTAINER_INSTANCE_HEALTH\"]}", 200)
                .then()
                .body("containerInstances[0].healthStatus.overallStatus", equalTo("OK"))
                .body("containerInstances[0].healthStatus.details[0].type",
                        equalTo("AGENT_CONNECTIVITY"));

        call("DescribeContainerInstances", describe + ",\"include\":[\"NOPE\"]}", 400)
                .then().body("message", containsString("include"));
    }

    // ── Service deployments and revisions ────────────────────────────────────

    @Test
    void aServiceDeploymentPointsAtTheRevisionItDeployed() {
        String family = seed("edge-deployment-td");
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"edge-deployment-svc\","
                + "\"taskDefinition\":\"" + family + "\",\"desiredCount\":0,"
                + "\"launchType\":\"FARGATE\"," + NETWORK + "}", 200);

        Response listed = call("ListServiceDeployments", "{\"cluster\":\"" + CLUSTER
                + "\",\"service\":\"edge-deployment-svc\"}", 200);
        listed.then()
                .body("serviceDeployments", hasSize(1))
                .body("serviceDeployments[0].targetServiceRevisionArn",
                        containsString("service-revision/"))
                .body("serviceDeployments[0].startedAt", notNullValue())
                .body("serviceDeployments[0].finishedAt", notNullValue());

        String deploymentArn = listed.jsonPath().getString("serviceDeployments[0].serviceDeploymentArn");
        String revisionArn = listed.jsonPath().getString("serviceDeployments[0].targetServiceRevisionArn");

        call("DescribeServiceDeployments", "{\"serviceDeploymentArns\":[\"" + deploymentArn + "\"]}", 200)
                .then()
                .body("serviceDeployments[0].status", equalTo("SUCCESSFUL"))
                .body("serviceDeployments[0].targetServiceRevision.arn", equalTo(revisionArn))
                .body("serviceDeployments[0].targetServiceRevision.requestedTaskCount", equalTo(0))
                // AWS's ServiceDeployment shape has no taskDefinition; the revision carries it.
                .body("serviceDeployments[0].taskDefinition", nullValue());

        // The service points back at both, so a caller never has to list first.
        call("DescribeServices", "{\"cluster\":\"" + CLUSTER
                + "\",\"services\":[\"edge-deployment-svc\"]}", 200)
                .then()
                .body("services[0].currentServiceDeployment", equalTo(deploymentArn))
                .body("services[0].currentServiceRevisions[0].arn", equalTo(revisionArn));
    }

    @Test
    void aServiceRevisionSnapshotsTheConfigurationItWasCreatedWith() {
        String family = seed("edge-revision-td");
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"edge-revision-svc\","
                + "\"taskDefinition\":\"" + family + "\",\"desiredCount\":0,"
                + "\"launchType\":\"FARGATE\"," + NETWORK + "}", 200);

        String revisionArn = call("ListServiceDeployments", "{\"cluster\":\"" + CLUSTER
                + "\",\"service\":\"edge-revision-svc\"}", 200)
                .jsonPath().getString("serviceDeployments[0].targetServiceRevisionArn");

        call("DescribeServiceRevisions", "{\"serviceRevisionArns\":[\"" + revisionArn + "\"]}", 200)
                .then()
                .body("serviceRevisions[0].taskDefinition", containsString(family))
                .body("serviceRevisions[0].launchType", equalTo("FARGATE"))
                .body("serviceRevisions[0].platformVersion", equalTo("1.4.0"))
                .body("serviceRevisions[0].platformFamily", equalTo("Linux"))
                .body("serviceRevisions[0].networkConfiguration.awsvpcConfiguration.subnets",
                        hasSize(1))
                .body("serviceRevisions[0].containerImages[0].containerName", equalTo("app"))
                .body("serviceRevisions[0].containerImages[0].image", equalTo("nginx:latest"))
                .body("serviceRevisions[0].guardDutyEnabled", equalTo(false));

        call("DescribeServiceRevisions", "{\"serviceRevisionArns\":[\"arn:aws:ecs:us-east-1:"
                + "000000000000:service-revision/nope\"]}", 200)
                .then()
                .body("serviceRevisions", hasSize(0))
                .body("failures[0].reason", equalTo("MISSING"));
    }
}
