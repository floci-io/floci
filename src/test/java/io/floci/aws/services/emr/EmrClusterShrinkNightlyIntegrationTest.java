package io.floci.aws.services.emr;

import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
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

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.EmrRequests.awaitStep;
import static io.floci.aws.testing.EmrRequests.emr;
import static io.floci.aws.testing.EmrRequests.terminateClusterAndAwaitCleanup;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Executor placement and HDFS data preservation while a four-node EMR cluster shrinks. */
@QuarkusTest
@TestProfile(CloudFormationSettingsProfile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
class EmrClusterShrinkNightlyIntegrationTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    ContainerLifecycleManager lifecycle;

    @Inject
    EmulatorConfig config;

    @Inject
    EmrService emrService;

    private S3Client s3;
    private String bucket;
    private String clusterId;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void prepareBucket() throws InterruptedException {
        assumeTrue(DockerAvailability.isAvailable(), "Docker daemon must be available for EMR's shrink check");
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "000000000000", Region.US_EAST_1);
        cleanup.register(s3::close);
        bucket = "emr-shrink-" + UUID.randomUUID().toString().substring(0, 8);
        s3.createBucket(request -> request.bucket(bucket));
        cleanup.register(() -> S3TestClients.deleteBucket(s3, bucket));
    }

    @Test
    // Two five-minute step waits + five-minute decommission + one minute for API calls and assertions.
    @Timeout(value = 16, unit = TimeUnit.MINUTES)
    void aClustersCoreAndTaskNodesRunItsExecutorsAndAShrinkKeepsItsHdfsData() {
        put("side/nodes.py", """
                import socket
                import sys
                from pyspark.sql import SparkSession
                spark = SparkSession.builder.getOrCreate()
                lines = spark.sparkContext.parallelize(range(8), 8) \\
                    .map(lambda i: (socket.gethostname(), "line " + str(i))).cache()
                lines.map(lambda line: line[1]).saveAsTextFile("hdfs:///kept")
                hosts = " ".join(sorted(set(lines.map(lambda line: line[0]).collect())))
                spark.createDataFrame([(hosts,)], ["value"]).coalesce(1).write.text(sys.argv[1])
                """);
        startCluster("[]", null, ",{\"Name\":\"core\",\"InstanceRole\":\"CORE\",\"InstanceType\":\"m5.xlarge\","
                + "\"InstanceCount\":2},{\"Name\":\"task\",\"InstanceRole\":\"TASK\",\"InstanceType\":\"m5.xlarge\","
                + "\"InstanceCount\":1}");

        // Its executors run on its core and task nodes, each named as ListInstances names it, not on its primary node.
        assertEquals("COMPLETED", runStep("command-runner.jar", "spark-submit", s3Uri("side/nodes.py"),
                s3Uri("hosts/")));
        List<String> instances = emr("ListInstances", "{\"ClusterId\":\"" + clusterId + "\"}").jsonPath()
                .getList("Instances.PrivateDnsName");
        for (String host : partFiles("hosts/").trim().split(" ")) {
            assertTrue(instances.subList(1, instances.size()).contains(host), host + " among " + instances);
        }

        // A core node goes, once HDFS has moved its blocks to the one that stays, and every line is still there.
        String core = emr("ListInstanceGroups", "{\"ClusterId\":\"" + clusterId + "\"}").jsonPath()
                .getString("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.Id");
        emr("ModifyInstanceGroups", "{\"ClusterId\":\"" + clusterId + "\",\"InstanceGroups\":[{\"InstanceGroupId\":\""
                + core + "\",\"InstanceCount\":1}]}").then().statusCode(200);
        String leaving = ContainerStorageHelper.dockerName(config, "emr-" + clusterId + "-node-1-2");
        await("core node " + leaving + " to go").atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofSeconds(1))
                .until(() -> gone(leaving));
        assertEquals("COMPLETED", runStep("command-runner.jar", "s3-dist-cp", "--src", "hdfs:///kept/",
                "--dest", s3Uri("kept/")));
        assertEquals("line 0\nline 1\nline 2\nline 3\nline 4\nline 5\nline 6\nline 7\n", partFiles("kept/"));
    }

    /** A primary node followed by {@code moreGroups}, each led by a comma, on the cached release. */
    private void startCluster(String configurations, String logUri, String moreGroups) {
        clusterId = emr("RunJobFlow", "{\"Name\":\"docker-runner\",\"ReleaseLabel\":\"emr-7.5.0\","
                + (logUri == null ? "" : "\"LogUri\":\"" + logUri + "\",")
                + "\"Applications\":[{\"Name\":\"Hadoop\"},{\"Name\":\"Spark\"}],"
                + "\"Configurations\":" + configurations + ","
                + "\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,\"InstanceGroups\":[{\"Name\":\"primary\","
                + "\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}" + moreGroups + "]}}")
                .then().statusCode(200).extract().jsonPath().getString("JobFlowId");
        cleanup.register(() -> terminateClusterAndAwaitCleanup(clusterId, emrService));
    }

    /** Whether Docker no longer has the container named so. */
    private boolean gone(String name) {
        try {
            lifecycle.getDockerClient().inspectContainerCmd(name).exec();
            return false;
        } catch (NotFoundException e) {
            return true;
        }
    }

    /** Adds one step to the cluster and returns the state it ended in. */
    private String runStep(String jar, String... args) {
        String stepId = EmrRequests.addStep(clusterId, jar, jar, args);
        return awaitStep(clusterId, stepId).jsonPath().getString("Step.Status.State");
    }

    private void put(String key, String text) {
        s3.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString(text));
    }

    private String s3Uri(String key) {
        return "s3://" + bucket + "/" + key;
    }

    private String partFiles(String prefix) {
        return S3TestClients.partFiles(s3, bucket, prefix);
    }
}
