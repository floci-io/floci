package io.floci.aws.services.emr;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static io.floci.aws.testing.EmrRequests.emr;
import static io.floci.aws.testing.EmrRequests.terminateClusterAndAwaitCleanup;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An EMR session run for real: its Spark Connect server runs on its cluster, the release's image on the cluster's
 * YARN, and a Spark Connect client, Spark 4's own JVM client from that image, reaches it through Floci at the endpoint
 * GetSessionEndpoint gives, with the token and the session's id in the parameters AWS's connection string puts them
 * in. A wrong token is refused, queries run, a query's Spark error comes back as Spark's, and TerminateSession stops
 * the server.
 *
 * <p>A nightly check: the cluster's image, HDFS and YARN, the server and the client each take minutes and gigabytes.
 */
@QuarkusTest
@TestProfile(EmrSessionServerNightlyIntegrationTest.Profile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
// Bounded waits: the cluster's image of up to twenty minutes, its HDFS and YARN, the server's start, the client's run
// and the session's end; the Nightly checks job's timeout counts the 40.
@Timeout(value = 40, unit = TimeUnit.MINUTES)
class EmrSessionServerNightlyIntegrationTest {

    /** Clusters run their steps and sessions in containers, and Floci listens on a fixed port, its endpoint's. */
    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.emr.runner-mode", "docker",
                    "quarkus.http.test-port", String.valueOf(PORT),
                    "floci.port", String.valueOf(PORT),
                    "floci.base-url", "http://localhost:" + PORT);
        }
    }

    private static final int PORT = 4596;
    private static final String RELEASE = "emr-spark-8.1.0";
    // Spark 4's JVM Spark Connect client, which the release's image carries, run as a single source file, against
    // the URLs it is given: the refused one first, then the session's.
    private static final String CLIENT = """
            set -e
            mkdir -p /tmp/client && cd /tmp/client && cat > Probe.java <<'JAVA'
            import org.apache.spark.sql.SparkSession;
            public class Probe {
                public static void main(String[] args) throws Exception {
                    try {
                        SparkSession.builder().remote(args[1]).create().range(1).count();
                        System.out.println("REFUSED=none");
                    } catch (Exception e) {
                        System.out.println("REFUSED=" + e.getMessage());
                    }
                    SparkSession spark = SparkSession.builder().remote(args[0]).create();
                    System.out.println("COUNT=" + spark.range(10).count());
                    Object sum = spark.sql("SELECT sum(id) FROM range(5)").collectAsList().get(0).get(0);
                    System.out.println("SUM=" + sum);
                    try {
                        spark.sql("SELECT * FROM no_such_table").collectAsList();
                        System.out.println("ERROR=none");
                    } catch (Exception e) {
                        System.out.println("ERROR=" + e.getMessage());
                    }
                    spark.close();
                }
            }
            JAVA
            # The client's own jar first, then Spark's, less the classic SQL engine and the server, whose classes it
            # would otherwise reach for; Spark 4's shared API needs spark-core.
            classpath=$(ls /usr/lib/spark/jars/connect-repl/spark-connect-client-jvm_*.jar)
            for jar in /usr/lib/spark/jars/*.jar; do
              case $(basename "$jar") in
                spark-sql_*|spark-catalyst_*|spark-hive*|spark-connect_*) ;;
                *) classpath=$classpath:$jar ;;
              esac
            done
            # Java 17's launcher compiles the one file itself; Arrow needs java.nio opened.
            exec /usr/lib/jvm/java-17-amazon-corretto/bin/java --add-opens=java.base/java.nio=ALL-UNNAMED \\
                -cp "$classpath" Probe.java "$@"
            """;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    DockerClient docker;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    ContainerReachableEndpoint reachableEndpoint;

    @Inject
    EmulatorConfig config;

    @Inject
    EmrService emrService;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aClientRunsQueriesOnTheSessionsServerThroughFlociWithItsToken() throws InterruptedException {
        // Enabled, the check needs Docker: without a daemon it fails rather than pass having run nothing.
        assertTrue(DockerAvailability.isAvailable(), "the nightly check needs a Docker daemon");
        String clusterId = emr("RunJobFlow", "{\"Name\":\"sessions\",\"ReleaseLabel\":\"" + RELEASE + "\","
                + "\"Applications\":[{\"Name\":\"Hadoop\"},{\"Name\":\"Spark\"}],\"SessionEnabled\":true,"
                + "\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,\"InstanceGroups\":[{\"Name\":\"primary\","
                + "\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}]}}")
                .then().statusCode(200).extract().jsonPath().getString("JobFlowId");
        cleanup.register(() -> terminateClusterAndAwaitCleanup(clusterId, emrService));
        awaitCluster(clusterId, "WAITING", Duration.ofMinutes(5));
        String sessionId = emr("StartSession", "{\"ClusterId\":\"" + clusterId + "\",\"Name\":\"probe\","
                + "\"ClientRequestToken\":\"" + UUID.randomUUID() + "\"}")
                .then().statusCode(200).extract().jsonPath().getString("Id");

        awaitSession(clusterId, sessionId, "IDLE", Duration.ofMinutes(25));
        JsonPath endpoint = emr("GetSessionEndpoint", "{\"ClusterId\":\"" + clusterId + "\",\"SessionId\":\""
                + sessionId + "\"}").then().statusCode(200).extract().jsonPath();
        assertEquals("http://localhost:" + PORT, endpoint.getString("Endpoint"));
        // Floci's address as a container reaches it, where the test's endpoint names the host's loopback.
        URI floci = URI.create(reachableEndpoint.baseUrl(false));
        String connect = "sc://" + floci.getHost() + ":" + floci.getPort() + "/;authorization=" + sessionId
                + ";x-aws-proxy-auth=";
        String server = ContainerStorageHelper.dockerName(config, "emr-" + sessionId);
        String image = lifecycleManager.findByName(server).map(Container::getImage)
                .orElseThrow(() -> new AssertionError("no container " + server));
        String output = runClient(image, connect + endpoint.getString("AuthToken"), connect + "not-the-token");

        assertTrue(output.contains("REFUSED=") && output.contains("UNAUTHENTICATED"), output);
        assertTrue(output.contains("COUNT=10"), output);
        assertTrue(output.contains("SUM=10"), output);
        assertTrue(output.contains("ERROR=") && output.contains("TABLE_OR_VIEW_NOT_FOUND"), output);
        emr("TerminateSession", "{\"ClusterId\":\"" + clusterId + "\",\"SessionId\":\"" + sessionId + "\"}")
                .then().statusCode(200);
        awaitSession(clusterId, sessionId, "TERMINATED", Duration.ofMinutes(2));
        assertTrue(lifecycleManager.findByName(server).isEmpty(), "the session's server is still there");
    }

    // Runs the client in a container of the cluster's image, as far as Floci's address, and returns its output.
    private String runClient(String image, String sessionUrl, String refusedUrl) throws InterruptedException {
        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName("floci-spark-connect-client-" + UUID.randomUUID().toString().substring(0, 8))
                .withEntrypoint(List.of("/bin/bash", "-c", CLIENT, "floci", sessionUrl, refusedUrl))
                .withHostDockerInternalOnLinux()
                .withMemoryMb(1536)
                .build();
        String containerId = lifecycleManager.create(spec);
        try {
            lifecycleManager.startCreated(containerId, spec);
            int exitCode = docker.waitContainerCmd(containerId).start().awaitStatusCode(5, TimeUnit.MINUTES);
            String output = lifecycleManager.lastLogLines(containerId, 200, Duration.ofSeconds(30));
            assertEquals(0, exitCode, output);
            return output;
        } finally {
            lifecycleManager.stopAndRemove(containerId, null, 1);
        }
    }

    private void awaitCluster(String clusterId, String state, Duration limit) {
        await(() -> emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}").jsonPath()
                .getString("Cluster.Status.State"), state, List.of("TERMINATED", "TERMINATED_WITH_ERRORS"), limit,
                "Cluster " + clusterId);
    }

    private void awaitSession(String clusterId, String sessionId, String state, Duration limit) {
        await(() -> emr("GetSession", "{\"ClusterId\":\"" + clusterId + "\",\"SessionId\":\"" + sessionId + "\"}")
                .jsonPath().getString("Session.State"), state, List.of("FAILED", "TERMINATED"), limit,
                "Session " + sessionId);
    }

    private static void await(Supplier<String> read, String state, List<String> ends,
                              Duration limit, String what) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (true) {
            String now = read.get();
            if (state.equals(now)) {
                return;
            }
            if (ends.contains(now) || System.nanoTime() - deadline > 0) {
                fail(what + " is " + now + ", not " + state);
            }
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));
        }
    }
}
