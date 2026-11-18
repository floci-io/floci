package io.floci.aws.services.emr;

import io.floci.aws.services.emrserverless.EmrServerlessSparkRunner;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.EmrRequests;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static io.floci.aws.testing.EmrRequests.awaitStep;
import static io.floci.aws.testing.EmrRequests.emr;
import static io.floci.aws.testing.EmrRequests.terminateClusterAndAwaitCleanup;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * On-cluster application UIs open for real: on an emr-7.5.0 cluster no step has run on yet, the ResourceManager's link
 * starts the primary node and is ready once the node is, then opens the ResourceManager through Floci on a host of its
 * own; after a MapReduce step, the ResourceManager's answer links to the job on the JobHistory server's own host, which
 * opens it, as the JobHistory server's link does.
 *
 * <p>A nightly check: the cluster's image, its HDFS and YARN, and a MapReduce job take minutes and gigabytes.
 */
@QuarkusTest
@TestProfile(EmrAppUiNightlyIntegrationTest.Profile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
// Bounded waits: the cluster's image of up to twenty minutes, its HDFS and YARN, and a MapReduce step; the Nightly
// checks job's timeout counts the 30.
@Timeout(value = 30, unit = TimeUnit.MINUTES)
class EmrAppUiNightlyIntegrationTest {

    /** Clusters run their steps in containers, and Floci listens on a fixed port, its links'. */
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

    private static final int PORT = 4598;
    private static final String EXAMPLES = "/usr/lib/hadoop-mapreduce/hadoop-mapreduce-examples.jar";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    EmrService emrService;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void theResourceManagerAndTheJobHistoryServerOpenThroughTheirLinks() throws InterruptedException {
        // Enabled, the check needs Docker: without a daemon it fails rather than pass having run nothing.
        assertTrue(DockerAvailability.isAvailable(), "the nightly check needs a Docker daemon");
        String clusterId = emr("RunJobFlow", "{\"Name\":\"app-uis\",\"ReleaseLabel\":\"emr-7.5.0\","
                + "\"Applications\":[{\"Name\":\"Hadoop\"},{\"Name\":\"Spark\"}],"
                + "\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,\"InstanceGroups\":[{\"Name\":\"primary\","
                + "\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}]}}")
                .then().statusCode(200).extract().jsonPath().getString("JobFlowId");
        cleanup.register(() -> terminateClusterAndAwaitCleanup(clusterId, emrService));
        await(() -> emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}").jsonPath()
                .getString("Cluster.Status.State").equals("WAITING"), Duration.ofMinutes(5));

        // No step has run: the link starts the primary node, and is ready once the node is.
        assertFalse(link(clusterId, "ResourceManager", null).getBoolean("PresignedURLReady"));
        String resourceManager = readyLink(clusterId, "ResourceManager", null);
        Response cluster = open(resourceManager);
        assertEquals(200, cluster.statusCode(), cluster.asString());
        assertTrue(cluster.asString().contains("All Applications"), cluster.asString());

        String step = EmrRequests.addStep(clusterId, "pi", "command-runner.jar", "hadoop", "jar", EXAMPLES, "pi",
                "1", "10");
        assertEquals("COMPLETED", awaitStep(clusterId, step).jsonPath().getString("Step.Status.State"));

        // The ResourceManager's REST answer, through its link's host: the links it holds to the node's interfaces are
        // their own hosts', under the same link.
        URI linked = URI.create(resourceManager);
        String secret = linked.getHost().substring(0, 26);
        String origin = "http://" + secret + "-%d.emrappui.localhost:" + PORT;
        Response answer = open(linked.getScheme() + "://" + linked.getRawAuthority() + "/ws/v1/cluster/apps");
        JsonPath apps = answer.jsonPath();
        String job = "apps.app.find { it.applicationType == 'MAPREDUCE' }";
        String applicationId = apps.getString(job + ".id");
        String jobPath = "/jobhistory/job/" + applicationId.replace("application_", "job_");
        // The job's application master's logs, on the NodeManager of the primary node, which works.
        String amLogs = apps.getString(job + ".amContainerLogs");
        assertTrue(amLogs.startsWith(String.format(origin, EmrClusterStorage.NODE_MANAGER_WEB_PORT)), amLogs);
        Response logs = open(amLogs);
        assertEquals(200, logs.statusCode(), logs.asString());
        assertTrue(logs.asString().contains("syslog"), logs.asString());
        // Its tracking URL, the ResourceManager's proxy, sends the browser on to the job's page on the JobHistory
        // server's own host.
        String tracking = apps.getString(job + ".trackingUrl");
        assertEquals(String.format(origin, EmrClusterStorage.RESOURCE_MANAGER_WEB_PORT) + "/proxy/" + applicationId
                + "/", tracking);
        Response proxied = open(tracking);
        assertTrue(proxied.statusCode() / 100 == 3, proxied.statusCode() + " " + proxied.asString());
        String history = proxied.getHeader("Location");
        assertTrue(history.startsWith(String.format(origin, EmrServerlessSparkRunner.JOB_HISTORY_WEB_PORT) + jobPath),
                history);
        Response page = open(history);
        assertEquals(200, page.statusCode(), page.asString());
        assertTrue(page.asString().contains("QuasiMonteCarlo"), page.asString());

        // The JobHistory server's own link lands on the job.
        String jobLink = readyLink(clusterId, "JobHistoryServer", applicationId);
        assertEquals(jobPath, URI.create(jobLink).getPath());
        assertTrue(open(jobLink).asString().contains("QuasiMonteCarlo"));
    }

    private static JsonPath link(String clusterId, String type, String applicationId) {
        return emr("GetOnClusterAppUIPresignedURL", "{\"ClusterId\":\"" + clusterId + "\",\"OnClusterAppUIType\":\""
                + type + "\"" + (applicationId != null ? ",\"ApplicationId\":\"" + applicationId + "\"" : "") + "}")
                .then().statusCode(200).extract().jsonPath();
    }

    // The link once it is ready: the primary node's image, HDFS and YARN start first.
    private static String readyLink(String clusterId, String type, String applicationId) {
        String[] url = new String[1];
        await(() -> {
            JsonPath answer = link(clusterId, type, applicationId);
            url[0] = answer.getString("PresignedURL");
            return answer.getBoolean("PresignedURLReady");
        }, Duration.ofMinutes(20));
        return url[0];
    }

    // A browser's request to the link: to Floci, under the link's host.
    private static Response open(String url) {
        URI uri = URI.create(url);
        return given().header("Host", uri.getRawAuthority()).header("Accept", "application/json, text/html")
                .redirects().follow(false)
                .get("http://localhost:" + PORT + uri.getRawPath()
                        + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : ""));
    }

    private static void await(BooleanSupplier condition, Duration limit) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                fail("not reached within " + limit);
            }
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));
        }
    }
}
