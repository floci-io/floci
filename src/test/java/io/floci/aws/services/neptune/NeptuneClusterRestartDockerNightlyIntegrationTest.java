package io.floci.aws.services.neptune;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.neptune.container.NeptuneContainerHandle;
import io.floci.aws.services.neptune.container.NeptuneContainerManager;
import io.floci.aws.services.neptune.model.NeptuneCluster;
import io.floci.aws.services.neptune.model.NeptuneInstance;
import io.floci.aws.services.neptune.proxy.NeptuneProxyManager;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #5664: a Neptune cluster comes back after Floci restarts with the graph it held, on the port it had. Shutdown stops
 * each cluster's proxy and container and keeps its volume; the startup restore starts a new container on it and the
 * proxy on the cluster's port again, so a vertex added before reads back through the same endpoint. Stopping the
 * proxies and containers, as shutdown does, stands in for the shutdown, reloading the records from disk for what a
 * restart reads back, and the restore is what startup calls. One cluster is in another account and Region, so the
 * restore is seen starting each in its own.
 *
 * <p>A Docker end-to-end check: it runs real Gremlin Server containers with the cluster records on disk, which the
 * shared test configuration keeps in memory.
 */
@QuarkusTest
@TestProfile(NeptuneClusterRestartDockerNightlyIntegrationTest.Profile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class NeptuneClusterRestartDockerNightlyIntegrationTest {

    /** Real Gremlin Server containers, the cluster records on disk, and volumes removed with their clusters. */
    public static final class Profile implements QuarkusTestProfile {
        private static final String DATA = "./target/test-data/neptune-restart-" + UUID.randomUUID();

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.neptune.db-type", "gremlin",
                    "floci.storage.mode", "persistent",
                    "floci.storage.persistent-path", DATA,
                    "floci.storage.prune-volumes-on-delete", "true");
        }
    }

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String OTHER_ACCOUNT = "111111111111";
    private static final String OTHER_REGION = "eu-west-1";

    @Inject
    DockerClient dockerClient;

    @Inject
    NeptuneService neptuneService;

    @Inject
    NeptuneContainerManager containerManager;

    @Inject
    NeptuneProxyManager proxyManager;

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient),
                "Docker daemon must be available for the Neptune restart check");
    }

    @Test
    void aClusterKeepsItsGraphAndPortAcrossARestart() throws Exception {
        String id = "restart-" + UUID.randomUUID().toString().substring(0, 8);
        String instanceId = id + "-1";
        String defaultAuth = auth(ACCOUNT, REGION);
        String otherAuth = auth(OTHER_ACCOUNT, OTHER_REGION);
        try {
            call(defaultAuth, "CreateDBCluster", Map.of("DBClusterIdentifier", id, "Engine", "neptune"));
            call(defaultAuth, "CreateDBInstance", Map.of("DBInstanceIdentifier", instanceId,
                    "DBClusterIdentifier", id, "DBInstanceClass", "db.r5.large", "Engine", "neptune"));
            call(otherAuth, "CreateDBCluster", Map.of("DBClusterIdentifier", id, "Engine", "neptune"));
            NeptuneCluster before = cluster(ACCOUNT, REGION, id);
            NeptuneCluster otherBefore = cluster(OTHER_ACCOUNT, OTHER_REGION, id);
            assertTrue(gremlin(before.getPort(), "g.addV('person').property('name','here').count()")
                    .contains("\"code\":200"));
            assertTrue(gremlin(otherBefore.getPort(), "g.addV('person').property('name','elsewhere').count()")
                    .contains("\"code\":200"));

            for (NeptuneCluster cluster : new NeptuneCluster[] {before, otherBefore}) {
                String identity = identity(cluster, id);
                proxyManager.stopProxy(identity);
                containerManager.stop(new NeptuneContainerHandle(cluster.getContainerId(), identity,
                        cluster.getContainerHost(), cluster.getContainerPort()));
            }
            storageFactory.create("neptune", "neptune-clusters.json",
                    new TypeReference<Map<String, NeptuneCluster>>() {}).load();
            storageFactory.create("neptune", "neptune-instances.json",
                    new TypeReference<Map<String, NeptuneInstance>>() {}).load();
            assertNotSame(before, cluster(ACCOUNT, REGION, id));
            neptuneService.restorePersistedRuntime().join();

            NeptuneCluster after = cluster(ACCOUNT, REGION, id);
            assertEquals("available", after.getStatus());
            assertEquals(before.getPort(), after.getPort());
            assertNotEquals(before.getContainerId(), after.getContainerId());
            assertTrue(gremlin(after.getPort(), "g.V().has('person','name','here').values('name')")
                    .contains("here"));
            NeptuneInstance instance = RequestScopes.callAs(ACCOUNT, REGION,
                    () -> neptuneService.getDbInstance(instanceId));
            assertEquals("available", instance.getStatus());
            assertEquals(after.getPort(), instance.getPort());
            NeptuneCluster otherAfter = cluster(OTHER_ACCOUNT, OTHER_REGION, id);
            assertEquals("available", otherAfter.getStatus());
            assertNotEquals(otherBefore.getContainerId(), otherAfter.getContainerId());
            assertTrue(gremlin(otherAfter.getPort(), "g.V().has('person','name','elsewhere').values('name')")
                    .contains("elsewhere"));
        } finally {
            given().header("Authorization", defaultAuth).contentType(FORM)
                    .formParam("Action", "DeleteDBInstance").formParam("DBInstanceIdentifier", instanceId)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
            for (String auth : new String[] {defaultAuth, otherAuth}) {
                given().header("Authorization", auth).contentType(FORM)
                        .formParam("Action", "DeleteDBCluster").formParam("DBClusterIdentifier", id)
                        .formParam("SkipFinalSnapshot", "true").when().post("/");
            }
        }
    }

    private NeptuneCluster cluster(String account, String region, String id) {
        return RequestScopes.callAs(account, region, () -> neptuneService.getDbCluster(id));
    }

    private static String identity(NeptuneCluster cluster, String id) {
        String[] arn = cluster.getDbClusterArn().split(":");
        return arn[4] + "-" + arn[3] + "-" + id;
    }

    /**
     * Evaluates {@code script} through the cluster's port, as a GraphSON text frame over Gremlin Server's WebSocket
     * endpoint, and returns the response frame, once the server answers.
     */
    private static String gremlin(int port, String script) {
        String request = """
                {"requestId":"%s","op":"eval","processor":"","args":{"gremlin":"%s","language":"gremlin-groovy"}}"""
                .formatted(UUID.randomUUID(), script);
        return await("gremlin answer").atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> send(port, request), response -> response.contains("\"status\""));
    }

    private static String send(int port, String request) throws Exception {
        CompletableFuture<String> response = new CompletableFuture<>();
        StringBuilder text = new StringBuilder();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port + "/gremlin"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        text.append(data);
                        if (last) {
                            response.complete(text.toString());
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        response.completeExceptionally(error);
                    }
                })
                .get(10, TimeUnit.SECONDS);
        try {
            socket.sendText(request, true).get(10, TimeUnit.SECONDS);
            return response.get(30, TimeUnit.SECONDS);
        } finally {
            socket.abort();
        }
    }

    private static String call(String auth, String action, Map<String, String> params) {
        return given().header("Authorization", auth).contentType(FORM).formParam("Action", action)
                .formParams(params)
                .when().post("/")
                .then().statusCode(200).extract().asString();
    }

    private static String auth(String account, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260516/" + region + "/neptune/aws4_request, "
                + "SignedHeaders=content-type;host, Signature=test";
    }
}
