package io.floci.aws.services.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLiveness;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.RetryingTarCopier;
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
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.floci.aws.testing.EmrRequests.awaitStep;
import static io.floci.aws.testing.EmrRequests.emr;
import static io.floci.aws.testing.EmrRequests.terminateClusterAndAwaitCleanup;
import static io.floci.aws.testing.EmrServerlessRequests.awaitJobRun;
import static io.floci.aws.testing.EmrServerlessRequests.createSparkApplication;
import static io.floci.aws.testing.EmrServerlessRequests.deleteApplication;
import static io.floci.aws.testing.EmrServerlessRequests.startJobRun;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * EMR steps and EMR Serverless job runs through Floci's Docker runner for real: a cluster in the {@code docker}
 * runner mode runs each step, and an application each job run, in a container of the release's EMR Serverless image,
 * which reaches Floci's S3 and Glue from there, a job run's executors each in a container of their own, as the profile sets. The cached {@code emr-7.5.0} image keeps a local run from pulling;
 * CI pulls it (3.5 GB) the first time.
 */
@QuarkusTest
@TestProfile(ContainerDataPlaneProfile.class)
class EmrDockerRunnerDockerIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String WORDS = "a b a\nc b a\n";
    private static final Map<String, Integer> WORD_COUNTS = Map.of("a", 3, "b", 2, "c", 1);
    private static final String MAPPER = """
            import sys
            for line in sys.stdin:
                for word in line.split():
                    print(word + "\\t1")
            """;
    private static final String REDUCER = """
            import sys
            counts = {}
            for line in sys.stdin:
                word, count = line.rstrip("\\n").split("\\t")
                counts[word] = counts.get(word, 0) + int(count)
            for word in sorted(counts):
                print(word + "\\t" + str(counts[word]))
            """;

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
    void putInputs() throws InterruptedException {
        assumeTrue(DockerAvailability.isAvailable(), "Docker daemon must be available for EMR's Docker runner tests");
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "000000000000", Region.US_EAST_1);
        cleanup.register(s3::close);
        bucket = "emr-docker-" + UUID.randomUUID().toString().substring(0, 8);
        s3.createBucket(request -> request.bucket(bucket));
        cleanup.register(() -> S3TestClients.deleteBucket(s3, bucket));
        put("in/words.txt", WORDS);
        put("side/mapper.py", MAPPER);
        put("side/reducer.py", REDUCER);
    }

    @Test
    void aStreamingWordCountStepReadsItsInputAndWritesItsCountsInFlocisS3() {
        startCluster("[]");

        assertEquals("COMPLETED", runStep("command-runner.jar", "hadoop-streaming",
                "-files", side("mapper.py", "reducer.py"), "-mapper", "python3 mapper.py",
                "-reducer", "python3 reducer.py", "-input", s3Uri("in/"), "-output", s3Uri("out/")));
        assertEquals(WORD_COUNTS, counts("out/"));
    }

    @Test
    void aStreamingJobsMapperAndReducerRunInYarnContainersOnItsClustersCoreAndTaskNodes() {
        // Each says the host it ran on, after the words it counted.
        String where = """
                import socket
                print(sys.argv[1] + " " + socket.gethostname() + "\\t1")
                """;
        put("side/where-mapper.py", MAPPER + where);
        put("side/where-reducer.py", REDUCER + where);
        startCluster("[]", null, ",{\"Name\":\"core\",\"InstanceRole\":\"CORE\",\"InstanceType\":\"m5.xlarge\","
                + "\"InstanceCount\":1},{\"Name\":\"task\",\"InstanceRole\":\"TASK\",\"InstanceType\":\"m5.xlarge\","
                + "\"InstanceCount\":1}");
        List<String> instances = emr("ListInstances", "{\"ClusterId\":\"" + clusterId + "\"}").jsonPath()
                .getList("Instances.PrivateDnsName");
        List<String> nodes = instances.subList(1, instances.size());

        // The mapper and the reducer, from the files -files sends, with a shuffle between them, run on the core and
        // task nodes, not in the step's container.
        assertEquals("COMPLETED", runStep("command-runner.jar", "hadoop-streaming",
                "-D", "mapreduce.job.maps=1", "-D", "mapreduce.job.reduces=1",
                "-files", side("where-mapper.py", "where-reducer.py"), "-mapper", "python3 where-mapper.py mapper",
                "-reducer", "python3 where-reducer.py reducer", "-input", s3Uri("in/"), "-output", s3Uri("where/")));
        Map<String, Integer> counts = counts("where/");
        assertEquals(WORD_COUNTS, counts.entrySet().stream().filter(entry -> !entry.getKey().contains(" "))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)), counts::toString);
        List<String> ranOn = counts.keySet().stream().filter(key -> key.contains(" ")).toList();
        assertEquals(List.of("mapper", "reducer"), ranOn.stream().map(task -> task.split(" ")[0]).distinct().toList(),
                ranOn::toString);
        for (String task : ranOn) {
            assertTrue(nodes.contains(task.split(" ")[1]), task + " among " + nodes);
        }
    }

    @Test
    void aCustomJarAtEmrsStreamingJarPathRunsItsWordCountInTheDriver() {
        startCluster("[]");

        assertEquals("COMPLETED", runStep("/usr/lib/hadoop-mapreduce/hadoop-streaming.jar",
                "-files", side("mapper.py", "reducer.py"), "-mapper", "python3 mapper.py",
                "-reducer", "python3 reducer.py", "-input", s3Uri("in/"), "-output", s3Uri("jar-out/")));
        assertEquals(WORD_COUNTS, counts("jar-out/"));
    }

    @Test
    void s3DistCpCopiesFromFlocisS3IntoTheClustersHdfsAndBack() {
        startCluster("[]");

        assertEquals("COMPLETED", runStep("command-runner.jar", "s3-dist-cp",
                "--src", s3Uri("in/"), "--dest", "hdfs:///input/"));
        assertEquals("COMPLETED", runStep("command-runner.jar", "s3-dist-cp",
                "--src", "hdfs:///input/", "--dest", s3Uri("copied/")));
        assertEquals(WORDS,
                s3.getObjectAsBytes(request -> request.bucket(bucket).key("copied/words.txt")).asUtf8String());
    }

    @Test
    void aSparkStepKeepsAHiveTableAndAStreamingQuerysCheckpointsInTheClustersHdfsWhichListsFromItsRoot() {
        put("side/stream.py", """
                import sys
                from pyspark.sql import SparkSession
                spark = SparkSession.builder.enableHiveSupport().getOrCreate()
                # Hive, which Spark writes the table through, looks up the HDFS encryption zone of its folder.
                spark.sql("create table words (w string) stored as textfile location 'hdfs:///hive-format/words'")
                spark.sql("insert into words values ('a'), ('b')")
                spark.table("words").write.text("/stream/in")
                query = spark.readStream.text("/stream/in").writeStream.format("parquet") \\
                    .option("checkpointLocation", "hdfs:///stream/checkpoint").option("path", "hdfs:///stream/out") \\
                    .trigger(availableNow=True).start()
                query.awaitTermination()
                rows = sorted(row.value for row in spark.read.parquet("/stream/out").collect())
                spark.createDataFrame([(" ".join(rows),)], ["value"]).coalesce(1).write.text(sys.argv[1])
                """);
        startCluster("[]");

        // The checkpoint and the sink are hdfs:// paths without a host, as the Management Guide's
        // "hdfs:///path-to-data", which Hadoop's FileContext, behind Spark's default checkpoint manager, refuses.
        assertEquals("COMPLETED", runStep("command-runner.jar", "spark-submit",
                s3Uri("side/stream.py"), s3Uri("stream-out/")));
        assertEquals("a b\n", partFiles("stream-out/"));
        assertEquals("COMPLETED", runStep("command-runner.jar", "hadoop", "fs", "-test", "-e",
                "/stream/checkpoint/offsets/0"));
        assertEquals("COMPLETED", runStep("command-runner.jar", "hadoop", "fs", "-ls", "/"));
    }

    @Test
    void aSparkStepRunsOnTheClustersYarnInEitherDeployMode() {
        put("side/where.py", """
                import socket
                import sys
                from pyspark.sql import SparkSession
                spark = SparkSession.builder.getOrCreate()
                sc = spark.sparkContext
                executors = sc.parallelize(range(4), 4).map(lambda _: socket.gethostname()).distinct().collect()
                where = " ".join([sc.master, sc.getConf().get("spark.submit.deployMode"),
                                  str(socket.gethostname() in executors)])
                print("driver: " + where, flush=True)
                spark.createDataFrame([(where,)], ["value"]).coalesce(1).write.text(sys.argv[1])
                """);
        startCluster("[]", s3Uri("logs/"));

        // Its driver in the step's container, its executors on the primary node.
        assertEquals("COMPLETED", runStep("command-runner.jar", "spark-submit",
                s3Uri("side/where.py"), s3Uri("client/")));
        assertEquals("yarn client False\n", partFiles("client/"));
        // Its driver on the primary node too, whose output is in its application's container logs, where a user
        // reads it, the step's own holding only the client's.
        assertEquals("COMPLETED", runStep("command-runner.jar", "spark-submit", "--deploy-mode", "cluster",
                s3Uri("side/where.py"), s3Uri("cluster/")));
        assertEquals("yarn cluster True\n", partFiles("cluster/"));
        List<String> driverLogs = containerStdoutsHolding("driver: yarn cluster True");
        assertEquals(1, driverLogs.size(), () -> "container stdouts with the driver's line: " + driverLogs);
        String containers = "logs/" + clusterId + "/containers/";
        assertTrue(driverLogs.get(0).matches(containers + "application_\\d+_\\d+/container_\\w+/stdout\\.gz"),
                driverLogs.get(0));
    }

    @Test
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

    @Test
    void aClustersHdfsWhoseDataNodeExitsStartsAgainOnItsDataForTheNextStep() {
        startCluster("[]");
        assertEquals("COMPLETED", runStep("command-runner.jar", "s3-dist-cp",
                "--src", s3Uri("in/"), "--dest", "hdfs:///kept/"));
        String hdfs = ContainerStorageHelper.dockerName(config, "emr-" + clusterId + "-hdfs");

        // The DataNode exits, as one the kernel stops for memory does, and its container ends with it.
        ContainerExec.run(lifecycle.getDockerClient(), hdfs,
                new String[]{"pkill", "-f", "org.apache.hadoop.hdfs.server.datanode.DataNode"}, 10);
        await("the HDFS container of " + clusterId + " to stop").atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> lifecycle.probeContainer(hdfs) == ContainerLiveness.NOT_RUNNING);

        // The next step starts it again on its volume, once the NameNode has left safe mode, with the file there.
        assertEquals("COMPLETED", runStep("command-runner.jar", "s3-dist-cp",
                "--src", "hdfs:///kept/", "--dest", s3Uri("kept/")));
        assertEquals(WORDS,
                s3.getObjectAsBytes(request -> request.bucket(bucket).key("kept/words.txt")).asUtf8String());
    }

    @Test
    void aClustersSparkEnvExportReachesItsSparkStepsDriver() {
        put("side/env.py", """
                import os, sys
                from pyspark.sql import SparkSession
                spark = SparkSession.builder.getOrCreate()
                value = os.environ.get("FLOCI_PROBE", "unset")
                spark.createDataFrame([(value,)], ["value"]).coalesce(1).write.text(sys.argv[1])
                """);
        startCluster("""
                [{"Classification": "spark-env", "Configurations": [
                  {"Classification": "export", "Properties": {"FLOCI_PROBE": "from spark-env"}}]}]
                """);

        assertEquals("COMPLETED", runStep("command-runner.jar", "spark-submit",
                s3Uri("side/env.py"), s3Uri("env-out/")));
        assertEquals("from spark-env\n", partFiles("env-out/"));
    }

    @Test
    void aSparkStepOnTheGlueCatalogWritesAndReadsBackAPartitionedTableInFlocisGlue() {
        String database = bucket.replace('-', '_');
        put("side/glue.py", """
                import sys
                from pyspark.sql import SparkSession
                spark = SparkSession.builder.enableHiveSupport().getOrCreate()
                database, warehouse, out = sys.argv[1:4]
                spark.sql(f"create database {database} location '{warehouse}'")
                spark.sql(f"create table {database}.words (word string, n int, d string) using parquet"
                          f" partitioned by (d) location '{warehouse}words/'")
                spark.sql(f"insert into {database}.words values ('a', 1, 'one'), ('b', 2, 'two'), ('c', 3, 'two')")
                rows = spark.sql(f"select d, count(*) from {database}.words group by d order by d").collect()
                spark.createDataFrame([(" ".join(f"{d}={n}" for d, n in rows),)], ["value"]) \\
                    .coalesce(1).write.text(out)
                """);
        GlueClient glue = AwsTestClients.create(GlueClient.builder(), "000000000000", Region.US_EAST_1);
        cleanup.register(glue::close);
        cleanup.register(() -> glue.deleteDatabase(request -> request.name(database)));
        // The Management Guide's setting for a cluster's Spark on the Glue Data Catalog.
        startCluster("""
                [{"Classification": "spark-hive-site", "Properties": {"hive.metastore.client.factory.class":
                  "com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory"}}]
                """);

        assertEquals("COMPLETED", runStep("command-runner.jar", "spark-submit",
                s3Uri("side/glue.py"), database, s3Uri("warehouse/"), s3Uri("glue-out/")));
        // Each row once, though the Glue client reads a table's partitions in five segments.
        assertEquals("one=1 two=2\n", partFiles("glue-out/"));
        // Floci's Glue holds the partitions, so the step's Spark used the Glue catalog, not a metastore of its own.
        assertEquals(List.of("one", "two"),
                glue.getPartitions(request -> request.databaseName(database).tableName("words")).partitions().stream()
                        .map(partition -> partition.values().get(0)).sorted().toList());
    }

    @Test
    void anEmrServerlessRunsPythonIsTheOneInTheArchiveItsSparkArchivesUnpack() throws IOException {
        put("side/job.py", """
                import os, sys
                import floci_probe
                from pyspark.sql import SparkSession
                spark = SparkSession.builder.getOrCreate()
                folder = os.path.basename(os.path.dirname(os.path.dirname(floci_probe.__file__)))
                spark.createDataFrame([(floci_probe.VALUE + " " + folder,)], ["value"]) \\
                    .coalesce(1).write.text(sys.argv[1])
                """);
        s3.putObject(request -> request.bucket(bucket).key("envs/pyspark_venv.tar.gz"),
                RequestBody.fromBytes(environmentArchive()));
        String applicationId = createSparkApplication(REGION, "emr-7.5.0");
        cleanup.register(() -> deleteApplication(REGION, applicationId));
        // The user guide's recipe for a virtual environment: the archive, and the driver's and executors' Python in it.
        String python = "./environment/bin/python";
        ObjectNode run = JSON.createObjectNode().put("clientToken", UUID.randomUUID().toString())
                .put("executionRoleArn", "arn:aws:iam::000000000000:role/emr-serverless-job");
        ObjectNode sparkSubmit = run.putObject("jobDriver").putObject("sparkSubmit")
                .put("entryPoint", s3Uri("side/job.py"))
                .put("sparkSubmitParameters", "--conf spark.archives=" + s3Uri("envs/pyspark_venv.tar.gz#environment")
                        + " --conf spark.emr-serverless.driverEnv.PYSPARK_DRIVER_PYTHON=" + python
                        + " --conf spark.emr-serverless.driverEnv.PYSPARK_PYTHON=" + python
                        + " --conf spark.executorEnv.PYSPARK_PYTHON=" + python);
        sparkSubmit.putArray("entryPointArguments").add(s3Uri("venv-out/"));
        String jobRunId = startJobRun(REGION, applicationId, run.toString());

        Response ended = awaitJobRun(REGION, applicationId, jobRunId);

        assertEquals("SUCCESS", ended.jsonPath().getString("jobRun.state"));
        assertEquals("from the archive environment\n", partFiles("venv-out/"));
    }

    @Test
    void anEmrServerlessRunsExecutorsRunInContainersOfTheirOwnThatGoWithIt() {
        put("side/hosts.py", """
                import socket, sys, time
                from pyspark.sql import SparkSession
                spark = SparkSession.builder.getOrCreate()
                hosts = spark.sparkContext.parallelize(range(24), 24) \\
                    .map(lambda i: (time.sleep(0.5), socket.gethostname())[1]).distinct().collect()
                where = "with the driver" if socket.gethostname() in hosts else "apart from the driver"
                spark.createDataFrame([("%d executors, %s" % (len(hosts), where),)], ["value"]) \\
                    .coalesce(1).write.text(sys.argv[1])
                """);
        String applicationId = createSparkApplication(REGION, "emr-7.5.0");
        cleanup.register(() -> deleteApplication(REGION, applicationId));
        ObjectNode run = JSON.createObjectNode().put("clientToken", UUID.randomUUID().toString())
                .put("executionRoleArn", "arn:aws:iam::000000000000:role/emr-serverless-job");
        ObjectNode sparkSubmit = run.putObject("jobDriver").putObject("sparkSubmit")
                .put("entryPoint", s3Uri("side/hosts.py"))
                .put("sparkSubmitParameters", "--conf spark.executor.cores=2 --conf spark.executor.memory=1g");
        sparkSubmit.putArray("entryPointArguments").add(s3Uri("hosts-out/"));
        String jobRunId = startJobRun(REGION, applicationId, run.toString());

        Response ended = awaitJobRun(REGION, applicationId, jobRunId);

        assertEquals("SUCCESS", ended.jsonPath().getString("jobRun.state"));
        // Each executor has a worker of its own, as on EMR Serverless, up to the profile's two.
        assertEquals("2 executors, apart from the driver\n", partFiles("hosts-out/"));
        await().atMost(Duration.ofSeconds(30)).until(() -> lifecycle.getDockerClient().listContainersCmd()
                .withShowAll(true).withLabelFilter(Map.of(ContainerStorageHelper.RESOURCE_ID_LABEL, jobRunId))
                .exec().isEmpty());
    }

    /**
     * A stand-in for the guide's packed virtual environment that needs no package index: its {@code bin/python}
     * runs the image's Python with the archive's own {@code lib} on the path, where the module the job imports is.
     */
    private static byte[] environmentArchive() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = RetryingTarCopier.newTarStream(new GzipCompressorOutputStream(out))) {
            RetryingTarCopier.putEntry(tar, "bin/python", """
                    #!/bin/sh
                    here="$(cd "$(dirname "$0")/.." && pwd)"
                    PYTHONPATH="$here/lib${PYTHONPATH:+:$PYTHONPATH}" exec /usr/bin/python3 "$@"
                    """.getBytes(StandardCharsets.UTF_8), 0755);
            RetryingTarCopier.putEntry(tar, "lib/floci_probe.py",
                    "VALUE = \"from the archive\"\n".getBytes(StandardCharsets.UTF_8), 0644);
        }
        return out.toByteArray();
    }

    /** A one-primary cluster of the cached release, with {@code configurations} as its Configurations. */
    private void startCluster(String configurations) {
        startCluster(configurations, null);
    }

    /** The same, its logs under {@code logUri} when that is not null, as the cluster's {@code LogUri}. */
    private void startCluster(String configurations, String logUri) {
        startCluster(configurations, logUri, "");
    }

    /** The same, with the instance groups {@code moreGroups}, each led by a comma, after its primary node's. */
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
        ObjectNode request = JSON.createObjectNode().put("JobFlowId", clusterId);
        ObjectNode step = request.putArray("Steps").addObject().put("Name", jar).put("ActionOnFailure", "CONTINUE");
        ObjectNode hadoopJarStep = step.putObject("HadoopJarStep").put("Jar", jar);
        List.of(args).forEach(hadoopJarStep.putArray("Args")::add);
        String stepId = emr("AddJobFlowSteps", request.toString())
                .then().statusCode(200).extract().jsonPath().getString("StepIds[0]");
        return awaitStep(clusterId, stepId).jsonPath().getString("Step.Status.State");
    }

    private void put(String key, String text) {
        s3.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString(text));
    }

    private String s3Uri(String key) {
        return "s3://" + bucket + "/" + key;
    }

    private String side(String... names) {
        return Stream.of(names).map(name -> s3Uri("side/" + name)).collect(Collectors.joining(","));
    }

    /** The word counts a streaming job wrote, one {@code word<TAB>count} line each, over all its part files. */
    private Map<String, Integer> counts(String prefix) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String line : partFiles(prefix).split("\n")) {
            if (!line.isBlank()) {
                String[] fields = line.split("\t");
                counts.merge(fields[0], Integer.parseInt(fields[1].trim()), Integer::sum);
            }
        }
        return counts;
    }

    /** The keys of the cluster's container {@code stdout} logs under {@code logs/} that hold {@code line}. */
    private List<String> containerStdoutsHolding(String line) {
        String prefix = "logs/" + clusterId + "/containers/";
        return s3.listObjectsV2Paginator(request -> request.bucket(bucket).prefix(prefix)).contents().stream()
                .map(S3Object::key)
                .filter(key -> key.endsWith("/stdout.gz"))
                .filter(key -> S3TestClients.gunzipped(s3, bucket, key).contains(line))
                .toList();
    }

    /** What a job wrote under {@code prefix}: its part files, in key order, one after the other. */
    private String partFiles(String prefix) {
        StringBuilder text = new StringBuilder();
        for (S3Object object : s3.listObjectsV2(request -> request.bucket(bucket).prefix(prefix)).contents()) {
            if (object.key().substring(prefix.length()).startsWith("part-")) {
                text.append(s3.getObjectAsBytes(request -> request.bucket(bucket).key(object.key())).asUtf8String());
            }
        }
        return text.toString();
    }
}
