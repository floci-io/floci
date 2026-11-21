package io.floci.aws.services.emrserverless;

import com.github.dockerjava.api.DockerClient;
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
import io.restassured.response.Response;
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

import static io.floci.aws.testing.EmrServerlessRequests.deleteApplication;
import static io.floci.aws.testing.EmrServerlessRequests.emrServerless;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An EMR Serverless session run for real: its Spark Connect server runs in the release's image, and a Spark Connect
 * client, Spark's own JVM client from the same image, reaches it through Floci at the endpoint GetSessionEndpoint
 * gives, with the token in its {@code x-aws-proxy-auth} parameter as EMR Serverless's connection string puts it. A
 * wrong token is refused, queries run, a query's Spark error comes back as Spark's, and TerminateSession stops the
 * server.
 *
 * <p>A nightly check: the release's image is 3.7 GB, and the server and the client each start a JVM.
 */
@QuarkusTest
@TestProfile(EmrServerlessSparkConnectNightlyIntegrationTest.Profile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
// Bounded waits: the image's pull of up to twenty minutes, then the server's start, the client's run and the session's
// end, a few minutes; the Nightly checks job's timeout counts the 30.
@Timeout(value = 30, unit = TimeUnit.MINUTES)
class EmrServerlessSparkConnectNightlyIntegrationTest {

    /** Sessions run their servers in containers, and Floci listens on a fixed port, the one its endpoint names. */
    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.emrserverless.runner-mode", "docker",
                    "quarkus.http.test-port", String.valueOf(PORT),
                    "floci.port", String.valueOf(PORT),
                    "floci.base-url", "http://localhost:" + PORT);
        }
    }

    private static final int PORT = 4597;
    private static final String REGION = "us-east-1";
    private static final String RELEASE = "emr-7.13.0";
    private static final String IMAGE = "public.ecr.aws/emr-serverless/spark/emr-7.13.0:latest";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-serverless-session";
    // Spark's JVM Spark Connect client, which the release's image carries, compiled and run against the URLs it is
    // given: the refused one first, then the session's.
    private static final String CLIENT = """
            set -e
            mkdir -p /tmp/client && cd /tmp/client && cat > Probe.java <<'JAVA'
            import org.apache.spark.sql.SparkSession;
            public class Probe {
                public static void main(String[] args) {
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
            # The client's own jar first, then Spark's, less those whose classes it replaces.
            classpath=/usr/lib/spark/connector/lib/spark-connect-client-jvm.jar
            for jar in /usr/lib/spark/jars/*.jar; do
              case $(basename "$jar") in
                spark-sql_*|spark-catalyst_*|spark-hive*|spark-core_*) ;;
                *) classpath=$classpath:$jar ;;
              esac
            done
            javac -cp "$classpath" Probe.java
            # Arrow, which the client reads answers with, needs java.nio opened.
            exec java --add-opens=java.base/java.nio=ALL-UNNAMED -cp ".:$classpath" Probe "$@"
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

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aClientRunsAQueryOnTheSessionsServerThroughFlociWithItsToken() throws InterruptedException {
        // Enabled, the check needs Docker: without a daemon it fails rather than pass having run nothing.
        assertTrue(DockerAvailability.isAvailable(), "the nightly check needs a Docker daemon");
        String applicationId = emrServerless(REGION).body("""
                        {"name": "spark-connect", "releaseLabel": "%s", "type": "SPARK", "clientToken": "%s",
                         "interactiveConfiguration": {"sessionEnabled": true}}
                        """.formatted(RELEASE, UUID.randomUUID()))
                .when().post("/applications")
                .then().statusCode(200).extract().path("applicationId");
        cleanup.register(() -> deleteApplication(REGION, applicationId));
        String sessionId = emrServerless(REGION).body("""
                        {"clientToken": "%s", "executionRoleArn": "%s", "idleTimeoutMinutes": 30}
                        """.formatted(UUID.randomUUID(), ROLE))
                .when().post("/applications/" + applicationId + "/sessions")
                .then().statusCode(200).extract().path("sessionId");
        cleanup.register(() -> emrServerless(REGION).when()
                .delete("/applications/" + applicationId + "/sessions/" + sessionId));

        awaitState(applicationId, sessionId, "IDLE", Duration.ofMinutes(22));
        Response endpoint = emrServerless(REGION).when()
                .get("/applications/" + applicationId + "/sessions/" + sessionId + "/endpoint")
                .then().statusCode(200).extract().response();
        assertEquals("http://localhost:" + PORT, endpoint.path("endpoint"));
        // Floci's address as a container reaches it, where the test's endpoint names the host's loopback.
        URI floci = URI.create(reachableEndpoint.baseUrl(false));
        String connect = "sc://" + floci.getHost() + ":" + floci.getPort() + "/;x-aws-proxy-auth=";
        String output = runClient(connect + endpoint.path("authToken"), connect + "not-the-token");

        assertTrue(output.contains("REFUSED=") && output.contains("UNAUTHENTICATED"), output);
        assertTrue(output.contains("COUNT=10"), output);
        assertTrue(output.contains("SUM=10"), output);
        // Spark's own error, which its server answers before any message, reaches the client through Floci.
        assertTrue(output.contains("ERROR=") && output.contains("TABLE_OR_VIEW_NOT_FOUND"), output);
        assertEquals("IDLE", session(applicationId, sessionId).path("session.state"));

        emrServerless(REGION).when().delete("/applications/" + applicationId + "/sessions/" + sessionId)
                .then().statusCode(200);
        awaitState(applicationId, sessionId, "TERMINATED", Duration.ofMinutes(2));
        String server = ContainerStorageHelper.dockerName(config, "emr-serverless-" + sessionId);
        assertTrue(lifecycleManager.findByName(server).isEmpty(), "the session's server is still there");
    }

    // Runs the client in a container of the release's image, as far as Floci's address, and returns its output.
    private String runClient(String sessionUrl, String refusedUrl) throws InterruptedException {
        ContainerSpec spec = containerBuilder.newContainer(IMAGE)
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

    private void awaitState(String applicationId, String sessionId, String state, Duration limit) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (true) {
            Response session = session(applicationId, sessionId);
            String now = session.path("session.state");
            if (state.equals(now)) {
                return;
            }
            if (List.of("FAILED", "TERMINATED").contains(now) || System.nanoTime() - deadline > 0) {
                fail("Session " + sessionId + " is " + now + ", not " + state + ": "
                        + session.path("session.stateDetails"));
            }
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));
        }
    }

    private static Response session(String applicationId, String sessionId) {
        return emrServerless(REGION).when().get("/applications/" + applicationId + "/sessions/" + sessionId)
                .then().statusCode(200).extract().response();
    }
}
