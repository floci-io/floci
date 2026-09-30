package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The join a steady-state wait walks, end to end.
 *
 * <p>Since provider 6.x the Terraform AWS provider no longer decides {@code wait_for_steady_state}
 * from {@code DescribeServices} alone. For a service under the ECS deployment controller whose
 * PRIMARY deployment started after the operation, it:
 *
 * <ol>
 *   <li>reads {@code services[0].deployments[0].id}, which is {@code ecs-svc/<taskSetId>};</li>
 *   <li>calls {@code ListServiceDeployments} and takes the brief whose
 *       {@code targetServiceRevisionArn} <em>contains that {@code taskSetId}</em>;</li>
 *   <li>polls {@code DescribeServiceDeployments} on that ARN until its status is
 *       {@code SUCCESSFUL}.</li>
 * </ol>
 *
 * <p>Step 2 is a join AWS makes work by naming the service revision with the very id the
 * deployment reports. Floci minted the two independently, so the join matched nothing, the
 * provider never reached step 3, and every {@code wait_for_steady_state} apply sat on
 * {@code tfPENDING} for its full twenty-minute timeout against a service that was running and
 * reporting itself stable. Six of the first sixteen {@code terraform-aws-ecs} Terratest suites
 * failed that way in the 2026-09-28 sweep.
 */
@QuarkusTest
class EcsServiceSteadyStateWaiterIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";
    private static final String CLUSTER = "waiter-cluster";
    private static final String NETWORK = "\"networkConfiguration\":{\"awsvpcConfiguration\":"
            + "{\"subnets\":[\"subnet-1\"],\"securityGroups\":[\"sg-1\"]}}";

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

    /**
     * As {@link #seedService}, but at a task count the service has not reached yet, so its
     * deployment is in flight. Whatever is done with it must not read the service back: see
     * {@link #aStoppedDeploymentReportsWhenItStoppedAndWhy}.
     */
    private static String seedUnconvergedService(String name) {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}");
        call("RegisterTaskDefinition", "{\"family\":\"" + name + "-td\",\"networkMode\":\"awsvpc\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx\",\"memory\":128}]}");
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"" + name + "\","
                + "\"taskDefinition\":\"" + name + "-td\",\"desiredCount\":1,"
                + "\"launchType\":\"FARGATE\"," + NETWORK + "}");
        return name;
    }

    /** Creates the cluster, a task definition and a service, and returns the service name. */
    private static String seedService(String name) {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}");
        call("RegisterTaskDefinition", "{\"family\":\"" + name + "-td\",\"networkMode\":\"awsvpc\","
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"nginx\",\"memory\":128}]}");
        // desiredCount 0 is already converged, so the deployment is SUCCESSFUL without waiting on
        // the reconciler: this test is about the join, not about convergence.
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"" + name + "\","
                + "\"taskDefinition\":\"" + name + "-td\",\"desiredCount\":0,"
                + "\"launchType\":\"FARGATE\"," + NETWORK + "}");
        return name;
    }

    /** The {@code <taskSetId>} half of the service's PRIMARY {@code ecs-svc/<taskSetId>}. */
    private static String primaryTaskSetId(String service) {
        String id = call("DescribeServices", "{\"cluster\":\"" + CLUSTER
                + "\",\"services\":[\"" + service + "\"]}")
                .jsonPath().getString("services[0].deployments[0].id");
        assertNotNull(id, "an ACTIVE service reports a PRIMARY deployment id");
        assertTrue(id.startsWith("ecs-svc/"), "deployment id is ecs-svc/<taskSetId>, was: " + id);
        return id.substring(id.indexOf('/') + 1);
    }

    @Test
    void theRevisionADeploymentTargetsIsNamedByThePrimaryDeploymentsTaskSetId() {
        String service = seedService("waiter-join-svc");
        String taskSetId = primaryTaskSetId(service);

        String targetRevision = call("ListServiceDeployments", "{\"cluster\":\"" + CLUSTER
                + "\",\"service\":\"" + service + "\"}")
                .jsonPath().getString("serviceDeployments[0].targetServiceRevisionArn");

        // Verbatim the provider's predicate: strings.Contains(targetServiceRevisionArn, taskSetID).
        assertTrue(targetRevision != null && targetRevision.contains(taskSetId),
                "targetServiceRevisionArn must carry the PRIMARY deployment's task set id "
                        + taskSetId + ", so a steady-state wait can find the deployment to poll; was: "
                        + targetRevision);
    }

    /**
     * Named for what it checks. It walks list -> join -> describe on an already-converged
     * service, so it never observes a deployment mid-flight: {@code desiredCount} is 0, which is
     * converged on arrival. Observing IN_PROGRESS over the wire would mean racing the 5-second
     * reconciler inside a {@code @QuarkusTest}, so that direction is asserted deterministically
     * in {@code EcsServiceDeploymentStatusTest} instead, and the writer echoes the field verbatim
     * ({@code EcsResponseWriter.serviceDeploymentNode}).
     */
    @Test
    void theJoinedDeploymentIsDescribable() {
        String service = seedService("waiter-walk-svc");
        String taskSetId = primaryTaskSetId(service);

        Response listed = call("ListServiceDeployments", "{\"cluster\":\"" + CLUSTER
                + "\",\"service\":\"" + service + "\"}");
        listed.then().body("serviceDeployments", hasSize(1));

        String deploymentArn = null;
        for (Object brief : listed.jsonPath().getList("serviceDeployments")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) brief;
            Object revision = map.get("targetServiceRevisionArn");
            if (revision != null && revision.toString().contains(taskSetId)) {
                deploymentArn = String.valueOf(map.get("serviceDeploymentArn"));
            }
        }
        assertNotNull(deploymentArn, "the listing must contain the PRIMARY deployment");

        // A service at its requested task count, desiredCount 0, is a finished deployment.
        call("DescribeServiceDeployments", "{\"serviceDeploymentArns\":[\"" + deploymentArn + "\"]}")
                .then()
                .body("serviceDeployments", hasSize(1))
                .body("serviceDeployments[0].status", equalTo("SUCCESSFUL"))
                // A deployment that succeeded was never stopped, so AWS reports neither field and
                // nor do we. Absent, not null: a client reading stoppedAt to learn when a
                // deployment ended must not be handed a value for one that did not stop.
                .body("serviceDeployments[0].stoppedAt", nullValue())
                .body("serviceDeployments[0].statusReason", nullValue());
    }

    /**
     * The stopped fields over the wire. A deployment that ends without completing carries
     * {@code stoppedAt} and {@code statusReason}, both of which are members of AWS's
     * {@code ServiceDeployment} shape and neither of which floci used to write at all, so a
     * client asking when a stopped deployment ended, or why, got nothing.
     *
     * <p>The deployment has to still be in flight when the delete arrives, and a test cannot
     * promise that: the 5-second reconciler runs on its own thread, and a tick that lands between
     * CreateService and DeleteService starts the task, so the delete correctly settles the
     * deployment SUCCESSFUL instead. That is a lost race, not a failure, so the test tries again
     * on a fresh service, a few times; the window is milliseconds against a 5000ms period. It
     * fails only if no attempt produced a stopped deployment, or one did and its fields are wrong.
     * The behaviour itself is pinned without any timing in
     * {@code EcsServiceDeploymentStatusTest.deletingAServiceStopsTheDeploymentItLeavesUnfinished};
     * what only this test can show is that the two fields reach the wire.
     */
    @Test
    void aStoppedDeploymentReportsWhenItStoppedAndWhy() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            String service = seedUnconvergedService("waiter-stop-svc-" + attempt);

            call("DeleteService", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"" + service
                    + "\",\"force\":true}");

            // Identified after the delete: the service was created once and never updated, so it
            // has exactly one deployment, and a terminal record cannot change under a read.
            Response listed = call("ListServiceDeployments", "{\"cluster\":\"" + CLUSTER
                    + "\",\"service\":\"" + service + "\"}");
            listed.then().body("serviceDeployments", hasSize(1));
            String deploymentArn = listed.jsonPath()
                    .getString("serviceDeployments[0].serviceDeploymentArn");
            assertNotNull(deploymentArn, "the deleted service's deployment is still describable");

            Response described = call("DescribeServiceDeployments",
                    "{\"serviceDeploymentArns\":[\"" + deploymentArn + "\"]}");
            if ("SUCCESSFUL".equals(described.jsonPath().getString("serviceDeployments[0].status"))) {
                continue;
            }
            described.then()
                    .body("serviceDeployments", hasSize(1))
                    .body("serviceDeployments[0].status", equalTo("STOPPED"))
                    .body("serviceDeployments[0].stoppedAt", notNullValue())
                    .body("serviceDeployments[0].finishedAt", notNullValue())
                    .body("serviceDeployments[0].statusReason", equalTo("The service was deleted."));
            return;
        }
        fail("the reconciler converged the service before every one of five deletes");
    }
}
