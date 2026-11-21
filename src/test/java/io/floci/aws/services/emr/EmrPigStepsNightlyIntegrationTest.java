package io.floci.aws.services.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CloudFormationSettingsProfile;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.EmrRequests;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.S3TestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.EmrRequests.awaitStep;
import static io.floci.aws.testing.EmrRequests.emr;
import static io.floci.aws.testing.EmrRequests.terminateClusterAndAwaitCleanup;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EMR Pig steps through Floci's Docker runner for real, with {@code floci.services.emr.pig.enabled} on: a cluster
 * whose {@code Applications} name Pig runs the AWS CLI's Pig step, EMR's {@code pig-script} in the image, with Apache
 * Pig, which Floci downloads and adds to the image as it makes it, on Tez on the cluster's YARN, as EMR's Pig runs by
 * default, and on MapReduce when a step asks for it.
 *
 * <p>A nightly check: it downloads Apache Pig (470 MB) and pulls the release's Spark and Hive images.
 */
@QuarkusTest
@TestProfile(EmrPigStepsNightlyIntegrationTest.PigProfile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
// Bounded waits: the image made with Pig, then two steps of up to five minutes each, past the default two.
@Timeout(value = 30, unit = TimeUnit.MINUTES)
class EmrPigStepsNightlyIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PIG = "[{\"Name\":\"Hadoop\"},{\"Name\":\"Pig\"}]";
    private static final String ROWS = "a,1\nb,2\nc,3\na,4\n";
    // Sums each key's values through a shuffle, then orders the sums through another, as Pig's GROUP and ORDER do.
    private static final String SCRIPT = """
            rows = LOAD '$INPUT' USING PigStorage(',') AS (k:chararray, v:int);
            grouped = GROUP rows BY k;
            sums = FOREACH grouped GENERATE group, SUM(rows.v) * 10;
            ordered = ORDER sums BY group;
            STORE ordered INTO '$OUTPUT' USING PigStorage(',');
            """;
    private static final String SUMS = "a,50\nb,20\nc,30\n";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    EmrService emrService;

    private S3Client s3;
    private String bucket;
    private String clusterId;

    /** The Docker data planes, with Floci's Pig on. */
    public static class PigProfile extends CloudFormationSettingsProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.services.emr.pig.enabled", "true");
            return overrides;
        }
    }

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void putInputs() throws InterruptedException {
        assertTrue(DockerAvailability.isAvailable(), "the nightly check needs a Docker daemon");
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "000000000000", Region.US_EAST_1);
        cleanup.register(s3::close);
        bucket = "emr-pig-" + UUID.randomUUID().toString().substring(0, 8);
        s3.createBucket(request -> request.bucket(bucket));
        cleanup.register(() -> S3TestClients.deleteBucket(s3, bucket));
        put("data/rows/rows.csv", ROWS);
        put("scripts/sums.pig", SCRIPT);
    }

    @Test
    void theAwsClisPigStepRunsItsScriptFromS3OnTezThenOnMapReduce() {
        startCluster();

        // The Release Guide's Pig step, Type=PIG,Args=[-f,...,-p,INPUT=...,-p,OUTPUT=...], as the AWS CLI sends it.
        String tez = pigStep("-f", s3Uri("scripts/sums.pig"), "-p", "INPUT=" + s3Uri("data/rows/"),
                "-p", "OUTPUT=" + s3Uri("out/tez/"));
        assertEquals("COMPLETED", state(tez), () -> logs(tez));
        assertEquals(SUMS, objects("out/tez/"));
        // Pig 0.16.0 and later "use Apache Tez", as Floci's Pig does unless told otherwise.
        assertTrue(logs(tez).contains("TezLauncher"), () -> logs(tez));

        String mapReduce = pigStep("-x", "mapreduce", "-f", s3Uri("scripts/sums.pig"),
                "-p", "INPUT=" + s3Uri("data/rows/"), "-p", "OUTPUT=" + s3Uri("out/mapreduce/"));
        assertEquals("COMPLETED", state(mapReduce), () -> logs(mapReduce));
        assertEquals(SUMS, objects("out/mapreduce/"));
        assertTrue(logs(mapReduce).contains("MapReduceLauncher"), () -> logs(mapReduce));
        // Its counters, which Pig's client gets from the cluster's JobHistory server once each job's application master
        // has ended.
        assertTrue(logs(mapReduce).contains("Successfully read 4 records"), () -> logs(mapReduce));
    }

    private void startCluster() {
        ObjectNode request = JSON.createObjectNode().put("Name", "pig-steps").put("ReleaseLabel", "emr-7.5.0")
                .put("LogUri", s3Uri("logs/"));
        try {
            request.set("Applications", JSON.readTree(PIG));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        request.putObject("Instances").put("KeepJobFlowAliveWhenNoSteps", true).putArray("InstanceGroups")
                .addObject().put("Name", "primary").put("InstanceRole", "MASTER").put("InstanceType", "m5.xlarge")
                .put("InstanceCount", 1);
        clusterId = emr("RunJobFlow", request.toString()).then().statusCode(200).extract().jsonPath()
                .getString("JobFlowId");
        cleanup.register(() -> terminateClusterAndAwaitCleanup(clusterId, emrService));
    }

    private String pigStep(String... args) {
        String[] command = new String[args.length + 3];
        command[0] = "pig-script";
        command[1] = "--run-pig-script";
        command[2] = "--args";
        System.arraycopy(args, 0, command, 3, args.length);
        return EmrRequests.addStep(clusterId, "pig", "command-runner.jar", command);
    }

    private String state(String stepId) {
        return awaitStep(clusterId, stepId).jsonPath().getString("Step.Status.State");
    }

    // What a step wrote, Pig's log included, to its standard output, then its standard error.
    private String logs(String stepId) {
        String folder = "logs/" + clusterId + "/steps/" + stepId + "/";
        return S3TestClients.gunzipped(s3, bucket, folder + "stdout.gz") + "\n"
                + S3TestClients.gunzipped(s3, bucket, folder + "stderr.gz");
    }

    private String objects(String prefix) {
        StringBuilder text = new StringBuilder();
        for (S3Object object : s3.listObjectsV2(request -> request.bucket(bucket).prefix(prefix)).contents()) {
            text.append(s3.getObjectAsBytes(request -> request.bucket(bucket).key(object.key())).asUtf8String());
        }
        return text.toString();
    }

    private void put(String key, String text) {
        s3.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString(text));
    }

    private String s3Uri(String key) {
        return "s3://" + bucket + "/" + key;
    }
}
