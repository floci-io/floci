package io.floci.aws.services.emrserverless;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.S3TestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static io.floci.aws.testing.EmrServerlessRequests.deleteApplication;
import static io.floci.aws.testing.EmrServerlessRequests.emrServerless;
import static io.floci.aws.testing.EmrServerlessRequests.startJobRun;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * EMR Serverless dashboards open for real: GetDashboardForJobRun's link, while a PySpark run runs in the release's
 * image, opens its driver's Spark UI through Floci on a host of its own, its pages and its REST API naming the run's
 * application, and once the run is cancelled the link reaches nothing and a new one opens Floci's page;
 * GetResourceDashboard's link, while a session is idle, opens its Spark Connect server's Spark UI.
 *
 * <p>A nightly check: the release's image is 3.7 GB, and the run and the session each start a JVM. It shares the Spark
 * Connect check's profile, so the two run on one Floci.
 */
@QuarkusTest
@TestProfile(EmrServerlessSparkConnectNightlyIntegrationTest.Profile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
// Bounded waits: the image, the Spark Connect check's, which the job prefetches, or its pull, then the run's start and
// end and the session's, a minute or two; the Nightly checks job's timeout counts the 15.
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class EmrServerlessDashboardNightlyIntegrationTest {

    private static final int PORT = 4597;
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String RELEASE = "emr-7.13.0";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-serverless-dashboard";
    private static final String NAME = "floci-dashboard";
    // A run that does some work, under a name its UI shows, then waits to be cancelled.
    private static final String JOB = """
            import time
            from pyspark.sql import SparkSession
            spark = SparkSession.builder.appName("%s").getOrCreate()
            print(spark.range(1000).selectExpr("sum(id)").collect())
            time.sleep(1200)
            """.formatted(NAME);

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aRunningRunsAndAnIdleSessionsDashboardLinksOpenTheirDriversSparkUi() throws InterruptedException {
        // Enabled, the check needs Docker: without a daemon it fails rather than pass having run nothing.
        assertTrue(DockerAvailability.isAvailable(), "the nightly check needs a Docker daemon");
        S3Client s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), ACCOUNT, Region.US_EAST_1);
        cleanup.register(s3::close);
        String bucket = "emr-dashboard-" + UUID.randomUUID().toString().substring(0, 8);
        s3.createBucket(request -> request.bucket(bucket));
        cleanup.register(() -> S3TestClients.deleteBucket(s3, bucket));
        s3.putObject(request -> request.bucket(bucket).key("job.py"), RequestBody.fromString(JOB));
        String applicationId = emrServerless(REGION).body("""
                        {"name": "dashboards", "releaseLabel": "%s", "type": "SPARK", "clientToken": "%s",
                         "interactiveConfiguration": {"sessionEnabled": true}}
                        """.formatted(RELEASE, UUID.randomUUID()))
                .when().post("/applications")
                .then().statusCode(200).extract().path("applicationId");
        cleanup.register(() -> deleteApplication(REGION, applicationId));
        String jobRunId = startJobRun(REGION, applicationId, """
                {"clientToken": "%s", "executionRoleArn": "%s",
                 "jobDriver": {"sparkSubmit": {"entryPoint": "s3://%s/job.py"}}}
                """.formatted(UUID.randomUUID(), ROLE, bucket));
        String run = "/applications/" + applicationId + "/jobruns/" + jobRunId;
        cleanup.register(() -> emrServerless(REGION).when().delete(run));
        await(() -> emrServerless(REGION).when().get(run).then().statusCode(200).extract().path("jobRun.state"),
                "RUNNING"::equals, Duration.ofMinutes(10), "job run " + jobRunId);

        String live = emrServerless(REGION).when().get(run + "/dashboard").then().statusCode(200)
                .extract().path("url");
        assertTrue(URI.create(live).getAuthority().matches("[a-z2-7]{26}-4040\\.emr-serverless-ui\\.localhost:"
                + PORT), live);
        // The driver's UI answers once its JVM has started it, first with a page saying Spark is starting up.
        Response jobs = awaitPage(live + "jobs/", "Spark Jobs");
        assertTrue(jobs.asString().contains("Spark Jobs") && jobs.asString().contains(NAME), jobs.asString());
        Response applications = open(live + "api/v1/applications");
        assertEquals(200, applications.statusCode(), applications.asString());
        assertEquals(List.of(NAME), applications.jsonPath().getList("name"));

        emrServerless(REGION).when().delete(run).then().statusCode(200);
        await(() -> emrServerless(REGION).when().get(run).then().statusCode(200).extract().path("jobRun.state"),
                "CANCELLED"::equals, Duration.ofMinutes(2), "job run " + jobRunId);
        Response ended = open(live + "jobs/");
        assertEquals(404, ended.statusCode(), ended.asString());
        assertTrue(ended.asString().contains("is no longer running"), ended.asString());
        String page = emrServerless(REGION).when().get(run + "/dashboard").then().statusCode(200)
                .extract().path("url");
        assertTrue(page.startsWith("http://localhost:" + PORT + EmrServerlessDashboards.PATH + "?"), page);

        String sessionId = emrServerless(REGION).body("""
                        {"clientToken": "%s", "executionRoleArn": "%s", "idleTimeoutMinutes": 30}
                        """.formatted(UUID.randomUUID(), ROLE))
                .when().post("/applications/" + applicationId + "/sessions")
                .then().statusCode(200).extract().path("sessionId");
        String session = "/applications/" + applicationId + "/sessions/" + sessionId;
        cleanup.register(() -> emrServerless(REGION).when().delete(session));
        await(() -> emrServerless(REGION).when().get(session).then().statusCode(200).extract()
                .path("session.state"), "IDLE"::equals, Duration.ofMinutes(5), "session " + sessionId);
        String sessionUi = emrServerless(REGION).queryParam("resourceId", sessionId)
                .queryParam("resourceType", "SESSION")
                .when().get("/applications/" + applicationId + "/dashboard")
                .then().statusCode(200).extract().path("url");
        Response sessionJobs = awaitPage(sessionUi + "jobs/", "Spark Jobs");
        assertTrue(sessionJobs.asString().contains("Spark Jobs"), sessionJobs.asString());

        emrServerless(REGION).when().delete(session).then().statusCode(200);
        await(() -> emrServerless(REGION).when().get(session).then().statusCode(200).extract()
                .path("session.state"), "TERMINATED"::equals, Duration.ofMinutes(2), "session " + sessionId);
        assertEquals(404, open(sessionUi + "jobs/").statusCode());
    }

    // A page of a driver's UI once it shows what is asked for: its link's host answers 503 until the UI listens, and
    // the UI a page saying Spark is starting up until its Spark has.
    private static Response awaitPage(String url, String showing) {
        Response[] page = new Response[1];
        await(() -> {
            page[0] = open(url);
            return page[0].statusCode() + " " + page[0].asString();
        }, now -> now.startsWith("200 ") && now.contains(showing), Duration.ofMinutes(3), url);
        return page[0];
    }

    // A browser's request to the link: to Floci, under the link's host.
    private static Response open(String url) {
        URI uri = URI.create(url);
        return given().header("Host", uri.getRawAuthority()).header("Accept", "application/json, text/html")
                .redirects().follow(false)
                .get("http://localhost:" + PORT + uri.getRawPath()
                        + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : ""));
    }

    private static void await(Supplier<String> current, Predicate<String> reached, Duration limit, String what) {
        long deadline = System.nanoTime() + limit.toNanos();
        String now = current.get();
        while (!reached.test(now)) {
            if (List.of("FAILED", "TERMINATED").contains(now) || System.nanoTime() - deadline > 0) {
                fail(what + " is " + now + " after " + limit);
            }
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));
            now = current.get();
        }
    }
}
