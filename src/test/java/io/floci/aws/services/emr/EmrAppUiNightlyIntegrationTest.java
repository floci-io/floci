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
import java.util.Set;
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
 * opens it, as the JobHistory server's link does; after a Spark step, whose event logs are in the cluster's HDFS, the
 * ResourceManager's answer links to the application on the Spark History Server's own host, which lists and opens it,
 * as the Spark History Server's link does; after a Hive step, its Tez DAG is in the YARN timeline server, and the Tez
 * UI's page names the timeline server and the ResourceManager on their own hosts, which answer its page's requests. A
 * persistent application UI opens the Spark History Server while the cluster runs, and, once it has ended and its
 * storage has gone, its kept history: the Spark application, the DAG and the Tez UI's page.
 *
 * <p>A nightly check: the cluster's image, its HDFS and YARN, and a MapReduce job and a Spark one take minutes and
 * gigabytes.
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
    private static final String SPARK_EXAMPLES = "/usr/lib/spark/examples/jars/spark-examples.jar";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    EmrService emrService;

    @Inject
    EmrClusterStorage clusterStorage;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void theResourceManagerAndTheJobHistoryServerOpenThroughTheirLinks() throws InterruptedException {
        // Enabled, the check needs Docker: without a daemon it fails rather than pass having run nothing.
        assertTrue(DockerAvailability.isAvailable(), "the nightly check needs a Docker daemon");
        String clusterId = emr("RunJobFlow", "{\"Name\":\"app-uis\",\"ReleaseLabel\":\"emr-7.5.0\","
                + "\"Applications\":[{\"Name\":\"Hadoop\"},{\"Name\":\"Spark\"},{\"Name\":\"Hive\"},"
                + "{\"Name\":\"Tez\"}],"
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

        String sparkStep = EmrRequests.addStep(clusterId, "spark-pi", "command-runner.jar", "spark-submit",
                "--class", "org.apache.spark.examples.SparkPi", SPARK_EXAMPLES, "10");
        assertEquals("COMPLETED", awaitStep(clusterId, sparkStep).jsonPath().getString("Step.Status.State"));
        // Its tracking URL, the ResourceManager's proxy, sends the browser on, once it has ended, to its page on the
        // Spark History Server's own host.
        JsonPath sparkApps = open(linked.getScheme() + "://" + linked.getRawAuthority() + "/ws/v1/cluster/apps")
                .jsonPath();
        String spark = "apps.app.find { it.applicationType == 'SPARK' }";
        String sparkId = sparkApps.getString(spark + ".id");
        String sparkTracking = sparkApps.getString(spark + ".trackingUrl");
        assertEquals(String.format(origin, EmrClusterStorage.RESOURCE_MANAGER_WEB_PORT) + "/proxy/" + sparkId + "/",
                sparkTracking);
        Response sparkProxied = open(sparkTracking);
        assertTrue(sparkProxied.statusCode() / 100 == 3, sparkProxied.statusCode() + " " + sparkProxied.asString());
        String historyOrigin = String.format(origin, EmrServerlessSparkRunner.SPARK_HISTORY_WEB_PORT);
        assertTrue(sparkProxied.getHeader("Location").startsWith(historyOrigin + "/history/" + sparkId),
                sparkProxied.getHeader("Location"));
        // The Spark History Server's own link lists it from its event logs, read as they come, and lands on it, once
        // the server has read its finished log: one it listed while the application ran is renamed as it ends, and is
        // found again at the server's next look.
        String historyLink = readyLink(clusterId, "SparkHistoryServer", null);
        await(() -> open(historyLink.replaceFirst("/$", "") + "/api/v1/applications?status=completed").asString()
                .contains(sparkId), Duration.ofMinutes(2));
        String applicationLink = readyLink(clusterId, "SparkHistoryServer", sparkId);
        assertEquals("/history/" + sparkId + "/", URI.create(applicationLink).getPath());
        Response application = follow(applicationLink);
        assertEquals(200, application.statusCode(), application.asString());
        assertTrue(application.asString().contains("Spark Pi"), application.asString());
        assertTrue(follow(sparkTracking).asString().contains("Spark Pi"));

        String hiveStep = EmrRequests.addStep(clusterId, "hive", "command-runner.jar", "hive", "-e",
                "CREATE TABLE ui_rows AS SELECT 1 AS x");
        assertEquals("COMPLETED", awaitStep(clusterId, hiveStep).jsonPath().getString("Step.Status.State"));
        // The timeline server holds the query's Tez DAG, and its application history.
        String timelineLink = readyLink(clusterId, "YarnTimelineService", null);
        URI timeline = URI.create(timelineLink);
        assertEquals("/applicationhistory", timeline.getPath());
        Response applications = open(timelineLink);
        assertEquals(200, applications.statusCode(), applications.asString());
        // The ResourceManager published the query's application to it.
        Response published = open(timeline.getScheme() + "://" + timeline.getRawAuthority()
                + "/ws/v1/applicationhistory/apps");
        assertTrue(published.jsonPath().getList("app.findAll { it.type == 'TEZ' }").size() > 0,
                published.asString());
        String dags = timeline.getScheme() + "://" + timeline.getRawAuthority() + "/ws/v1/timeline/TEZ_DAG_ID";
        Response dagEntities = open(dags);
        assertEquals(200, dagEntities.statusCode(), dagEntities.asString());
        assertFalse(dagEntities.jsonPath().getList("entities").isEmpty(), dagEntities.asString());
        // The Tez UI's page, whose configuration names the timeline server and the ResourceManager on their own hosts
        // under the same link, which answer a request from its page's host.
        String tezLink = readyLink(clusterId, "TezUI", null);
        URI tez = URI.create(tezLink);
        Response tezPage = open(tezLink);
        assertEquals(200, tezPage.statusCode(), tezPage.asString());
        assertTrue(tezPage.asString().contains("<title>Tez UI</title>"), tezPage.asString());
        String config = open(tez.getScheme() + "://" + tez.getRawAuthority() + "/tez-ui/config/configs.js").asString();
        // Each link has a secret of its own.
        String tezHosts = "http://" + tez.getHost().substring(0, 26) + "-%d.emrappui.localhost:" + PORT;
        assertTrue(config.contains(String.format(tezHosts, EmrClusterStorage.TIMELINE_WEB_PORT)), config);
        assertTrue(config.contains(String.format(tezHosts, EmrClusterStorage.RESOURCE_MANAGER_WEB_PORT)), config);
        String tezOrigin = tez.getScheme() + "://" + tez.getRawAuthority();
        Response crossOrigin = given().header("Host", timeline.getRawAuthority()).header("Origin", tezOrigin)
                .get("http://localhost:" + PORT + "/ws/v1/timeline/TEZ_DAG_ID");
        assertEquals(200, crossOrigin.statusCode(), crossOrigin.asString());
        String allowed = crossOrigin.getHeader("Access-Control-Allow-Origin");
        assertTrue(tezOrigin.equals(allowed) || "*".equals(allowed), String.valueOf(allowed));

        // A persistent application UI: the cluster's own while it runs.
        String appUi = emr("CreatePersistentAppUI", "{\"TargetResourceArn\":\"arn:aws:elasticmapreduce:us-east-1:"
                + "000000000000:cluster/" + clusterId + "\"}").then().statusCode(200).extract().jsonPath()
                .getString("PersistentAppUIId");
        JsonPath live = persistentLink(appUi, "SHS");
        assertTrue(live.getBoolean("PresignedURLReady"), live.prettify());
        assertEquals(200, open(live.getString("PresignedURL")).statusCode());
        // Once the cluster has ended and its storage has gone, its kept history, served by a container of its own.
        cleanup.register(() -> clusterStorage.removeHistoriesBut(Set.of()));
        terminateClusterAndAwaitCleanup(clusterId, emrService);
        String keptHistory = readyPersistentLink(appUi, "SHS");
        await(() -> open(keptHistory.replaceFirst("/$", "") + "/api/v1/applications").asString().contains(sparkId),
                Duration.ofMinutes(2));
        URI keptTimeline = URI.create(readyPersistentLink(appUi, "YTS"));
        Response keptDags = open(keptTimeline.getScheme() + "://" + keptTimeline.getRawAuthority()
                + "/ws/v1/timeline/TEZ_DAG_ID");
        assertFalse(keptDags.jsonPath().getList("entities").isEmpty(), keptDags.asString());
        URI keptTez = URI.create(readyPersistentLink(appUi, "TEZ"));
        assertTrue(open(keptTez.toString()).asString().contains("<title>Tez UI</title>"));
        String keptConfig = open(keptTez.getScheme() + "://" + keptTez.getRawAuthority()
                + "/tez-ui/config/configs.js").asString();
        String keptTimelineHost = "http://" + keptTez.getHost().substring(0, 26) + "-8188.emrappui.localhost:" + PORT;
        assertTrue(keptConfig.contains(keptTimelineHost), keptConfig);
        // With no ResourceManager there, the Tez UI asks the timeline server in its place, which answers, rather than
        // the primary node that has gone.
        assertFalse(keptConfig.contains("8088"), keptConfig);
        Response keptRm = open(keptTimelineHost + "/ws/v1/cluster/apps");
        assertTrue(keptRm.statusCode() < 500, keptRm.statusCode() + " " + keptRm.asString());
    }

    private static JsonPath persistentLink(String appUi, String type) {
        return emr("GetPersistentAppUIPresignedURL", "{\"PersistentAppUIId\":\"" + appUi
                + "\",\"PersistentAppUIType\":\"" + type + "\"}").then().statusCode(200).extract().jsonPath();
    }

    // The link once it is ready: the history's container starts first.
    private static String readyPersistentLink(String appUi, String type) {
        String[] url = new String[1];
        await(() -> {
            JsonPath answer = persistentLink(appUi, type);
            url[0] = answer.getString("PresignedURL");
            return answer.getBoolean("PresignedURLReady");
        }, Duration.ofMinutes(10));
        return url[0];
    }

    // A browser's request to the link, following the redirects it is sent, each to a link's host.
    private static Response follow(String url) {
        String at = url;
        Response response = open(at);
        for (int hop = 0; hop < 5 && response.statusCode() / 100 == 3; hop++) {
            at = URI.create(at).resolve(response.getHeader("Location")).toString();
            response = open(at);
        }
        return response;
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
