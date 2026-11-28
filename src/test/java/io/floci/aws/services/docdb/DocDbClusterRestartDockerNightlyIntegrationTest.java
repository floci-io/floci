package io.floci.aws.services.docdb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.docdb.container.DocDbContainerHandle;
import io.floci.aws.services.docdb.container.DocDbContainerManager;
import io.floci.aws.services.docdb.model.DocDbCluster;
import io.floci.aws.services.docdb.model.DocDbInstance;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #5664: a DocumentDB cluster comes back after Floci restarts with the data it held. Shutdown removes each cluster's
 * MongoDB container and keeps its volume; the startup restore starts a new container on it with the master password
 * the data was set up with, so a document written before reads back, and a snapshot, which authenticates with that
 * password, still works. Stopping the containers, as shutdown does, stands in for the shutdown, reloading the records
 * from disk for what a restart reads back, and the restore is what startup calls. One cluster is in another account
 * and Region, so the restore is seen starting each in its own.
 *
 * <p>A Docker end-to-end check: it runs real MongoDB containers with the cluster records on disk, which the shared
 * test configuration keeps in memory.
 */
@QuarkusTest
@TestProfile(DocDbClusterRestartDockerNightlyIntegrationTest.Profile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class DocDbClusterRestartDockerNightlyIntegrationTest {

    /** Real MongoDB containers, the cluster records on disk, and volumes removed with their clusters. */
    public static final class Profile implements QuarkusTestProfile {
        private static final String DATA = "./target/test-data/docdb-restart-" + UUID.randomUUID();

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.docdb.mock", "false",
                    "floci.storage.mode", "persistent",
                    "floci.storage.persistent-path", DATA,
                    "floci.storage.prune-volumes-on-delete", "true");
        }
    }

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String OTHER_ACCOUNT = "111111111111";
    private static final String OTHER_REGION = "eu-west-1";
    private static final String USERNAME = "docdbadmin";
    private static final String PASSWORD = "restart99password";

    @Inject
    DockerClient dockerClient;

    @Inject
    DocDbService docDbService;

    @Inject
    DocDbContainerManager containerManager;

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient),
                "Docker daemon must be available for the DocumentDB restart check");
    }

    @Test
    void aClusterKeepsItsDataAcrossARestart() {
        String id = "restart-" + UUID.randomUUID().toString().substring(0, 8);
        String instanceId = id + "-1";
        String defaultAuth = auth("000000000000", "us-east-1");
        String otherAuth = auth(OTHER_ACCOUNT, OTHER_REGION);
        try {
            call(defaultAuth, "CreateDBCluster", Map.of("DBClusterIdentifier", id, "Engine", "docdb",
                    "MasterUsername", USERNAME, "MasterUserPassword", PASSWORD));
            call(defaultAuth, "CreateDBInstance", Map.of("DBInstanceIdentifier", instanceId,
                    "DBClusterIdentifier", id, "DBInstanceClass", "db.r5.large", "Engine", "docdb"));
            call(otherAuth, "CreateDBCluster", Map.of("DBClusterIdentifier", id, "Engine", "docdb",
                    "MasterUsername", USERNAME, "MasterUserPassword", PASSWORD));
            DocDbCluster before = docDbService.getDbCluster(id, "us-east-1");
            DocDbCluster otherBefore = otherCluster(id);
            write(before.getContainerId(), "here");
            write(otherBefore.getContainerId(), "elsewhere");

            for (DocDbCluster cluster : new DocDbCluster[] {before, otherBefore}) {
                containerManager.stop(new DocDbContainerHandle(cluster.getContainerId(), id,
                        cluster.getContainerHost(), cluster.getContainerPort()));
            }
            storageFactory.create("docdb", "docdb-clusters.json",
                    new TypeReference<Map<String, DocDbCluster>>() {}).load();
            storageFactory.create("docdb", "docdb-instances.json",
                    new TypeReference<Map<String, DocDbInstance>>() {}).load();
            assertNotSame(before, docDbService.getDbCluster(id, "us-east-1"));
            docDbService.restorePersistedRuntime().join();

            DocDbCluster after = docDbService.getDbCluster(id, "us-east-1");
            assertEquals("available", after.getStatus());
            assertNotEquals(before.getContainerId(), after.getContainerId());
            assertEquals("here", read(after.getContainerId()));
            DocDbInstance instance = docDbService.getDbInstance(instanceId, "us-east-1");
            assertEquals("available", instance.getStatus());
            assertEquals(after.getPort(), instance.getPort());
            DocDbCluster otherAfter = otherCluster(id);
            assertEquals("available", otherAfter.getStatus());
            assertNotEquals(otherBefore.getContainerId(), otherAfter.getContainerId());
            assertEquals("elsewhere", read(otherAfter.getContainerId()));

            String snapshot = call(defaultAuth, "CreateDBClusterSnapshot",
                    Map.of("DBClusterSnapshotIdentifier", id + "-snap", "DBClusterIdentifier", id));
            assertTrue(snapshot.contains("<Status>available</Status>"), snapshot);
        } finally {
            given().header("Authorization", defaultAuth).contentType(FORM)
                    .formParam("Action", "DeleteDBClusterSnapshot")
                    .formParam("DBClusterSnapshotIdentifier", id + "-snap").when().post("/");
            given().header("Authorization", defaultAuth).contentType(FORM)
                    .formParam("Action", "DeleteDBInstance").formParam("DBInstanceIdentifier", instanceId)
                    .when().post("/");
            for (String auth : new String[] {defaultAuth, otherAuth}) {
                given().header("Authorization", auth).contentType(FORM)
                        .formParam("Action", "DeleteDBCluster").formParam("DBClusterIdentifier", id)
                        .formParam("SkipFinalSnapshot", "true").when().post("/");
            }
        }
    }

    private DocDbCluster otherCluster(String id) {
        return RequestScopes.callAs(OTHER_ACCOUNT, OTHER_REGION, () -> docDbService.getDbCluster(id, OTHER_REGION));
    }

    /** Writes a document holding {@code value} into the cluster, through mongosh inside its container. */
    private void write(String containerId, String value) {
        await("document written").atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofSeconds(2))
                .until(() -> mongosh(containerId,
                        "db.getSiblingDB('app').items.insertOne({_id: 'kept', value: '" + value + "'})")
                        .exitCode() == 0);
    }

    /**
     * The value of the document {@link #write} wrote, read back from the cluster once its server accepts
     * connections, which can be a moment after its published port does.
     */
    private String read(String containerId) {
        return await("document read").atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofSeconds(2))
                .until(() -> mongosh(containerId,
                        "print(db.getSiblingDB('app').items.findOne({_id: 'kept'}).value)"),
                        result -> result.exitCode() == 0)
                .stdout().trim();
    }

    private ContainerExec.Result mongosh(String containerId, String script) {
        return ContainerExec.run(dockerClient, containerId, new String[] {"mongosh", "--quiet",
                "--username", USERNAME, "--password", PASSWORD, "--authenticationDatabase", "admin",
                "--eval", script}, 60);
    }

    private static String call(String auth, String action, Map<String, String> params) {
        return given().header("Authorization", auth).contentType(FORM).formParam("Action", action)
                .formParams(params)
                .when().post("/")
                .then().statusCode(200).extract().asString();
    }

    private static String auth(String account, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260615/" + region + "/docdb/aws4_request, "
                + "SignedHeaders=content-type;host, Signature=test";
    }
}
