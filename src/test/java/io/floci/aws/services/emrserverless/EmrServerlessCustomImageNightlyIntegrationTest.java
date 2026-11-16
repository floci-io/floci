package io.floci.aws.services.emrserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.PushResponseItem;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.S3TestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.testing.EmrServerlessRequests.awaitJobRun;
import static io.floci.aws.testing.EmrServerlessRequests.deleteApplication;
import static io.floci.aws.testing.EmrServerlessRequests.emrServerless;
import static io.floci.aws.testing.EmrServerlessRequests.startJobRun;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An EMR Serverless application on a custom image in Floci's ECR, run for real: the image is the release's base
 * image with a build marker, pushed to Floci's registry, and the job runs pull it from there. Then a second build is
 * pushed under the same tag. An application that resolves its image as it starts, the default on EMR 7.13 and earlier,
 * keeps the first build until it starts again ("Custom images are resolved to a digest at application start; that
 * digest is used for all jobs until the application restarts"); one that resolves it for each run takes the second at
 * once.
 *
 * <p>A nightly check: the first push copies the whole 3.5 GB base image into the registry.
 */
@QuarkusTest
@TestProfile(EmrServerlessCustomImageNightlyIntegrationTest.Profile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
class EmrServerlessCustomImageNightlyIntegrationTest {

    /**
     * Job runs in containers, and Floci on a fixed port: the registry address the runner hands the Docker daemon for an
     * ECR image is {@code localhost:<floci.port>}, so it has to be the port Floci listens on.
     */
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

    private static final int PORT = 4599;
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String RELEASE = "emr-7.5.0";
    private static final String TAG = "7.5.0";
    // The release's EMR Serverless image, which the runner's default template names and a custom image is built on.
    private static final String BASE_IMAGE = "public.ecr.aws/emr-serverless/spark/emr-7.5.0:latest";
    private static final ObjectMapper JSON = new ObjectMapper();

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    DockerClient docker;

    private S3Client s3;
    private EcrClient ecr;
    private String bucket;
    private String repository;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void setUp() throws InterruptedException {
        // Enabled, the check needs Docker: without a daemon it fails rather than pass having run nothing.
        assertTrue(DockerAvailability.isAvailable(), "the nightly check needs a Docker daemon");
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), ACCOUNT, Region.US_EAST_1);
        cleanup.register(s3::close);
        bucket = "emr-image-" + UUID.randomUUID().toString().substring(0, 8);
        s3.createBucket(request -> request.bucket(bucket));
        cleanup.register(() -> S3TestClients.deleteBucket(s3, bucket));
        s3.putObject(request -> request.bucket(bucket).key("job.py"), RequestBody.fromString("""
                import os, sys
                from pyspark.sql import SparkSession
                spark = SparkSession.builder.getOrCreate()
                spark.createDataFrame([(os.environ.get("FLOCI_IMAGE_BUILD", "none"),)], ["value"]) \\
                    .coalesce(1).write.text(sys.argv[1])
                """));
        ecr = AwsTestClients.create(EcrClient.builder(), ACCOUNT, Region.US_EAST_1);
        cleanup.register(ecr::close);
        repository = "emr-custom-" + UUID.randomUUID().toString().substring(0, 8);
        ecr.createRepository(request -> request.repositoryName(repository));
        cleanup.register(() -> ecr.deleteRepository(request -> request.repositoryName(repository).force(true)));
        docker.pullImageCmd(BASE_IMAGE).exec(new PullImageResultCallback()).awaitCompletion(20, TimeUnit.MINUTES);
    }

    @Test
    void anApplicationKeepsTheImageItResolvedAsItStartedUntilItStartsAgain() throws InterruptedException {
        String first = pushBuild("first");
        String resolvedAtStart = createApplication(null);
        String resolvedPerRun = createApplication(false);

        assertEquals("first", run(resolvedAtStart, "start-1/"));
        assertEquals(first, imageDigest(resolvedAtStart), "the digest the application resolved as it started");
        assertEquals("first", run(resolvedPerRun, "run-1/"));

        String second = pushBuild("second");
        assertNotEquals(first, second, "the second build must have moved the tag");

        assertEquals("first", run(resolvedAtStart, "start-2/"),
                "a started application keeps the image it resolved until it starts again");
        assertEquals("second", run(resolvedPerRun, "run-2/"),
                "an application resolving for each run takes the tag's new image at once");

        emrServerless(REGION).when().post("/applications/" + resolvedAtStart + "/stop").then().statusCode(200);
        emrServerless(REGION).when().post("/applications/" + resolvedAtStart + "/start").then().statusCode(200);

        assertEquals(second, imageDigest(resolvedAtStart), "the digest it resolved as it started again");
        assertEquals("second", run(resolvedAtStart, "start-3/"));
    }

    /**
     * Pushes the base image with {@code FLOCI_IMAGE_BUILD=build} to the repository's tag in Floci's ECR and returns the
     * digest ECR gives it. The local copy goes, so a run pulls the image from Floci's registry.
     */
    private String pushBuild(String build) throws InterruptedException {
        // A container's environment is the image's with its own added, and a commit keeps the container's.
        CreateContainerResponse container = docker.createContainerCmd(BASE_IMAGE)
                .withCmd("true")
                .withEnv("FLOCI_IMAGE_BUILD=" + build)
                .exec();
        String image;
        try {
            image = docker.commitCmd(container.getId()).exec();
        } finally {
            docker.removeContainerCmd(container.getId()).withForce(true).exec();
        }
        String reference = "localhost:" + PORT + "/" + ACCOUNT + "/" + REGION + "/" + repository;
        docker.tagImageCmd(image, reference, TAG).exec();
        try {
            AtomicReference<String> error = new AtomicReference<>();
            boolean finished = docker.pushImageCmd(reference).withTag(TAG)
                    .exec(new ResultCallback.Adapter<PushResponseItem>() {
                        @Override
                        public void onNext(PushResponseItem item) {
                            if (item.isErrorIndicated()) {
                                error.set(String.valueOf(item.getErrorDetail()));
                            }
                        }
                    })
                    .awaitCompletion(30, TimeUnit.MINUTES);
            assertTrue(finished, "the push of build " + build + " must finish");
            assertNull(error.get(), "the push of build " + build + " must succeed");
        } finally {
            removeImage(reference + ":" + TAG);
            removeImage(image);
        }
        return ecr.describeImages(request -> request.repositoryName(repository)
                        .imageIds(id -> id.imageTag(TAG)))
                .imageDetails().getFirst().imageDigest();
    }

    private void removeImage(String image) {
        try {
            docker.removeImageCmd(image).withForce(true).exec();
        } catch (NotFoundException expected) {
            // Removing one reference to an image can take the others with it.
        }
    }

    /**
     * Creates an application on the repository's tag and returns its id; a null
     * {@code applicationLevelDigestResolution} leaves it to the release.
     */
    private String createApplication(Boolean applicationLevelDigestResolution) {
        ObjectNode request = JSON.createObjectNode()
                .put("name", "custom-" + UUID.randomUUID().toString().substring(0, 8))
                .put("releaseLabel", RELEASE)
                .put("type", "SPARK")
                .put("clientToken", UUID.randomUUID().toString());
        ObjectNode image = request.putObject("imageConfiguration")
                .put("imageUri", ACCOUNT + ".dkr.ecr." + REGION + ".amazonaws.com/" + repository + ":" + TAG);
        if (applicationLevelDigestResolution != null) {
            image.put("applicationLevelDigestResolution", applicationLevelDigestResolution);
        }
        String applicationId = emrServerless(REGION).body(request.toString())
                .when().post("/applications")
                .then().statusCode(200).extract().path("applicationId");
        cleanup.register(() -> deleteApplication(REGION, applicationId));
        return applicationId;
    }

    /** Runs the job on the application, which starts it if it has not started, and returns the build marker it saw. */
    private String run(String applicationId, String output) {
        ObjectNode request = JSON.createObjectNode()
                .put("clientToken", UUID.randomUUID().toString())
                .put("executionRoleArn", "arn:aws:iam::" + ACCOUNT + ":role/emr-serverless-job");
        request.putObject("jobDriver").putObject("sparkSubmit")
                .put("entryPoint", "s3://" + bucket + "/job.py")
                .putArray("entryPointArguments").add("s3://" + bucket + "/" + output);
        String jobRunId = startJobRun(REGION, applicationId, request.toString());

        Response ended = awaitJobRun(REGION, applicationId, jobRunId);

        assertEquals("SUCCESS", ended.jsonPath().getString("jobRun.state"),
                ended.jsonPath().getString("jobRun.stateDetails"));
        return S3TestClients.partFiles(s3, bucket, output).strip();
    }

    private String imageDigest(String applicationId) {
        return emrServerless(REGION).when().get("/applications/" + applicationId)
                .then().statusCode(200).extract().path("application.imageConfiguration.resolvedImageDigest");
    }
}
