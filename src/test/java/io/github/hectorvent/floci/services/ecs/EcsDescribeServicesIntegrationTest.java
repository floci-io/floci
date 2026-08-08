package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the two {@code DescribeServices} response fields that AWS's
 * {@code ServicesStable} waiter depends on (floci-io/floci#2174).
 *
 * <p>The waiter accepts on
 * {@code length(services[?!(length(deployments) == `1` && runningCount == desiredCount)]) == `0`}
 * and fails fast on {@code failures[].reason == "MISSING"} (botocore
 * {@code ecs/2014-11-13/waiters-2.json}). So an ACTIVE service must report exactly one
 * deployment, and an unknown service must come back as a failure rather than being dropped;
 * getting either wrong leaves {@code aws ecs wait services-stable} and every SDK waiter
 * polling until they time out.
 */
@QuarkusTest
class EcsDescribeServicesIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", TARGET + action)
                .body(body)
                .when().post("/")
                .then().statusCode(200)
                .extract().response();
    }

    private static void seedClusterAndTaskDef(String cluster, String family) {
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\"}");
        registerTaskDef(family);
    }

    private static void registerTaskDef(String family) {
        call("RegisterTaskDefinition", "{\"family\":\"" + family + "\","
                + "\"containerDefinitions\":[{\"name\":\"web\",\"image\":\"nginx\",\"memory\":128}]}");
    }

    /**
     * Epoch seconds carry 10 significant digits, more than a float holds. RestAssured's default
     * number type is FLOAT_AND_DOUBLE, which silently rounds two timestamps a second apart to the
     * same value, so read them as BigDecimal.
     */
    private static double epochSeconds(Response response, String path) {
        return ((java.math.BigDecimal) response
                .jsonPath(new JsonPathConfig(JsonPathConfig.NumberReturnType.BIG_DECIMAL))
                .get(path)).doubleValue();
    }

    private static Response describe(String cluster, String service) {
        return call("DescribeServices",
                "{\"cluster\":\"" + cluster + "\",\"services\":[\"" + service + "\"]}");
    }

    @Test
    void describeServicesReportsExactlyOnePrimaryDeployment() {
        seedClusterAndTaskDef("dep-cluster-1", "dep-td-1");
        call("CreateService", "{\"cluster\":\"dep-cluster-1\",\"serviceName\":\"dep-svc-1\","
                + "\"taskDefinition\":\"dep-td-1\",\"desiredCount\":0}");

        Response r = describe("dep-cluster-1", "dep-svc-1");
        String taskDefinition = r.path("services[0].taskDefinition");
        int runningCount = r.path("services[0].runningCount");
        int pendingCount = r.path("services[0].pendingCount");

        r.then()
                .body("services", hasSize(1))
                .body("services[0].deployments", hasSize(1))
                .body("services[0].deployments[0].status", equalTo("PRIMARY"))
                .body("services[0].deployments[0].id", startsWith("ecs-svc/"))
                .body("services[0].deployments[0].taskDefinition", equalTo(taskDefinition))
                .body("services[0].deployments[0].desiredCount", equalTo(0))
                .body("services[0].deployments[0].runningCount", equalTo(runningCount))
                .body("services[0].deployments[0].pendingCount", equalTo(pendingCount))
                .body("services[0].deployments[0].failedTasks", equalTo(0))
                .body("services[0].deployments[0].rolloutStateReason",
                        startsWith("ECS deployment ecs-svc/"));
    }

    @Test
    void deploymentIdIsStableAcrossCalls() {
        // Deployments are synthesized per request. A random id would change on every call and
        // surface as perpetual drift in any client that persists it, so this is the regression
        // guard for deriving the id from the service ARN and task definition instead.
        seedClusterAndTaskDef("dep-cluster-2", "dep-td-2");
        call("CreateService", "{\"cluster\":\"dep-cluster-2\",\"serviceName\":\"dep-svc-2\","
                + "\"taskDefinition\":\"dep-td-2\",\"desiredCount\":0}");

        String first = describe("dep-cluster-2", "dep-svc-2").path("services[0].deployments[0].id");
        String second = describe("dep-cluster-2", "dep-svc-2").path("services[0].deployments[0].id");

        assertEquals(first, second, "deployment id must not change between DescribeServices calls");
    }

    @Test
    void convergedServiceReportsCompletedRollout() {
        // desiredCount 0 is trivially converged (running 0 >= desired 0), so this asserts the
        // COMPLETED branch without depending on Docker-backed tasks actually starting.
        seedClusterAndTaskDef("dep-cluster-3", "dep-td-3");
        call("CreateService", "{\"cluster\":\"dep-cluster-3\",\"serviceName\":\"dep-svc-3\","
                + "\"taskDefinition\":\"dep-td-3\",\"desiredCount\":0}");

        describe("dep-cluster-3", "dep-svc-3").then()
                .body("services[0].deployments[0].rolloutState", equalTo("COMPLETED"))
                .body("services[0].deployments[0].rolloutStateReason", endsWith("completed."));
    }

    @Test
    void deploymentIdRollsOverWhenTaskDefinitionChanges() {
        seedClusterAndTaskDef("dep-cluster-5", "dep-td-5a");
        call("CreateService", "{\"cluster\":\"dep-cluster-5\",\"serviceName\":\"dep-svc-5\","
                + "\"taskDefinition\":\"dep-td-5a\",\"desiredCount\":0}");
        String before = describe("dep-cluster-5", "dep-svc-5").path("services[0].deployments[0].id");

        registerTaskDef("dep-td-5b");
        call("UpdateService", "{\"cluster\":\"dep-cluster-5\",\"service\":\"dep-svc-5\","
                + "\"taskDefinition\":\"dep-td-5b\"}");

        String after = describe("dep-cluster-5", "dep-svc-5").path("services[0].deployments[0].id");
        assertNotEquals(before, after,
                "a new task definition is a new deployment and must get a new id");
    }

    @Test
    void newDeploymentCarriesItsOwnStartTimeNotTheServiceCreationTime() throws Exception {
        // A task-definition change mints a new deployment id, so the timestamps have to move with
        // it. Reporting the service's creation time would contradict the new id and misrepresent
        // when the deployment actually started.
        seedClusterAndTaskDef("dep-cluster-11", "dep-td-11a");
        call("CreateService", "{\"cluster\":\"dep-cluster-11\",\"serviceName\":\"dep-svc-11\","
                + "\"taskDefinition\":\"dep-td-11a\",\"desiredCount\":0}");

        Response before = describe("dep-cluster-11", "dep-svc-11");
        double createdAtBefore = epochSeconds(before, "services[0].deployments[0].createdAt");
        String idBefore = before.path("services[0].deployments[0].id");

        Thread.sleep(1100);
        registerTaskDef("dep-td-11b");
        call("UpdateService", "{\"cluster\":\"dep-cluster-11\",\"service\":\"dep-svc-11\","
                + "\"taskDefinition\":\"dep-td-11b\"}");

        Response after = describe("dep-cluster-11", "dep-svc-11");
        double createdAtAfter = epochSeconds(after, "services[0].deployments[0].createdAt");
        double serviceCreatedAt = epochSeconds(after, "services[0].createdAt");

        assertNotEquals(idBefore, after.path("services[0].deployments[0].id"));
        assertTrue(createdAtAfter > createdAtBefore,
                "a new deployment must carry a later createdAt than the one it replaced");
        assertTrue(createdAtAfter > serviceCreatedAt,
                "the deployment started after the service was created, so its createdAt must be later");
    }

    @Test
    void createAndUpdateServiceEchoTheSameDeploymentShape() {
        seedClusterAndTaskDef("dep-cluster-6", "dep-td-6");

        call("CreateService", "{\"cluster\":\"dep-cluster-6\",\"serviceName\":\"dep-svc-6\","
                + "\"taskDefinition\":\"dep-td-6\",\"desiredCount\":0}").then()
                .body("service.deployments", hasSize(1))
                .body("service.deployments[0].status", equalTo("PRIMARY"));

        call("UpdateService", "{\"cluster\":\"dep-cluster-6\",\"service\":\"dep-svc-6\","
                + "\"desiredCount\":0}").then()
                .body("service.deployments", hasSize(1))
                .body("service.deployments[0].status", equalTo("PRIMARY"));
    }

    @Test
    void unknownServiceIsReportedAsAMissingFailureRatherThanDroppedSilently() {
        // ECS answers a describe for an unknown service with a failure entry, not an error, and
        // the ServicesStable waiter fails fast on failures[].reason == "MISSING". Returning an
        // empty services list with no failure leaves that waiter polling for its full timeout.
        seedClusterAndTaskDef("dep-cluster-8", "dep-td-8");

        describe("dep-cluster-8", "no-such-service").then()
                .body("services", hasSize(0))
                .body("failures", hasSize(1))
                .body("failures[0].reason", equalTo("MISSING"))
                .body("failures[0].arn", endsWith(":service/dep-cluster-8/no-such-service"));
    }

    @Test
    void describeMixesFoundServicesWithMissingOnes() {
        seedClusterAndTaskDef("dep-cluster-9", "dep-td-9");
        call("CreateService", "{\"cluster\":\"dep-cluster-9\",\"serviceName\":\"dep-svc-9\","
                + "\"taskDefinition\":\"dep-td-9\",\"desiredCount\":0}");

        call("DescribeServices", "{\"cluster\":\"dep-cluster-9\","
                + "\"services\":[\"dep-svc-9\",\"ghost-service\"]}").then()
                .body("services", hasSize(1))
                .body("services[0].serviceName", equalTo("dep-svc-9"))
                .body("failures", hasSize(1))
                .body("failures[0].reason", equalTo("MISSING"))
                .body("failures[0].arn", endsWith(":service/dep-cluster-9/ghost-service"));
    }

    @Test
    void aServiceArnThatDoesNotExistIsEchoedBackInTheFailure() {
        seedClusterAndTaskDef("dep-cluster-10", "dep-td-10");
        String arn = "arn:aws:ecs:us-east-1:000000000000:service/dep-cluster-10/absent";

        call("DescribeServices", "{\"cluster\":\"dep-cluster-10\",\"services\":[\"" + arn + "\"]}").then()
                .body("failures", hasSize(1))
                .body("failures[0].arn", equalTo(arn))
                .body("failures[0].reason", equalTo("MISSING"));
    }

    @Test
    void deletedServiceReportsAnEmptyDeploymentListRatherThanOmittingIt() {
        // AWS's ServicesStable waiter fails an INACTIVE service on status, so an empty list is
        // correct here, but the key must still be present, matching how moto renders it.
        seedClusterAndTaskDef("dep-cluster-7", "dep-td-7");
        call("CreateService", "{\"cluster\":\"dep-cluster-7\",\"serviceName\":\"dep-svc-7\","
                + "\"taskDefinition\":\"dep-td-7\",\"desiredCount\":0}");
        call("DeleteService", "{\"cluster\":\"dep-cluster-7\",\"service\":\"dep-svc-7\"}");

        describe("dep-cluster-7", "dep-svc-7").then()
                .body("services[0].status", equalTo("INACTIVE"))
                .body("services[0].deployments", hasSize(0));
    }
}
