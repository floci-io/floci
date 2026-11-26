package io.floci.aws.services.neptune;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.services.neptune.container.NeptuneContainerHandle;
import io.floci.aws.services.neptune.container.NeptuneContainerManager;
import io.floci.aws.services.neptune.model.NeptuneDbType;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #5664: a Neptune cluster's graph outlives its container when the cluster has a volume. Each engine writes a
 * vertex, its container is stopped and removed as Floci's shutdown does, and a new container started on the same
 * volume reads the vertex back: Neo4j from its data directory, Gremlin Server from the file its TinkerGraph saved
 * when it stopped.
 *
 * <p>A Docker end-to-end check: it runs real Gremlin Server and Neo4j containers.
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class NeptuneGraphVolumeDockerNightlyIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @Inject
    DockerClient dockerClient;

    @Inject
    NeptuneContainerManager containerManager;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    EmulatorConfig config;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient),
                "Docker daemon must be available for the Neptune graph volume check");
    }

    @Test
    void aGremlinGraphIsBackOnANewContainer() throws Exception {
        String id = "graph-" + UUID.randomUUID().toString().substring(0, 8);
        String volumeId = UUID.randomUUID().toString();
        String image = config.services().neptune().defaultImage();
        try {
            NeptuneContainerHandle first = containerManager.start(id, image, NeptuneDbType.GREMLIN, ACCOUNT, REGION,
                    volumeId);
            String added = gremlin(first, "g.addV('person').property('name','kept').count()");
            assertTrue(added.contains("\"code\":200"), added);
            containerManager.stop(first);

            NeptuneContainerHandle second = containerManager.start(id, image, NeptuneDbType.GREMLIN, ACCOUNT,
                    REGION, volumeId);
            try {
                assertNotEquals(first.getContainerId(), second.getContainerId());
                String found = gremlin(second, "g.V().has('person','name','kept').values('name')");
                assertTrue(found.contains("kept"), found);
            } finally {
                containerManager.stop(second);
            }
        } finally {
            cleanUp(id, volumeId);
        }
    }

    @Test
    void aNeo4jGraphIsBackOnANewContainer() {
        String id = "graph-" + UUID.randomUUID().toString().substring(0, 8);
        String volumeId = UUID.randomUUID().toString();
        String image = config.services().neptune().defaultNeo4jImage();
        try {
            NeptuneContainerHandle first = containerManager.start(id, image, NeptuneDbType.NEO4J, ACCOUNT, REGION,
                    volumeId);
            try (Driver driver = bolt(first); Session session = driver.session()) {
                session.run("CREATE (:Person {name: 'kept'})").consume();
            }
            containerManager.stop(first);

            NeptuneContainerHandle second = containerManager.start(id, image, NeptuneDbType.NEO4J, ACCOUNT, REGION,
                    volumeId);
            try (Driver driver = bolt(second); Session session = driver.session()) {
                assertNotEquals(first.getContainerId(), second.getContainerId());
                long count = session.run("MATCH (p:Person {name: 'kept'}) RETURN count(p) AS c")
                        .single().get("c").asLong();
                assertEquals(1L, count);
            } finally {
                containerManager.stop(second);
            }
        } finally {
            cleanUp(id, volumeId);
        }
    }

    /**
     * Removes whatever container the cluster still has, by its identity, whether or not a start returned its handle,
     * and then the volume, which no container holds any more.
     */
    private void cleanUp(String id, String volumeId) {
        containerManager.stopByClusterId(ACCOUNT + "-" + REGION + "-" + id);
        lifecycleManager.removeVolume(volumeName(volumeId));
    }

    private String volumeName(String volumeId) {
        return ContainerStorageHelper.dockerName(config, "neptune-" + volumeId);
    }

    private static Driver bolt(NeptuneContainerHandle handle) {
        return GraphDatabase.driver("bolt://" + handle.getHost() + ":" + handle.getPort(), AuthTokens.none());
    }

    /**
     * Evaluates {@code script} on the container's Gremlin Server over its WebSocket endpoint, as a GraphSON text
     * frame, and returns the response frame. A server that has just opened its port can still be loading its graph,
     * so the request is retried until it is answered.
     */
    private static String gremlin(NeptuneContainerHandle handle, String script) {
        String request = """
                {"requestId":"%s","op":"eval","processor":"","args":{"gremlin":"%s","language":"gremlin-groovy"}}"""
                .formatted(UUID.randomUUID(), script);
        return await("gremlin answer").atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> send(handle, request), response -> response.contains("\"status\""));
    }

    private static String send(NeptuneContainerHandle handle, String request) throws Exception {
        CompletableFuture<String> response = new CompletableFuture<>();
        StringBuilder text = new StringBuilder();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://" + handle.getHost() + ":" + handle.getPort() + "/gremlin"),
                        new WebSocket.Listener() {
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
}
