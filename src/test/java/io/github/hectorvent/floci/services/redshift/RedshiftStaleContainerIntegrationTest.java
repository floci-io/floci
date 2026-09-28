package io.github.hectorvent.floci.services.redshift;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A container left under a cluster's fixed name by an earlier Floci process must not make
 * CreateCluster fail with a Docker name conflict.
 */
@QuarkusTest
class RedshiftStaleContainerIntegrationTest {

    private static final String CLUSTER_ID = "stale-container-cluster";

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260822/us-east-1/redshift/aws4_request";

    @Inject
    DockerClient dockerClient;

    @Inject
    ImageCacheService imageCacheService;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    EmulatorConfig config;

    @BeforeEach
    void requireDocker() {
        boolean available;
        try {
            dockerClient.pingCmd().exec();
            available = true;
        } catch (RuntimeException ignored) {
            // An unreachable daemon only means this test is skipped; the assumption message says so.
            available = false;
        }
        Assumptions.assumeTrue(available, "Docker daemon must be available for Redshift container tests");
    }

    @Test
    void createClusterReplacesAContainerLeftUnderItsName() {
        String containerName = ContainerStorageHelper.dockerName(
                config, "redshift-" + config.defaultAccountId() + "-" + CLUSTER_ID);
        String image = imageCacheService.ensureImageExists(config.services().redshift().imageVersion());
        // An interrupted earlier run may have left its fixture under the same name
        lifecycleManager.removeIfExists(containerName);
        String staleId = dockerClient.createContainerCmd(image)
                .withName(containerName)
                .exec()
                .getId();
        Response created = null;
        try {
            created = query("Action", "CreateCluster", "ClusterIdentifier", CLUSTER_ID,
                    "NodeType", "dc2.large", "MasterUsername", "admin", "MasterUserPassword", "Password123");
            created.then().statusCode(200);

            Optional<Container> running = lifecycleManager.findByName(containerName);
            assertTrue(running.isPresent());
            assertNotEquals(staleId, running.get().getId());
        } finally {
            try {
                // Only a created cluster can be deleted; asserting a 404 here would hide the original failure
                if (created != null && created.statusCode() == 200) {
                    query("Action", "DeleteCluster", "ClusterIdentifier", CLUSTER_ID,
                            "SkipFinalClusterSnapshot", "true")
                            .then().statusCode(200);
                }
            } finally {
                lifecycleManager.removeIfExists(containerName);
            }
        }
    }

    private static Response query(String... formParams) {
        RequestSpecification spec = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH_HEADER)
                .formParam("Version", "2012-12-01");
        for (int i = 0; i < formParams.length; i += 2) {
            spec = spec.formParam(formParams[i], formParams[i + 1]);
        }
        return spec.when().post("/");
    }
}
