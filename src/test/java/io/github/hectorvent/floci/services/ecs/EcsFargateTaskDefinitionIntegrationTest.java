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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RegisterTaskDefinition for the Fargate launch type: the members a Fargate task definition
 * carries must survive a round trip, and the ones Fargate has no equivalent for must be rejected
 * at registration rather than at launch.
 */
@QuarkusTest
class EcsFargateTaskDefinitionIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";

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

    private static Response register(String body, int expectedStatus) {
        return call("RegisterTaskDefinition", body, expectedStatus);
    }

    /** A Fargate-compatible definition with everything filled in but the named members replaced. */
    private static String fargateTaskDefinition(String family, String extraMembers) {
        return "{\"family\":\"" + family + "\",\"requiresCompatibilities\":[\"FARGATE\"],"
                + "\"networkMode\":\"awsvpc\",\"cpu\":\"256\",\"memory\":\"512\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]"
                + extraMembers + "}";
    }

    @Test
    void registerTaskDefinitionRoundTripsEphemeralStorageAndPidMode() {
        register(fargateTaskDefinition("fargate-ephemeral",
                ",\"ephemeralStorage\":{\"sizeInGiB\":50},\"pidMode\":\"task\""), 200);

        call("DescribeTaskDefinition", "{\"taskDefinition\":\"fargate-ephemeral\"}", 200)
                .then()
                .body("taskDefinition.ephemeralStorage.sizeInGiB", equalTo(50))
                .body("taskDefinition.pidMode", equalTo("task"));
    }

    @Test
    void registerTaskDefinitionRoundTripsMembersFlociDoesNotActOn() {
        // proxyConfiguration and the container's ulimits, linuxParameters and resourceRequirements
        // have no local behaviour, but a client that wrote them reads them back or sees drift.
        register("{\"family\":\"fargate-passthrough\",\"requiresCompatibilities\":[\"FARGATE\"],"
                + "\"networkMode\":\"awsvpc\",\"cpu\":\"512\",\"memory\":\"1024\","
                + "\"proxyConfiguration\":{\"type\":\"APPMESH\",\"containerName\":\"envoy\","
                + "\"properties\":[{\"name\":\"AppPorts\",\"value\":\"8080\"}]},"
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\","
                + "\"ulimits\":[{\"name\":\"nofile\",\"softLimit\":1024,\"hardLimit\":4096}],"
                + "\"linuxParameters\":{\"initProcessEnabled\":true},"
                + "\"systemControls\":[{\"namespace\":\"net.core.somaxconn\",\"value\":\"1024\"}]}]}", 200);

        call("DescribeTaskDefinition", "{\"taskDefinition\":\"fargate-passthrough\"}", 200)
                .then()
                .body("taskDefinition.proxyConfiguration.type", equalTo("APPMESH"))
                .body("taskDefinition.proxyConfiguration.properties[0].name", equalTo("AppPorts"))
                .body("taskDefinition.containerDefinitions[0].ulimits[0].name", equalTo("nofile"))
                .body("taskDefinition.containerDefinitions[0].ulimits[0].softLimit", equalTo(1024))
                .body("taskDefinition.containerDefinitions[0].linuxParameters.initProcessEnabled",
                        equalTo(true))
                .body("taskDefinition.containerDefinitions[0].systemControls[0].namespace",
                        equalTo("net.core.somaxconn"));
    }

    @Test
    void registerTaskDefinitionRoundTripsTypedContainerMembers() {
        register("{\"family\":\"fargate-container-members\",\"requiresCompatibilities\":[\"FARGATE\"],"
                + "\"networkMode\":\"awsvpc\",\"cpu\":\"256\",\"memory\":\"512\","
                + "\"containerDefinitions\":["
                + "{\"name\":\"init\",\"image\":\"busybox\",\"essential\":false},"
                + "{\"name\":\"app\",\"image\":\"nginx:latest\",\"user\":\"1000:1000\","
                + "\"workingDirectory\":\"/srv\",\"readonlyRootFilesystem\":true,\"stopTimeout\":30,"
                + "\"startTimeout\":120,\"dockerLabels\":{\"team\":\"platform\"},"
                + "\"environmentFiles\":[{\"value\":\"arn:aws:s3:::cfg/app.env\",\"type\":\"s3\"}],"
                + "\"repositoryCredentials\":{\"credentialsParameter\":\"arn:aws:secretsmanager:us-east-1:"
                + "000000000000:secret:registry\"},"
                + "\"dependsOn\":[{\"containerName\":\"init\",\"condition\":\"SUCCESS\"}]}]}", 200);

        call("DescribeTaskDefinition", "{\"taskDefinition\":\"fargate-container-members\"}", 200)
                .then()
                .body("taskDefinition.containerDefinitions[1].user", equalTo("1000:1000"))
                .body("taskDefinition.containerDefinitions[1].workingDirectory", equalTo("/srv"))
                .body("taskDefinition.containerDefinitions[1].readonlyRootFilesystem", equalTo(true))
                .body("taskDefinition.containerDefinitions[1].stopTimeout", equalTo(30))
                .body("taskDefinition.containerDefinitions[1].startTimeout", equalTo(120))
                .body("taskDefinition.containerDefinitions[1].dockerLabels.team", equalTo("platform"))
                .body("taskDefinition.containerDefinitions[1].environmentFiles", hasSize(1))
                .body("taskDefinition.containerDefinitions[1].environmentFiles[0].type", equalTo("s3"))
                .body("taskDefinition.containerDefinitions[1].repositoryCredentials.credentialsParameter",
                        containsString("secret:registry"))
                .body("taskDefinition.containerDefinitions[1].dependsOn[0].containerName", equalTo("init"))
                .body("taskDefinition.containerDefinitions[1].dependsOn[0].condition", equalTo("SUCCESS"));
    }

    @Test
    void registerTaskDefinitionReportsRegistrationMetadata() {
        Response response = register(fargateTaskDefinition("fargate-registered-at", ""), 200);
        assertTrue(response.jsonPath().getDouble("taskDefinition.registeredAt") > 0,
                "registeredAt must be reported as an epoch timestamp");
        assertTrue(response.jsonPath().getString("taskDefinition.registeredBy").startsWith("arn:"),
                "registeredBy must be an ARN");
    }

    @Test
    void registerTaskDefinitionRejectsEphemeralStorageOutsideTheFargateRange() {
        register(fargateTaskDefinition("fargate-storage-small", ",\"ephemeralStorage\":{\"sizeInGiB\":10}"), 400)
                .then().body("message", containsString("ephemeral storage"));
        register(fargateTaskDefinition("fargate-storage-large", ",\"ephemeralStorage\":{\"sizeInGiB\":300}"), 400)
                .then().body("message", containsString("ephemeral storage"));
    }

    @Test
    void registerTaskDefinitionRejectsParametersFargateDoesNotSupport() {
        String prefix = "{\"family\":\"fargate-unsupported\",\"requiresCompatibilities\":[\"FARGATE\"],"
                + "\"networkMode\":\"awsvpc\",\"cpu\":\"256\",\"memory\":\"512\",\"containerDefinitions\":"
                + "[{\"name\":\"app\",\"image\":\"nginx:latest\",";

        register(prefix + "\"privileged\":true}]}", 400)
                .then().body("message", containsString("privileged"));
        register(prefix + "\"links\":[\"other\"]}]}", 400)
                .then().body("message", containsString("links"));
        register(prefix + "\"disableNetworking\":true}]}", 400)
                .then().body("message", containsString("disableNetworking"));
        register(prefix + "\"dnsServers\":[\"10.0.0.2\"]}]}", 400)
                .then().body("message", containsString("dnsServers"));
        register(fargateTaskDefinition("fargate-ipc", ",\"ipcMode\":\"host\""), 400)
                .then().body("message", containsString("ipcMode"));
        register(fargateTaskDefinition("fargate-host-volume",
                ",\"volumes\":[{\"name\":\"data\",\"host\":{\"sourcePath\":\"/srv/data\"}}]"), 400);
    }

    @Test
    void registerTaskDefinitionAllowsThoseParametersOnEc2() {
        // The same members are legal on an EC2-compatible definition, so the rejection above must
        // be scoped to Fargate rather than applied to every task definition.
        register("{\"family\":\"ec2-unsupported-on-fargate\",\"networkMode\":\"bridge\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\","
                + "\"privileged\":true,\"dnsServers\":[\"10.0.0.2\"],"
                + "\"resourceRequirements\":[{\"type\":\"GPU\",\"value\":\"1\"}]}]}", 200)
                .then()
                .body("taskDefinition.containerDefinitions[0].privileged", equalTo(true))
                .body("taskDefinition.containerDefinitions[0].dnsServers[0]", equalTo("10.0.0.2"))
                .body("taskDefinition.containerDefinitions[0].resourceRequirements[0].type",
                        equalTo("GPU"));
    }

    @Test
    void registerTaskDefinitionRejectsADependencyOnAContainerThatIsNotThere() {
        register("{\"family\":\"fargate-bad-dependency\",\"requiresCompatibilities\":[\"FARGATE\"],"
                + "\"networkMode\":\"awsvpc\",\"cpu\":\"256\",\"memory\":\"512\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\","
                + "\"dependsOn\":[{\"containerName\":\"sidecar\",\"condition\":\"START\"}]}]}", 400)
                .then().body("message", containsString("sidecar"));
    }

    @Test
    void registerTaskDefinitionRejectsAnUnknownDependencyCondition() {
        register("{\"family\":\"fargate-bad-condition\",\"requiresCompatibilities\":[\"FARGATE\"],"
                + "\"networkMode\":\"awsvpc\",\"cpu\":\"256\",\"memory\":\"512\","
                + "\"containerDefinitions\":[{\"name\":\"init\",\"image\":\"busybox\"},"
                + "{\"name\":\"app\",\"image\":\"nginx:latest\","
                + "\"dependsOn\":[{\"containerName\":\"init\",\"condition\":\"READY\"}]}]}", 400)
                .then().body("message", containsString("READY"));
    }

    @Test
    void registerTaskDefinitionAcceptsTheLargestFargateTaskSize() {
        register("{\"family\":\"fargate-16vcpu\",\"requiresCompatibilities\":[\"FARGATE\"],"
                + "\"networkMode\":\"awsvpc\",\"cpu\":\"16384\",\"memory\":\"122880\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}", 200);
    }

    @Test
    void registerTaskDefinitionRejectsAMemorySizeOffTheFargateStep() {
        // 8 vCPU goes from 16 GB up in 4 GB steps, so 18 GB is not a configuration that exists.
        Response response = register("{\"family\":\"fargate-8vcpu-bad\",\"requiresCompatibilities\":[\"FARGATE\"],"
                + "\"networkMode\":\"awsvpc\",\"cpu\":\"8192\",\"memory\":\"18432\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}", 400);
        assertEquals("No Fargate configuration exists for given values.",
                response.jsonPath().getString("message"));
    }

    @Test
    void registerTaskDefinitionRejectsSubVcpuSizesForWindowsOnFargate() {
        register("{\"family\":\"fargate-windows\",\"requiresCompatibilities\":[\"FARGATE\"],"
                + "\"networkMode\":\"awsvpc\",\"cpu\":\"512\",\"memory\":\"1024\","
                + "\"runtimePlatform\":{\"operatingSystemFamily\":\"WINDOWS_SERVER_2019_CORE\","
                + "\"cpuArchitecture\":\"X86_64\"},"
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}", 400);

        register("{\"family\":\"fargate-windows-ok\",\"requiresCompatibilities\":[\"FARGATE\"],"
                + "\"networkMode\":\"awsvpc\",\"cpu\":\"1024\",\"memory\":\"2048\","
                + "\"runtimePlatform\":{\"operatingSystemFamily\":\"WINDOWS_SERVER_2019_CORE\","
                + "\"cpuArchitecture\":\"X86_64\"},"
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx:latest\"}]}", 200);
    }
}
