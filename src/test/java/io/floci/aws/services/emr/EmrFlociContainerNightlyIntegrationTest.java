package io.floci.aws.services.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.EmrRequests;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.S3TestClients;
import io.floci.aws.testing.TestDockerClients;
import io.floci.aws.testutil.FreePorts;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.EmrRequests.awaitStep;
import static io.floci.aws.testing.EmrRequests.emr;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An EMR cluster with Hive and Spark, run by Floci in a container of its own with the Docker socket, as a user's
 * Docker setup runs it. The cluster's nodes are the Floci container's siblings on a network of their own, so Floci's
 * container joins that network for the cluster's metastore service to reach Floci's S3, and leaves it once the cluster
 * has ended. A Hive step makes tables over rows in S3, a Spark step reads one through the metastore and makes another,
 * and a later Hive step reads both.
 *
 * <p>A nightly check: it runs the image {@code nightly.checks.floci-image} names, which the nightly workflow builds from the
 * pull request's own code, and pulls the release's Spark and Hive images.
 */
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
// Bounded waits: three steps of up to five minutes each and five minutes for the cluster to end, past the default two.
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class EmrFlociContainerNightlyIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";
    private static final int FLOCI_PORT = 4566;
    private static final Set<String> TERMINATED = Set.of("TERMINATED", "TERMINATED_WITH_ERRORS");
    private static final Duration FLOCI_START = Duration.ofMinutes(3);
    private static final Duration CLUSTER_END = Duration.ofMinutes(5);

    private final List<Runnable> cleanup = new ArrayList<>();
    private DockerClient docker;
    private String flociContainer;
    private S3Client s3;
    private String bucket;
    private String clusterId;

    // Floci's health check alone may take FLOCI_START, three minutes.
    @BeforeEach
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void startFloci() throws InterruptedException, IOException {
        // Enabled, the check needs both: without either it fails rather than pass having run nothing.
        String image = System.getProperty("nightly.checks.floci-image");
        assertNotNull(image, "the nightly check needs -Dnightly.checks.floci-image naming the Floci image to run");
        assertTrue(DockerAvailability.isAvailable(), "the nightly check needs a Docker daemon");
        docker = TestDockerClients.local();
        cleanup.add(() -> {
            try {
                docker.close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        int hostPort = FreePorts.anyInterfacePort();
        ExposedPort flociPort = ExposedPort.tcp(FLOCI_PORT);
        flociContainer = docker.createContainerCmd(image)
                .withName("floci-nightly-emr-" + UUID.randomUUID().toString().substring(0, 8))
                .withEnv("FLOCI_SERVICES_EMR_RUNNER_MODE=docker")
                .withExposedPorts(flociPort)
                .withHostConfig(HostConfig.newHostConfig()
                        .withBinds(new Bind("/var/run/docker.sock", new Volume("/var/run/docker.sock")))
                        .withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", hostPort),
                                flociPort)))
                .exec()
                .getId();
        cleanup.add(this::removeClusterLeftovers);
        cleanup.add(this::stopFloci);
        docker.startContainerCmd(flociContainer).exec();
        awaitHealthy(hostPort);

        // A plain JUnit class has no Quarkus test setup to point RestAssured at Floci and teach it the AWS JSON
        // content type EMR's requests are sent as; the reset undoes both.
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = hostPort;
        RestAssuredJsonUtils.configureAwsContentTypes();
        cleanup.add(RestAssured::reset);
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), ACCOUNT, Region.US_EAST_1,
                URI.create("http://localhost:" + hostPort));
        cleanup.add(s3::close);
        bucket = "emr-floci-" + UUID.randomUUID().toString().substring(0, 8);
        s3.createBucket(request -> request.bucket(bucket));
        put("data/rows/rows.csv", HiveCatalogJobs.ROWS);
        put("queries/load.q", HiveCatalogJobs.LOAD_QUERY);
        put("queries/report.q", HiveCatalogJobs.REPORT_QUERY);
        put("jobs/catalog.py", HiveCatalogJobs.CATALOG_JOB);
    }

    // Every action runs even when one before it fails: a Floci that no longer answers still has its container,
    // and the cluster's, removed. assertAll reports each failure.
    @AfterEach
    void tearDown() {
        List<Executable> actions = new ArrayList<>();
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            actions.add(cleanup.get(i)::run);
        }
        assertAll("cleanup", actions);
    }

    @Test
    void aClusterRunByFlociInAContainerSharesItsMetastoreOverFlocisS3AndLeavesNoNetworkBehind() {
        startCluster();

        assertEquals("COMPLETED", step("hive-script", "--run-hive-script", "--args",
                "-f", s3Uri("queries/load.q"), "-d", "INPUT=" + s3Uri("data/rows/")));
        assertEquals("COMPLETED", step("spark-submit", s3Uri("jobs/catalog.py"), s3Uri("out/spark/")));
        assertEquals(HiveCatalogJobs.CATALOG_OUTPUT, objects("out/spark/"));
        assertEquals("COMPLETED", step("hive-script", "--run-hive-script", "--args",
                "-f", s3Uri("queries/report.q"), "-d", "OUTPUT=" + s3Uri("out/report/")));
        assertEquals(HiveCatalogJobs.REPORT_OUTPUT, objects("out/report/"));
        assertTrue(clusterNetworks().size() > 0, "Floci's container must be on the cluster's network: " + networks());

        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}").then().statusCode(200);
        await("cluster " + clusterId + " to end and Floci's container to leave its network")
                .atMost(CLUSTER_END)
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> TERMINATED.contains(clusterState()) && clusterNetworks().isEmpty());
    }

    private void startCluster() {
        ObjectNode request = JSON.createObjectNode().put("Name", "floci-in-a-container")
                .put("ReleaseLabel", "emr-7.5.0").put("LogUri", s3Uri("logs/"));
        ObjectNode[] applications = {JSON.createObjectNode().put("Name", "Hadoop"),
                JSON.createObjectNode().put("Name", "Hive"), JSON.createObjectNode().put("Name", "Spark")};
        request.putArray("Applications").addAll(List.of(applications));
        request.putObject("Instances").put("KeepJobFlowAliveWhenNoSteps", true).putArray("InstanceGroups")
                .addObject().put("Name", "primary").put("InstanceRole", "MASTER").put("InstanceType", "m5.xlarge")
                .put("InstanceCount", 1);
        clusterId = emr("RunJobFlow", request.toString()).then().statusCode(200).extract().jsonPath()
                .getString("JobFlowId");
        cleanup.add(() -> emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}"));
    }

    /** Runs one step on the cluster and returns the state it ended in, its stderr and Floci's log if it failed. */
    private String step(String... args) {
        String stepId = EmrRequests.addStep(clusterId, args[0], "command-runner.jar", args);
        String state = awaitStep(clusterId, stepId).jsonPath().getString("Step.Status.State");
        if (!"COMPLETED".equals(state)) {
            return state + "\n" + stepLog(stepId) + "\n" + flociLog();
        }
        return state;
    }

    private String clusterState() {
        return emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}").then().statusCode(200).extract()
                .jsonPath().getString("Cluster.Status.State");
    }

    /** The networks Floci's container is on that are the cluster's: their names carry its id. */
    private List<String> clusterNetworks() {
        return networks().stream().filter(name -> name.contains(clusterId)).toList();
    }

    private List<String> networks() {
        InspectContainerResponse floci = docker.inspectContainerCmd(flociContainer).exec();
        return List.copyOf(floci.getNetworkSettings().getNetworks().keySet());
    }

    private void awaitHealthy(int hostPort) {
        HttpClient http = HttpClient.newHttpClient();
        URI health = URI.create("http://localhost:" + hostPort + "/_floci/health");
        await("Floci's container to answer its health check")
                .atMost(FLOCI_START)
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> http.send(HttpRequest.newBuilder(health).build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode() == 200);
    }

    // Floci's container is stopped as a user stops it, which removes the containers and networks it made.
    private void stopFloci() {
        try {
            docker.stopContainerCmd(flociContainer).withTimeout(60).exec();
        } catch (NotFoundException | NotModifiedException expected) {
            // Gone, or stopped already: nothing to stop.
        } finally {
            try {
                docker.removeContainerCmd(flociContainer).withForce(true).withRemoveVolumes(true).exec();
            } catch (NotFoundException expected) {
                // Gone already.
            }
        }
    }

    // The cluster's containers and networks a stop left, as one that timed out or a Floci that crashed leaves them,
    // removed by name after Floci's own container.
    private void removeClusterLeftovers() {
        if (clusterId == null) {
            return;
        }
        for (Container container : docker.listContainersCmd().withShowAll(true)
                .withNameFilter(List.of(clusterId)).exec()) {
            try {
                docker.removeContainerCmd(container.getId()).withForce(true).exec();
            } catch (NotFoundException expected) {
                // Removed meanwhile.
            }
        }
        docker.listNetworksCmd().exec().stream()
                .filter(network -> network.getName().contains(clusterId))
                .forEach(network -> {
                    try {
                        docker.removeNetworkCmd(network.getId()).exec();
                    } catch (NotFoundException expected) {
                        // Removed meanwhile.
                    }
                });
    }

    private String stepLog(String stepId) {
        try {
            return S3TestClients.gunzipped(s3, bucket, "logs/" + clusterId + "/steps/" + stepId + "/stderr.gz");
        } catch (RuntimeException e) {
            return "(no step log: " + e.getMessage() + ")";
        }
    }

    /** The last lines of Floci's container's log, for a failure's message. */
    private String flociLog() {
        StringBuilder log = new StringBuilder();
        try {
            docker.logContainerCmd(flociContainer).withStdOut(true).withStdErr(true).withTail(200)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            log.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    })
                    .awaitCompletion(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return log.toString();
    }

    private void put(String key, String text) {
        s3.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString(text));
    }

    private String s3Uri(String key) {
        return "s3://" + bucket + "/" + key;
    }

    /** The objects under {@code prefix}, in key order, one after the other. */
    private String objects(String prefix) {
        StringBuilder text = new StringBuilder();
        s3.listObjectsV2(request -> request.bucket(bucket).prefix(prefix)).contents().forEach(object ->
                text.append(s3.getObjectAsBytes(request -> request.bucket(bucket).key(object.key())).asUtf8String()));
        return text.toString();
    }
}
