package io.floci.aws.services.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.ContainerDataPlaneProfile;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.S3TestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Table;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EmrRequests.awaitStep;
import static io.floci.aws.testing.EmrRequests.emr;
import static io.floci.aws.testing.EmrRequests.terminateClusterAndAwaitCleanup;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * EMR Hive steps through Floci's Docker runner for real: on a cluster with Hive in the {@code docker} runner mode, the
 * AWS CLI's Hive step runs {@code hive-script} in a container of the cluster's image, the release's EMR Serverless
 * image with its EMR Serverless Hive image's Hive, which Floci makes once, on the cluster's storage, its HDFS and
 * metastore, and on Floci's S3. The cached {@code emr-7.5.0} images keep a local run from pulling; CI pulls the Hive
 * one (2.7 GB) and the Spark one (3.5 GB) the first time.
 */
@QuarkusTest
@TestProfile(ContainerDataPlaneProfile.class)
class EmrHiveStepsDockerIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String ROWS = "a,1\nb,2\nc,3\n";
    private static final String HIVE_AND_SPARK = "[{\"Name\":\"Hadoop\"},{\"Name\":\"Hive\"},{\"Name\":\"Spark\"}]";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

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
    void putInputs() throws InterruptedException {
        assumeTrue(DockerAvailability.isAvailable(), "Docker daemon must be available for EMR's Hive steps");
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "000000000000", Region.US_EAST_1);
        cleanup.register(s3::close);
        bucket = "emr-hive-" + UUID.randomUUID().toString().substring(0, 8);
        s3.createBucket(request -> request.bucket(bucket));
        cleanup.register(() -> S3TestClients.deleteBucket(s3, bucket));
        put("data/rows/rows.csv", ROWS);
    }

    @Test
    void hiveAndSparkStepsShareTheClustersMetastoreServiceAndHdfs() {
        // The two steps that read what the first one made, and need nothing from each other, run at once.
        startCluster(HIVE_AND_SPARK, "[]", null, 2);
        put("queries/load.q", """
                create external table kv (k string, v int)
                  row format delimited fields terminated by ',' location '${INPUT}';
                create table scaled as select k, v * 10 as v from kv;
                create table pinned (k string) location 'hdfs:///floci/pinned';
                insert into pinned select k from kv;
                """);
        put("queries/report.q", """
                insert overwrite directory '${OUTPUT}' row format delimited fields terminated by ','
                  select k, v from scaled order by k;
                select k, v from scaled order by k;
                select k from from_spark order by k;
                """);
        put("jobs/catalog.py", """
                import sys
                from pyspark.sql import SparkSession
                spark = SparkSession.builder.enableHiveSupport().getOrCreate()
                rows = sorted(f"{row.k}{row.v}" for row in spark.sql("select k, v from scaled").collect())
                spark.createDataFrame([(" ".join(rows),)], ["value"]).coalesce(1).write.text(sys.argv[1])
                spark.sql("create table from_spark stored as textfile as select upper(k) as k from scaled")
                """);

        assertEquals("COMPLETED", hiveStep("-f", s3Uri("queries/load.q"), "-d", "INPUT=" + s3Uri("data/rows/")));
        // Its data is in the cluster's HDFS, which a step in the Spark image reads as hdfs://, and so is an hdfs://
        // location's, whose NameNode answers Hive's encryption zone lookups.
        String cat = addStep("command-runner.jar", "hadoop", "fs", "-cat", "hdfs:///user/hive/warehouse/scaled/*",
                "hdfs:///floci/pinned/*");
        // A Spark step uses the cluster's metastore too: it reads Hive's table, and makes one a Hive step reads.
        String spark = addStep("command-runner.jar", "spark-submit", s3Uri("jobs/catalog.py"), s3Uri("out/spark/"));
        assertEquals("COMPLETED", state(cat));
        assertEquals("a\u000110\nb\u000120\nc\u000130\na\nb\nc\n", stepLog(cat, "stdout.gz"));
        assertEquals("COMPLETED", state(spark));
        assertEquals("a10 b20 c30\n", objects("out/spark/"));
        // The tables the first step and the Spark step made are in the cluster's metastore for a later Hive step.
        String report = addStep("command-runner.jar", "hive-script", "--run-hive-script", "--args",
                "-f", s3Uri("queries/report.q"), "-d", "OUTPUT=" + s3Uri("out/report/"));
        assertEquals("COMPLETED", state(report));
        assertEquals("a,10\nb,20\nc,30\n", objects("out/report/"));
        String reported = stepLog(report, "stdout.gz");
        assertTrue(reported.contains("a\t10\nb\t20\nc\t30\n") && reported.contains("A\nB\nC\n"), reported);
    }

    @Test
    void hiveStepsRunningAtOnceShareTheClustersMetastoreService() {
        startCluster(HIVE_AND_SPARK, "[]", null, 2);

        // The first holds its session past the second's, which an embedded Derby database would refuse.
        String slow = addStep("command-runner.jar", "hive-script", "--run-hive-script", "--args",
                "-e", "create table slow (k string); !sleep 20; insert into slow values ('s');");
        String quick = addStep("command-runner.jar", "hive-script", "--run-hive-script", "--args",
                "-e", "create table quick as select 'q' as k;");
        assertEquals("COMPLETED", state(quick));
        assertEquals("COMPLETED", state(slow));
        String both = addStep("command-runner.jar", "hive-script", "--run-hive-script", "--args",
                "-e", "select k from slow union all select k from quick order by k;");
        assertEquals("COMPLETED", state(both));
        assertTrue(stepLog(both, "stdout.gz").contains("q\ns\n"));
    }

    @Test
    void aHiveStepsTezTasksRunInYarnContainersOnItsClustersPrimaryNode() {
        startCluster(HIVE_AND_SPARK, "[]", null);
        List<String> instances = emr("ListInstances", "{\"ClusterId\":\"" + clusterId + "\"}").jsonPath()
                .getList("Instances.PrivateDnsName");

        // A query that groups its rows through a shuffle, its tasks giving the hosts they ran on: the primary node,
        // whose NodeManager runs them on a cluster without core nodes, not the step's container. Core and task nodes
        // running a job's containers are EmrDockerRunnerDockerIntegrationTest's, as a cluster with Hive starts slower.
        assertEquals("COMPLETED", hiveStep("-e", "create external table kv (k string, v int) row format delimited"
                + " fields terminated by ',' location '" + s3Uri("data/rows/") + "';"
                + " insert overwrite directory '" + s3Uri("out/tez/") + "' row format delimited fields terminated"
                + " by ',' collection items terminated by ' ' select sum(v), count(k),"
                + " collect_set(split(reflect('java.net.InetAddress', 'getLocalHost'), '/')[0]) from kv;"));
        String[] fields = objects("out/tez/").trim().split(",");
        assertEquals(List.of("6", "3"), List.of(fields[0], fields[1]), () -> String.join(",", fields));
        List<String> hosts = List.of(fields[2].split(" "));
        assertTrue(instances.containsAll(hosts), hosts + " among " + instances);
    }

    @Test
    void aClustersHiveSiteForTheGlueDataCatalogKeepsItsHiveStepsTablesInFlocisGlue() {
        String database = bucket.replace('-', '_');
        GlueClient glue = AwsTestClients.create(GlueClient.builder(), "000000000000", Region.US_EAST_1);
        cleanup.register(glue::close);
        cleanup.register(() -> glue.deleteDatabase(request -> request.name(database)));
        // The Release Guide's hive-site for the Glue Data Catalog.
        startCluster(HIVE_AND_SPARK, """
                [{"Classification": "hive-site", "Properties": {"hive.metastore.client.factory.class":
                  "com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory"}}]""", null);

        assertEquals("COMPLETED", hiveStep("-e", "create database " + database + " location '"
                + s3Uri("warehouse/") + "'; create external table " + database + ".kv (k string, v int) row format"
                + " delimited fields terminated by ',' location '" + s3Uri("data/rows/") + "';"));
        assertEquals(List.of("kv"), glue.getTables(request -> request.databaseName(database)).tableList()
                .stream().map(Table::name).toList());
    }

    @Test
    void aHiveStepOnAClusterWithoutHiveFailsAsEmrsHiveScriptDoes() {
        startCluster("[{\"Name\":\"Hadoop\"},{\"Name\":\"Spark\"}]", "[]", null);

        String query = addStep("command-runner.jar", "hive-script", "--run-hive-script", "--args", "-e", "select 1");

        assertEquals("FAILED", state(query));
        assertTrue(stepLog(query, "stdout.gz").contains("Hive isn't installed."));
    }

    @Test
    void whatABootstrapInstalledIsThereForTheClustersHiveAndOtherSteps() {
        // One action, as the Management Guide's "Run custom bootstrap actions" has them: a file in the hadoop user's
        // home, one in /mnt, and a command installed with sudo.
        put("bootstrap/install.sh", String.join("\n", "#!/bin/bash",
                "echo bootstrapped > /home/hadoop/marker.txt",
                "echo mounted > /mnt/marker.txt",
                "printf '%s\\n' '#!/bin/bash' 'echo installed' | sudo tee /usr/local/bin/floci-probe > /dev/null",
                "sudo chmod +x /usr/local/bin/floci-probe", ""));
        startCluster(HIVE_AND_SPARK, "[]", s3Uri("bootstrap/install.sh"));

        String query = addStep("command-runner.jar", "hive-script", "--run-hive-script", "--args",
                "-e", "!cat /home/hadoop/marker.txt /mnt/marker.txt; !/usr/local/bin/floci-probe;");
        assertEquals("COMPLETED", state(query));
        // The cluster's HDFS, started before the step, keeps its data in a volume of its own, not in /mnt's.
        assertTrue(stepLog(query, "stdout.gz").contains("bootstrapped\nmounted\ninstalled\n"));
        String probe = addStep("command-runner.jar", "floci-probe");
        assertEquals("COMPLETED", state(probe));
        assertEquals("installed\n", stepLog(probe, "stdout.gz"));
    }

    /** A cluster with {@code applications}, {@code configurations} and a bootstrap action's script, if not null. */
    private void startCluster(String applications, String configurations, String bootstrapScript) {
        startCluster(applications, configurations, bootstrapScript, 1);
    }

    /** As {@link #startCluster(String, String, String)}, running up to {@code stepConcurrency} steps at once. */
    private void startCluster(String applications, String configurations, String bootstrapScript,
                              int stepConcurrency) {
        ObjectNode request = JSON.createObjectNode().put("Name", "hive-steps").put("ReleaseLabel", "emr-7.5.0")
                .put("LogUri", s3Uri("logs/")).put("StepConcurrencyLevel", stepConcurrency);
        try {
            request.set("Applications", JSON.readTree(applications));
            request.set("Configurations", JSON.readTree(configurations));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (bootstrapScript != null) {
            ArrayNode actions = request.putArray("BootstrapActions");
            actions.addObject().put("Name", "marker").putObject("ScriptBootstrapAction").put("Path", bootstrapScript);
        }
        request.putObject("Instances").put("KeepJobFlowAliveWhenNoSteps", true).putArray("InstanceGroups")
                .addObject().put("Name", "primary").put("InstanceRole", "MASTER").put("InstanceType", "m5.xlarge")
                .put("InstanceCount", 1);
        clusterId = emr("RunJobFlow", request.toString()).then().statusCode(200).extract().jsonPath()
                .getString("JobFlowId");
        cleanup.register(() -> terminateClusterAndAwaitCleanup(clusterId, emrService));
    }

    /** Runs the AWS CLI's Hive step with {@code args} and returns the state it ended in. */
    private String hiveStep(String... args) {
        List<String> command = new ArrayList<>(List.of("hive-script", "--run-hive-script", "--args"));
        command.addAll(List.of(args));
        return state(addStep("command-runner.jar", command.toArray(String[]::new)));
    }

    /** Adds one step to the cluster and returns its id. */
    private String addStep(String jar, String... args) {
        ObjectNode request = JSON.createObjectNode().put("JobFlowId", clusterId);
        ObjectNode step = request.putArray("Steps").addObject().put("Name", args[0]).put("ActionOnFailure", "CONTINUE");
        ObjectNode hadoopJarStep = step.putObject("HadoopJarStep").put("Jar", jar);
        List.of(args).forEach(hadoopJarStep.putArray("Args")::add);
        return emr("AddJobFlowSteps", request.toString()).then().statusCode(200).extract().jsonPath()
                .getString("StepIds[0]");
    }

    private String state(String stepId) {
        Response ended = awaitStep(clusterId, stepId);
        return ended.jsonPath().getString("Step.Status.State");
    }

    /** A log of the step's, in the step log folder of the Management Guide's "Log locations". */
    private String stepLog(String stepId, String name) {
        return S3TestClients.gunzipped(s3, bucket, "logs/" + clusterId + "/steps/" + stepId + "/" + name);
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
        for (S3Object object : s3.listObjectsV2(request -> request.bucket(bucket).prefix(prefix)).contents()) {
            text.append(s3.getObjectAsBytes(request -> request.bucket(bucket).key(object.key())).asUtf8String());
        }
        return text.toString();
    }
}
