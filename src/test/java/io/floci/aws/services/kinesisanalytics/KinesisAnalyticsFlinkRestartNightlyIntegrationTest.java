package io.floci.aws.services.kinesisanalytics;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.services.kinesisanalytics.model.FlinkApplication;
import io.floci.aws.testing.AwsTestClients;
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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesisanalyticsv2.KinesisAnalyticsV2Client;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.ApplicationDetail;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.ApplicationStatus;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.RunConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * #5664: a Managed Service for Apache Flink application comes back after Floci restarts. Shutdown removes the
 * application's Flink cluster; the startup restore starts a new one, so the application is RUNNING again and its
 * JobManager answers. Removing the JobManager stands in for the shutdown, and the restore is what startup calls.
 *
 * <p>A nightly check: it runs a real Flink JobManager, which the shared test configuration mocks.
 */
@QuarkusTest
@TestProfile(KinesisAnalyticsFlinkRestartNightlyIntegrationTest.Profile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
// Bounded waits: the Flink image's pull, then the cluster starting twice.
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class KinesisAnalyticsFlinkRestartNightlyIntegrationTest {

    /** Real Flink clusters. */
    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.kinesis-analytics.mock", "false");
        }
    }

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/x";
    private static final Duration APPLICATION_TIMEOUT = Duration.ofMinutes(4);

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Inject
    DockerClient dockerClient;

    @Inject
    KinesisAnalyticsV2Service kinesisAnalyticsService;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient),
                "Docker daemon must be available for the Flink restart check");
    }

    @Test
    void anApplicationIsRunningAgainAfterARestart() throws Exception {
        String name = "restart-" + UUID.randomUUID().toString().substring(0, 8);
        try (KinesisAnalyticsV2Client client = AwsTestClients.create(
                KinesisAnalyticsV2Client.builder(), ACCOUNT, Region.of(REGION))) {
            client.createApplication(request -> request.applicationName(name).runtimeEnvironment("FLINK-1_20")
                    .serviceExecutionRole(ROLE));
            try {
                client.startApplication(request -> request.applicationName(name)
                        .runConfiguration(RunConfiguration.builder().build()));
                awaitRunning(client, name);
                FlinkApplication started = kinesisAnalyticsService.describeApplication(name, REGION);
                String jobManager = started.getContainerId();
                assertEquals(200, overview(started.getRestEndpoint()));

                dockerClient.removeContainerCmd(jobManager).withForce(true).exec();
                kinesisAnalyticsService.restorePersistedRuntime().join();

                awaitRunning(client, name);
                FlinkApplication restored = kinesisAnalyticsService.describeApplication(name, REGION);
                assertNotEquals(jobManager, restored.getContainerId());
                assertEquals(200, overview(restored.getRestEndpoint()));
            } finally {
                if (describe(client, name).applicationStatus() != ApplicationStatus.READY) {
                    client.stopApplication(request -> request.applicationName(name));
                }
                client.deleteApplication(request -> request.applicationName(name)
                        .createTimestamp(describe(client, name).createTimestamp()));
            }
        }
    }

    private static void awaitRunning(KinesisAnalyticsV2Client client, String name) {
        await("application " + name + " running").atMost(APPLICATION_TIMEOUT).pollInterval(Duration.ofSeconds(2))
                .until(() -> describe(client, name).applicationStatus(), status -> {
                    if (status == ApplicationStatus.READY) {
                        throw new AssertionError("application " + name + " went back to READY");
                    }
                    return status == ApplicationStatus.RUNNING;
                });
    }

    private static ApplicationDetail describe(KinesisAnalyticsV2Client client, String name) {
        return client.describeApplication(request -> request.applicationName(name)).applicationDetail();
    }

    /** The status of the JobManager's {@code GET /overview}, which answers once the cluster is up. */
    private int overview(String restEndpoint) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(restEndpoint + "/overview"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
