package io.floci.aws.services.emrcontainers;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CloudFormationSettingsProfile;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.EksRequests;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.S3TestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EMR on EKS job runs, run for real in the {@code docker} runner mode: each runs its Spark job in a container of its
 * release's base image from Amazon ECR Public, on Floci's S3, and ends as its driver does.
 *
 * <p>A nightly check: the release's base image is about 5 GB.
 */
@QuarkusTest
@TestProfile(CloudFormationSettingsProfile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
// One instance, so that the image is pulled once for the class.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// Bounded waits: a job run of up to five minutes in each method, past the default two, and the cancelled run's five
// minutes to start besides. With the image's pull of up to 21 and each method's setup of up to the default two, 58
// minutes, which the Nightly checks' docker-runner group counts.
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class EmrContainersSparkNightlyIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-job";
    private static final String RELEASE = "emr-7.5.0-latest";
    // The release's base image, which the runner's default template names.
    private static final String BASE_IMAGE = "public.ecr.aws/emr-on-eks/spark/emr-7.5.0:latest";
    private static final Set<String> FINISHED = Set.of("COMPLETED", "FAILED", "CANCELLED");
    private static final Duration JOB_RUN_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    DockerClient docker;

    private S3Client s3;
    private String bucket;
    private String virtualClusterId;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // The base image's pull alone may take its twenty minutes.
    @BeforeAll
    @Timeout(value = 21, unit = TimeUnit.MINUTES)
    void pullImage() throws InterruptedException {
        // Enabled, the check needs Docker: without a daemon it fails rather than pass having run nothing.
        assertTrue(DockerAvailability.isAvailable(), "the nightly check needs a Docker daemon");
        docker.pullImageCmd(BASE_IMAGE).exec(new PullImageResultCallback()).awaitCompletion(20, TimeUnit.MINUTES);
    }

    @BeforeEach
    void setUp() {
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), ACCOUNT, Region.US_EAST_1);
        cleanup.register(s3::close);
        bucket = "emr-eks-" + UUID.randomUUID().toString().substring(0, 8);
        s3.createBucket(request -> request.bucket(bucket));
        cleanup.register(() -> S3TestClients.deleteBucket(s3, bucket));
        put("write.py", """
                import sys
                from pyspark.sql import SparkSession
                spark = SparkSession.builder.getOrCreate()
                spark.createDataFrame([(sys.argv[2],)], ["value"]).coalesce(1).write.text(sys.argv[1])
                """);
        put("fail.py", """
                import sys
                sys.exit(3)
                """);
        put("sleep.py", """
                import time
                time.sleep(600)
                """);
        put("query.sql", """
                INSERT OVERWRITE DIRECTORY 's3://%s/sql-out/' USING text SELECT 'from-sql' AS value;
                """.formatted(bucket));
        String eksCluster = "emr-" + UUID.randomUUID().toString().substring(0, 8);
        EksRequests.eks(ACCOUNT, REGION)
                .body("""
                        {"name": "%s", "roleArn": "arn:aws:iam::000000000000:role/eks-role", "version": "1.29"}
                        """.formatted(eksCluster))
                .post("/clusters")
                .then().statusCode(200);
        cleanup.register(() -> EksRequests.eks(ACCOUNT, REGION).delete("/clusters/" + eksCluster)
                .then().statusCode(200));
        virtualClusterId = emr().body("""
                        {"name": "nightly", "clientToken": "%s",
                         "containerProvider": {"type": "EKS", "id": "%s", "info": {"eksInfo": {"namespace": "spark"}}}}
                        """.formatted(UUID.randomUUID(), eksCluster))
                .post("/virtualclusters")
                .then().statusCode(200)
                .extract().path("id");
        String created = virtualClusterId;
        cleanup.register(() -> emr().delete("/virtualclusters/" + created).then().statusCode(200));
    }

    @Test
    void sparkSubmitJobRunWritesToS3AndCompletes() {
        String id = startJobRun("""
                {"sparkSubmitJobDriver": {"entryPoint": "s3://%s/write.py",
                  "entryPointArguments": ["s3://%s/out/", "from-submit"],
                  "sparkSubmitParameters": "--conf spark.sql.shuffle.partitions=1"}}
                """.formatted(bucket, bucket));

        Response run = awaitFinished(id);

        assertEquals("COMPLETED", run.jsonPath().getString("jobRun.state"), run.asString());
        assertEquals("from-submit\n", S3TestClients.partFiles(s3, bucket, "out/"));
    }

    @Test
    void sparkSqlJobRunRunsItsFileAndCompletes() {
        String id = startJobRun("""
                {"sparkSqlJobDriver": {"entryPoint": "s3://%s/query.sql",
                  "sparkSqlParameters": "--conf spark.sql.shuffle.partitions=1"}}
                """.formatted(bucket));

        Response run = awaitFinished(id);

        assertEquals("COMPLETED", run.jsonPath().getString("jobRun.state"), run.asString());
        assertEquals("from-sql\n", S3TestClients.partFiles(s3, bucket, "sql-out/"));
    }

    @Test
    void jobRunWhoseDriverExitsWithAnErrorFails() {
        String id = startJobRun("""
                {"sparkSubmitJobDriver": {"entryPoint": "s3://%s/fail.py"}}
                """.formatted(bucket));

        Response run = awaitFinished(id);

        assertEquals("FAILED", run.jsonPath().getString("jobRun.state"), run.asString());
        assertEquals("USER_ERROR", run.jsonPath().getString("jobRun.failureReason"));
    }

    @Test
    @Timeout(value = 11, unit = TimeUnit.MINUTES)
    void cancelledJobRunStopsItsDriverAndIsCancelled() {
        String id = startJobRun("""
                {"sparkSubmitJobDriver": {"entryPoint": "s3://%s/sleep.py"}}
                """.formatted(bucket));
        await("job run " + id + " to run").atMost(JOB_RUN_TIMEOUT).pollInterval(POLL_INTERVAL)
                .until(() -> describe(id).jsonPath().getString("jobRun.state"), "RUNNING"::equals);

        emr().delete("/virtualclusters/" + virtualClusterId + "/jobruns/" + id).then().statusCode(200);

        Response run = awaitFinished(id);
        assertEquals("CANCELLED", run.jsonPath().getString("jobRun.state"), run.asString());
    }

    private void put(String key, String body) {
        s3.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString(body));
    }

    private static RequestSpecification emr() {
        return given().header("Authorization", EnforcementFixtures.auth(ACCOUNT, REGION, "emr-containers"))
                .contentType("application/json")
                .urlEncodingEnabled(false);
    }

    private String startJobRun(String jobDriver) {
        return emr().body("""
                        {"name": "nightly", "clientToken": "%s", "executionRoleArn": "%s", "releaseLabel": "%s",
                         "jobDriver": %s}
                        """.formatted(UUID.randomUUID(), ROLE, RELEASE, jobDriver))
                .post("/virtualclusters/" + virtualClusterId + "/jobruns")
                .then().statusCode(200)
                .extract().path("id");
    }

    private Response describe(String id) {
        return emr().get("/virtualclusters/" + virtualClusterId + "/jobruns/" + id)
                .then().statusCode(200).extract().response();
    }

    private Response awaitFinished(String id) {
        return await("job run " + id + " to end").atMost(JOB_RUN_TIMEOUT).pollInterval(POLL_INTERVAL)
                .until(() -> describe(id), run -> FINISHED.contains(run.jsonPath().getString("jobRun.state")));
    }
}
