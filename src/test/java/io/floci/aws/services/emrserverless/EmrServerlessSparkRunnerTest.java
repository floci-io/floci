package io.floci.aws.services.emrserverless;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerExecStubs;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.lambda.launcher.BootstrapProcess;
import io.floci.aws.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.floci.aws.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.testing.MutableClock;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.floci.aws.services.emrserverless.SparkJobRunner.Engine.HIVE;
import static io.floci.aws.services.emrserverless.SparkJobRunner.Engine.SPARK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmrServerlessSparkRunnerTest {

    private static final String ENDPOINT = "http://floci:4566";
    // A cluster's storage, its HDFS running on its primary node.
    private static final SparkJobRunner.Storage STORAGE = new SparkJobRunner.Storage(
            "floci-aws-emr-j-2AXXXXXXGAPLF-home", "floci-aws-emr-j-2AXXXXXXGAPLF-mnt")
            .withHdfs("ip-10-0-0-1.ec2.internal", "172.18.0.5", "floci-aws-emr-j-2AXXXXXXGAPLF-hdfs");

    // The same, on its cluster's YARN, with a step's AWS environment for its containers.
    private static final SparkJobRunner.Storage ON_YARN = STORAGE.withYarn("ip-10-0-0-1.ec2.internal",
            "http://floci:4566");
    private static final List<String> AWS_ENVIRONMENT = List.of("AWS_ACCESS_KEY_ID=ASIASTEP",
            "AWS_ENDPOINT_URL=http://floci:4566");

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
    private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final SparkJobRunner.Progress progress = mock(SparkJobRunner.Progress.class);
    private final S3Service s3 = mock(S3Service.class);
    private final ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
    private final EcrRegistryManager ecr = mock(EcrRegistryManager.class);

    @Test
    void sparkSubmitRunsLocallyOnFlocisS3ThenTheRunsOwnSettings() {
        List<String> application = List.of("--conf", "spark.executor.cores=2", "--py-files", "s3://bucket/jobs/lib.zip",
                "s3://bucket/jobs/report.py", "s3://bucket/input/", "s3://bucket/output/");
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("spark.executor.cores", "4");
        defaults.put("spark.sql.shuffle.partitions", "8");

        List<String> arguments = EmrServerlessSparkRunner.sparkSubmitArguments(job(application, defaults), ENDPOINT);

        assertEquals(List.of("--master", "local[*]",
                "--conf", "spark.hadoop.fs.s3a.endpoint=http://floci:4566",
                "--conf", "spark.hadoop.fs.s3a.endpoint.region=eu-west-1",
                "--conf", "spark.hadoop.fs.s3a.path.style.access=true",
                "--conf", "spark.hadoop.fs.s3a.committer.magic.track.commits.in.memory.enabled=true",
                "--conf", "spark.hadoop.fs.s3.impl=org.apache.hadoop.fs.s3a.S3AFileSystem",
                "--conf", "spark.hadoop.fs.AbstractFileSystem.s3.impl=org.apache.hadoop.fs.s3a.S3A",
                "--conf", "spark.hadoop.aws.glue.endpoint=http://floci:4566",
                "--conf", "spark.hadoop.hive.metastore.glue.catalogid=123456789012",
                "--conf", "spark.hadoop.aws.region=eu-west-1",
                "--conf", "spark.executor.cores=4",
                "--conf", "spark.sql.shuffle.partitions=8",
                "--conf", "spark.executor.cores=2",
                "--py-files", "s3://bucket/jobs/lib.zip",
                "s3://bucket/jobs/report.py", "s3://bucket/input/", "s3://bucket/output/"), arguments);
    }

    @Test
    void theGlueCatalogIsTheJobsAccountUnlessTheJobNamesOneInAnotherAccount() {
        String own = "spark.hadoop.hive.metastore.glue.catalogid=123456789012";
        String other = "spark.hadoop.hive.metastore.glue.catalogid=111122223333";

        List<String> arguments = EmrServerlessSparkRunner.sparkSubmitArguments(job(List.of("s3://bucket/jobs/app.jar"),
                Map.of("spark.hadoop.hive.metastore.glue.catalogid", "111122223333")), ENDPOINT);

        // The job's own setting comes later, so it wins.
        assertTrue(arguments.contains(own));
        assertTrue(arguments.indexOf(other) > arguments.indexOf(own));
    }

    @Test
    void anApplicationWithoutOptionsOrArgumentsFollowsFlocisSettings() {
        List<String> arguments = EmrServerlessSparkRunner.sparkSubmitArguments(
                job(List.of("s3://bucket/jobs/app.jar"), Map.of()), ENDPOINT);

        assertEquals("--conf", arguments.get(arguments.size() - 3));
        assertEquals("spark.hadoop.aws.region=eu-west-1", arguments.get(arguments.size() - 2));
        assertEquals("s3://bucket/jobs/app.jar", arguments.get(arguments.size() - 1));
    }

    @Test
    void sparkSubmitParametersSplitAsAShellWould() {
        assertEquals(List.of("--conf", "spark.driver.extraJavaOptions=-Da=1 -Db=2", "--conf", "x=it's", "a b", ""),
                EmrServerlessSparkRunner.splitParameters(
                        "  --conf \"spark.driver.extraJavaOptions=-Da=1 -Db=2\"\t--conf 'x=it'\\''s' a\\ b ''  "));
        // Inside double quotes a backslash escapes only ", \, $, ` and a newline, and is kept before anything else.
        assertEquals(List.of("--conf", "spark.driver.extraJavaOptions=-Dpattern=\\d+ -Dq=\"x\" -Dp=\\ $HOME", "onetwo"),
                EmrServerlessSparkRunner.splitParameters(
                        "--conf \"spark.driver.extraJavaOptions=-Dpattern=\\d+ -Dq=\\\"x\\\" -Dp=\\\\ \\$HOME\" one\\\ntwo"));
        assertEquals(List.of(), EmrServerlessSparkRunner.splitParameters(null));
        assertEquals(List.of(), EmrServerlessSparkRunner.splitParameters("   "));
    }

    @Test
    void theImageIsTheReleasesPublicImageUnlessATemplateNamesAnother() {
        assertEquals("public.ecr.aws/emr-serverless/spark/emr-7.5.0:latest",
                EmrServerlessSparkRunner.image(null, "emr-7.5.0"));
        assertEquals("public.ecr.aws/emr-serverless/spark4/emr-spark-8.1.0:latest",
                EmrServerlessSparkRunner.image(null, "emr-spark-8.1.0"));
        assertEquals("registry.internal/emr-serverless/spark/emr-7.5.0:20250425",
                EmrServerlessSparkRunner.image("registry.internal/emr-serverless/spark/%s:20250425", "emr-7.5.0"));
        // The image a cluster's bootstrap left, which its steps get by id.
        String bootstrapped = "sha256:" + "2".repeat(64);
        assertEquals(bootstrapped, EmrServerlessSparkRunner.image(bootstrapped, "emr-7.5.0"));
        // A Hive run's is the release's Hive image, or its own template's.
        assertEquals("public.ecr.aws/emr-serverless/hive/emr-7.5.0:latest",
                EmrServerlessSparkRunner.image(null, "emr-7.5.0", HIVE));
        assertEquals("registry.internal/emr-serverless/hive/emr-7.5.0:20250425",
                EmrServerlessSparkRunner.image("registry.internal/emr-serverless/hive/%s:20250425", "emr-7.5.0", HIVE));
    }

    @Test
    void hiveRunsOnFlocisS3AndGlueWithTezInLocalModeThenTheRunsOwnArguments() {
        List<String> application = List.of("--hiveconf", "hive.exec.scratchdir=s3://bucket/scratch/",
                "-i", "s3://bucket/queries/init.sql", "-f", "s3://bucket/queries/report.sql",
                "--hivevar", "day=2026-11-03");

        List<String> arguments = EmrServerlessSparkRunner.hiveArguments(hiveJob(application), ENDPOINT, List.of());

        assertEquals(List.of(
                "--hiveconf", "fs.s3a.endpoint=http://floci:4566",
                "--hiveconf", "fs.s3a.endpoint.region=eu-west-1",
                "--hiveconf", "fs.s3a.path.style.access=true",
                "--hiveconf", "fs.s3a.committer.magic.track.commits.in.memory.enabled=true",
                "--hiveconf", "fs.s3.impl=org.apache.hadoop.fs.s3a.S3AFileSystem",
                "--hiveconf", "fs.AbstractFileSystem.s3.impl=org.apache.hadoop.fs.s3a.S3A",
                "--hiveconf", "aws.glue.endpoint=http://floci:4566",
                "--hiveconf", "hive.metastore.glue.catalogid=123456789012",
                "--hiveconf", "aws.region=eu-west-1",
                "--hiveconf", "tez.local.mode=true",
                "--hiveconf", "tez.am.client.am.port-range=40000-40100",
                "--hiveconf", "hive.exec.scratchdir=s3://bucket/scratch/",
                "-i", "s3://bucket/queries/init.sql", "-f", "s3://bucket/queries/report.sql",
                "--hivevar", "day=2026-11-03"), arguments);
    }

    @Test
    void aHiveRunStartsHiveAsTheImagesUserInTheReleasesHiveImageAfterItsScratchFoldersLocalPath() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);

        runner(builder).run(hiveJob(List.of("--hiveconf", "hive.exec.scratchdir=s3://bucket/hive/scratch/",
                "-f", "s3://bucket/queries/report.sql")), progress);

        verify(builder).newContainer("public.ecr.aws/emr-serverless/hive/emr-7.5.0:latest");
        // As root, which makes the scratch folder's local path the image's user's, then hive as that user.
        verify(spec).withUser("root");
        verify(spec).withEntrypoint(argThat((List<String> entrypoint) -> entrypoint.size() == 5
                && List.of("/bin/bash", "-c").equals(entrypoint.subList(0, 2))
                && entrypoint.get(2).contains("setpriv --reuid=hadoop --regid=hadoop --clear-groups \"$@\"")
                // While it is still root, which owns Hive's lib folder.
                && entrypoint.get(2).contains(EmrServerlessSparkRunner.HIVE_ONE_SDK + "\nexport HOME=~hadoop\n")
                && List.of("floci", "/usr/bin/hive").equals(entrypoint.subList(3, 5))));
        verify(spec).withCmd(argThat((List<String> cmd) -> "--hiveconf".equals(cmd.getFirst())
                && List.of("-f", "s3://bucket/queries/report.sql").equals(cmd.subList(cmd.size() - 2, cmd.size()))));
        verify(spec).withEnv("FLOCI_HIVE_SCRATCH", "/hive/scratch/hadoop");
        verify(spec).withEnv(List.of("LOGS_DIR=/tmp/floci-hive-logs"));
        // Nothing of Spark's: no spark-defaults for a command, and no Spark configuration of its own.
        verify(spec, never()).withEnv(eq("FLOCI_SPARK_DEFAULTS"), anyString());
        verify(spec, never()).withEnv(eq("FLOCI_SPARK_LOG4J2"), anyString());
    }

    @Test
    void anEmrHiveStepRunsItsCommandOnItsClustersStorageWithItsHdfsAndMetastore() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        SparkJobRunner.Job step = hiveStep(List.of("-f", "s3://bucket/queries/report.q"));

        runner(builder).run(step, progress);

        verify(builder).newContainer("public.ecr.aws/emr-serverless/hive/emr-7.5.0:latest");
        // hive-script in place of hive, as the image's user, with Floci's settings after its --args.
        verify(spec).withEntrypoint(argThat((List<String> entrypoint) -> entrypoint.size() == 7
                && List.of("floci", "hive-script", "--run-hive-script", "--args")
                .equals(entrypoint.subList(3, 7))));
        verify(spec).withCmd(EmrServerlessSparkRunner.hiveArguments(step, ENDPOINT, List.of()));
        verify(spec).withNamedVolume("j-1-home", "/home/hadoop");
        verify(spec).withNamedVolume("j-1-mnt", "/mnt");
        // In its cluster's image, which holds Spark too, Hive reads its own Hadoop configuration.
        verify(spec).withEntrypoint(argThat((List<String> entrypoint) -> entrypoint.get(2).contains(
                "if [ -d /etc/hadoop/conf.hive ]; then export HADOOP_CONF_DIR=/etc/hadoop/conf.hive; fi")));
        verify(spec, never()).withEnv(eq("FLOCI_HADOOP_SITE"), anyString());
        verify(spec, never()).withEnv(eq("FLOCI_MAPRED_SITE"), anyString());
    }

    @Test
    void anEmrHiveStepsHiveReachesItsClustersHdfsAndMetastoreServiceOnItsPrimaryNode() {
        List<String> arguments = EmrServerlessSparkRunner.hiveArguments(
                hiveStep(List.of("--hiveconf", "hive.exec.reducers.max=8", "-f", "s3://bucket/queries/report.q")),
                ENDPOINT, List.of());

        int tez = arguments.indexOf("tez.am.client.am.port-range=40000-40100");
        assertEquals(List.of(
                "--hiveconf", "fs.defaultFS=hdfs://ip-10-0-0-1.ec2.internal:8020",
                "--hiveconf", "hive.metastore.client.factory.class="
                        + "org.apache.hadoop.hive.ql.metadata.SessionHiveMetaStoreClientFactory",
                "--hiveconf", "hive.metastore.uris=thrift://ip-10-0-0-1.ec2.internal:9083",
                "--hiveconf", "hive.exec.reducers.max=8", "-f", "s3://bucket/queries/report.q"),
                arguments.subList(tez + 1, arguments.size()));
    }

    @Test
    void anEmrHiveStepWhereNoMetastoreServiceRunsKeepsItsMetastoreInItsClustersMnt() {
        // As in a network namespace the steps share, where the service would listen on every interface.
        SparkJobRunner.Job step = hiveStep(List.of("-f", "s3://bucket/queries/report.q"));
        List<String> arguments = EmrServerlessSparkRunner.hiveArguments(step.onStorage(
                new SparkJobRunner.Storage("j-1-home", "j-1-mnt").withHdfs("ip-10-0-0-1.ec2.internal", "127.0.0.1",
                        null)), ENDPOINT, List.of());

        int tez = arguments.indexOf("tez.am.client.am.port-range=40000-40100");
        assertEquals(List.of(
                "--hiveconf", "fs.defaultFS=hdfs://ip-10-0-0-1.ec2.internal:8020",
                "--hiveconf", "hive.metastore.client.factory.class="
                        + "org.apache.hadoop.hive.ql.metadata.SessionHiveMetaStoreClientFactory",
                "--hiveconf", "javax.jdo.option.ConnectionURL="
                        + "jdbc:derby:;databaseName=/mnt/var/lib/hive/metastore_db;create=true",
                "--hiveconf", "hive.metastore.schema.verification=false",
                "--hiveconf", "datanucleus.schema.autoCreateAll=true",
                "-f", "s3://bucket/queries/report.q"),
                arguments.subList(tez + 1, arguments.size()));
        // Nor does its Spark steps' Hive client get one.
        assertFalse(EmrServerlessSparkRunner.sparkSubmitArguments(stepJob(Map.of()), ENDPOINT).stream()
                .anyMatch(argument -> argument.startsWith("spark.hadoop.hive.metastore.uris=")));
    }

    @Test
    void aHiveScratchFolderInS3LeadsToTheSamePathLocallyThenItsUsersFolder() {
        assertEquals("/hive/scratch/hadoop", EmrServerlessSparkRunner.hiveScratchMirror(
                List.of("--hiveconf", "hive.exec.scratchdir=s3://bucket/hive/scratch/")));
        // The last setting wins, in either form of the option and in any of Hadoop's S3 schemes.
        assertEquals("/later/hadoop", EmrServerlessSparkRunner.hiveScratchMirror(List.of(
                "--hiveconf", "hive.exec.scratchdir=s3://bucket/earlier", "-f", "s3://bucket/queries/report.sql",
                "-hiveconf", "hive.exec.scratchdir=s3a://bucket/later")));
        // Hive's CLI also takes the option and its setting as one argument, and a setting's value as the next one.
        assertEquals("/inline/hadoop", EmrServerlessSparkRunner.hiveScratchMirror(List.of(
                "--hiveconf", "hive.exec.scratchdir=s3://bucket/earlier",
                "--hiveconf=hive.exec.scratchdir=s3://bucket/inline")));
        assertEquals("/short/inline/hadoop", EmrServerlessSparkRunner.hiveScratchMirror(List.of(
                "--hiveconf=hive.exec.scratchdir=s3://bucket/earlier",
                "-hiveconf=hive.exec.scratchdir=s3://bucket/short/inline")));
        assertEquals("/separate/hadoop", EmrServerlessSparkRunner.hiveScratchMirror(List.of(
                "--hiveconf=hive.exec.scratchdir=s3://bucket/earlier",
                "--hiveconf", "hive.exec.scratchdir", "s3://bucket/separate", "-f", "s3://bucket/queries/report.sql")));
        assertEquals("/inline/separate/hadoop", EmrServerlessSparkRunner.hiveScratchMirror(List.of(
                "--hiveconf=hive.exec.scratchdir", "s3://bucket/inline/separate")));
        // Without a value, an option following it, Hive sets it to true, a local folder.
        assertNull(EmrServerlessSparkRunner.hiveScratchMirror(List.of(
                "--hiveconf", "hive.exec.scratchdir=s3://bucket/earlier", "--hiveconf", "hive.exec.scratchdir",
                "-f", "s3://bucket/queries/report.sql")));
        assertEquals("/later/hadoop", EmrServerlessSparkRunner.hiveScratchMirror(List.of(
                "--hiveconf", "hive.exec.scratchdir", "--hiveconf", "hive.exec.scratchdir=s3://bucket/later")));
        // Another setting's value, or a variable of the same name, is not the scratch folder.
        assertNull(EmrServerlessSparkRunner.hiveScratchMirror(List.of(
                "--hiveconf", "hive.exec.stagingdir", "hive.exec.scratchdir=s3://bucket/value",
                "--hivevar", "hive.exec.scratchdir=s3://bucket/variable")));
        assertEquals("/hadoop", EmrServerlessSparkRunner.hiveScratchMirror(
                List.of("--hiveconf", "hive.exec.scratchdir=s3://bucket")));
        assertEquals("/scratch/hadoop", EmrServerlessSparkRunner.hiveScratchMirror(
                List.of("--hiveconf", "hive.exec.scratchdir=s3://bucket/a/../scratch/")));
        assertEquals("/hive/scratch/hadoop", EmrServerlessSparkRunner.hiveScratchMirror(
                List.of("--hiveconf", "hive.exec.scratchdir=s3://bucket//hive/./scratch//")));
        // A local scratch folder, or Hive's default one, is the image user's to make.
        assertNull(EmrServerlessSparkRunner.hiveScratchMirror(
                List.of("--hiveconf", "hive.exec.scratchdir=/tmp/hive-scratch")));
        assertNull(EmrServerlessSparkRunner.hiveScratchMirror(List.of("-f", "s3://bucket/queries/report.sql")));
    }

    @Test
    void theDriverRunsInTheJobsImageAndNetworkNamedAndLabelledForItsService() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);

        runner(builder).run(job(Duration.ZERO), progress);

        verify(builder).newContainer("public.ecr.aws/emr-serverless/spark/emr-7.5.0:latest");
        verify(spec).withEntrypoint(List.of("/usr/lib/spark/bin/spark-submit"));
        verify(spec).withCmd(argThat((List<String> cmd) -> List.of("--master", "local[*]").equals(cmd.subList(0, 2))
                && "s3://bucket/jobs/app.jar".equals(cmd.getLast())));
        verify(spec).withName(ContainerStorageHelper.dockerName(config, "emr-serverless-00f0000000000001"));
        verify(spec).withDockerNetwork(Optional.of("floci-network"));
        verify(spec).withLabels(argThat(labels ->
                "emr-serverless".equals(labels.get(ContainerStorageHelper.SERVICE_LABEL))));
        // Its JVMs trust Floci's CA, the trust store going in with the CA bundle as the container is created.
        verify(spec).withJavaTrustStore();
        verify(lifecycle, never()).copyJavaTrustStore(anyString());
        // The release's image is not in an ECR repository, so it is pulled once, as the container is created.
        verify(lifecycle, never()).resolveImageForLaunch(anyString(), any(), any());
        // An EMR Serverless run has no cluster, nor its storage or network.
        verify(spec, never()).withNamedVolume(anyString(), anyString());
        verify(lifecycle, never()).connectToNetwork(anyString(), anyString());
    }

    @Test
    void aSparkRunsExecutorsRunApartOnlyOnANetworkOfContainers() {
        SparkJobRunner.Job apart = executorsJob(Map.of());

        assertTrue(EmrServerlessSparkRunner.executorsApart(apart, "floci-network"));
        assertTrue(EmrServerlessSparkRunner.executorsApart(apart, null));
        // On the host's network, or in another container's namespace, its ports would meet another run's.
        assertFalse(EmrServerlessSparkRunner.executorsApart(apart, "host"));
        assertFalse(EmrServerlessSparkRunner.executorsApart(apart, "container:floci"));
        // A job without them, a Hive run and a step keep their executors where they were.
        assertFalse(EmrServerlessSparkRunner.executorsApart(job(Duration.ZERO), "floci-network"));
        assertFalse(EmrServerlessSparkRunner.executorsApart(hiveJob(List.of()).withExecutors(EXECUTORS),
                "floci-network"));
        assertFalse(EmrServerlessSparkRunner.executorsApart(stepJob(Map.of()).withExecutors(EXECUTORS),
                "floci-network"));
    }

    @Test
    void aRunWhoseExecutorsRunApartTakesItsMasterFromItsContainerAndHasItsUiOn() {
        List<String> arguments = EmrServerlessSparkRunner.sparkSubmitArguments(
                executorsJob(Map.of("spark.ui.enabled", "false")), ENDPOINT);

        assertFalse(arguments.contains("--master"));
        // Floci's comes before the job's own, which wins.
        int on = arguments.indexOf("spark.ui.enabled=true");
        assertTrue(on > 0 && arguments.indexOf("spark.ui.enabled=false") > on);
        // Its kill links, which any container on the network reaches, the driver's container turns off.
        assertFalse(arguments.contains("spark.ui.killEnabled=false"));
    }

    @Test
    void whatTheDriversContainerForcesComesAfterTheRunsOwnOptionsAndBeforeItsPrimaryResource() {
        List<String> arguments = EmrServerlessSparkRunner.sparkSubmitArguments(job(null,
                List.of("--conf", "spark.ui.killEnabled=true", "--conf=spark.authenticate.secret=mine", "--master",
                        "local[2]", "s3://bucket/jobs/app.jar", "--conf", "for-the-app"),
                Map.of("spark.ui.killEnabled", "true"), Duration.ZERO).withExecutors(EXECUTORS), ENDPOINT);

        // The driver's container gives its master, secret and kill links after the options its count covers,
        // the run's own settings and options among them, and before the run's primary resource.
        int options = SparkExecutors.optionCount(arguments);
        assertEquals("s3://bucket/jobs/app.jar", arguments.get(options));
        assertTrue(arguments.subList(0, options).containsAll(List.of("spark.ui.killEnabled=true",
                "--conf=spark.authenticate.secret=mine", "--master", "local[2]")));
    }

    @Test
    void theDriverOfARunWhoseExecutorsRunApartStartsTheMasterWithTheRunsSecretThenFlociAsksItsUi() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        EmrServerlessSparkRunner runner = runner(builder);
        List<List<String>> asked = ContainerExecStubs.completeEveryExec(docker, "driver", 7L, "", "");

        runner.run(executorsJob(Map.of()), progress);

        // spark-submit, then how many of its arguments are options, which the wrapper puts its own after.
        ArgumentCaptor<List<String>> cmd = ArgumentCaptor.captor();
        verify(spec).withCmd(cmd.capture());
        verify(spec).withEntrypoint(List.of("/bin/bash", "-c", SparkExecutors.DRIVER_WRAPPER, "floci",
                "/usr/lib/spark/bin/spark-submit", String.valueOf(SparkExecutors.optionCount(cmd.getValue()))));
        assertEquals("s3://bucket/jobs/app.jar", cmd.getValue().get(SparkExecutors.optionCount(cmd.getValue())));
        verify(spec).withEnv(eq(SparkExecutors.SECRET_VARIABLE),
                argThat((String secret) -> secret.matches("[0-9a-f]{64}")));
        assertFalse(cmd.getValue().contains("--master"));
        assertEquals(List.of("curl", "-sf", "-m", "2", "http://127.0.0.1:4040/api/v1/applications"),
                asked.getFirst());
        verify(lifecycle, never()).createAndStart(any());
    }

    @Test
    void aRunsExecutorsContainersGoWithItsDriver() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("executor",
                Map.of()));
        EmrServerlessSparkRunner runner = runner(builder);
        // A driver whose settings leave dynamic allocation off, so its two executor instances start at once.
        ContainerExecStubs.completeEveryExec(docker, "driver", 0L, "[{\"id\": \"app-1\"}]", "");

        assertEquals(0, runner.run(executorsJob(Map.of()), progress).exitCode());

        verify(lifecycle, times(2)).createAndStart(any());
        verify(lifecycle, times(2)).stopAndRemove("executor", null, 0);
    }

    @Test
    void onTheHostsNetworkARunsExecutorsRunInItsDriversContainer() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        when(builder.resolveDockerNetwork(Optional.of("floci-network"))).thenReturn(Optional.of("host"));

        runner(builder).run(executorsJob(Map.of()), progress);

        verify(spec).withEntrypoint(List.of("/usr/lib/spark/bin/spark-submit"));
        verify(spec).withCmd(argThat((List<String> cmd) -> List.of("--master", "local[*]").equals(cmd.subList(0, 2))));
        verify(spec, never()).withEnv(eq(SparkExecutors.SECRET_VARIABLE), anyString());
    }

    @Test
    void anExecutorsContainerIsAWorkerOnTheDriversNetworkLabelledAsTheRunsAndGoesWhenRemoved() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("executor",
                Map.of()));
        when(lifecycle.resolveContainerNetworkIp("executor", "floci-network")).thenReturn("172.18.0.9");
        SparkExecutors.Containers containers = runner(builder).executorContainers(executorsJob(Map.of()),
                "sha256:image", "floci-network", "172.18.0.4", "secret", AWS_ENVIRONMENT,
                Map.of(ContainerStorageHelper.SERVICE_LABEL, "emr-serverless"));

        SparkExecutors.Worker worker = containers.start(2, FOUR_CORE_EXECUTOR);
        containers.remove(worker.containerId());

        assertEquals(new SparkExecutors.Worker("executor", "172.18.0.9"), worker);
        verify(builder).newContainer("sha256:image");
        verify(spec).withName(ContainerStorageHelper.dockerName(config, "emr-serverless-00f0000000000001-executor-2"));
        verify(spec).withEntrypoint(List.of("/bin/bash", "-c", SparkExecutors.WORKER_SCRIPT));
        // The driver's AWS environment, its master and secret, and the executor's cores and memory.
        verify(spec).withEnv(AWS_ENVIRONMENT);
        verify(spec).withEnv(SparkExecutors.SECRET_VARIABLE, "secret");
        verify(spec).withEnv(SparkExecutors.MASTER_VARIABLE, "172.18.0.4");
        verify(spec).withEnv(SparkExecutors.CORES_VARIABLE, "4");
        verify(spec).withEnv(SparkExecutors.MEMORY_VARIABLE, "14336");
        verify(spec).withDockerNetwork(Optional.of("floci-network"));
        verify(spec).withJavaTrustStore();
        // Found by a reset and the leftover sweep as the run's driver is.
        verify(spec).withLabels(argThat(labels -> "emr-serverless".equals(labels.get(ContainerStorageHelper.SERVICE_LABEL))
                && SparkExecutors.COMPONENT.equals(labels.get(ContainerStorageHelper.COMPONENT_LABEL))));
        verify(lifecycle).stopAndRemove("executor", null, 0);
    }

    @Test
    void anExecutorsContainerWhoseAddressCannotBeFoundGoesBeforeItIsTheRunsToRemove() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("executor",
                Map.of()));
        when(lifecycle.resolveContainerNetworkIp("executor", "floci-network"))
                .thenThrow(new IllegalStateException("no address"));
        SparkExecutors.Containers containers = runner(builder).executorContainers(executorsJob(Map.of()),
                "sha256:image", "floci-network", "172.18.0.4", "secret", AWS_ENVIRONMENT, Map.of());

        assertThrows(IllegalStateException.class, () -> containers.start(1, FOUR_CORE_EXECUTOR));

        verify(lifecycle).stopAndRemove("executor", null, 0);
    }

    @Test
    void aDriverThatCannotBeAskedAnswersNothing() {
        EmrServerlessSparkRunner runner = runner(mock(ContainerBuilder.class));
        when(docker.execCreateCmd("driver")).thenThrow(new NotFoundException("gone"));

        assertNull(runner.driver("driver").get(SparkExecutors.DRIVER_UI_PORT, "/api/v1/applications"));
    }

    @Test
    void theRunsOfAnApplicationShareOneCapacityWhichGoesWithTheLast() {
        EmrServerlessSparkRunner runner = runner();
        SparkExecutors.Capacity first = runner.joinCapacity("arn:aws:emr-serverless:eu-west-1:123456789012:/app/a");
        SparkExecutors.Capacity second = runner.joinCapacity("arn:aws:emr-serverless:eu-west-1:123456789012:/app/a");

        assertSame(first, second);
        assertNotSame(first, runner.joinCapacity("arn:aws:emr-serverless:eu-west-1:123456789012:/app/b"));
        // A run of no application shares it with none.
        assertNotSame(runner.joinCapacity(null), runner.joinCapacity(null));
        runner.leaveCapacity("arn:aws:emr-serverless:eu-west-1:123456789012:/app/a", first);
        assertSame(first, runner.joinCapacity("arn:aws:emr-serverless:eu-west-1:123456789012:/app/a"));
        runner.leaveCapacity("arn:aws:emr-serverless:eu-west-1:123456789012:/app/a", first);
        runner.leaveCapacity("arn:aws:emr-serverless:eu-west-1:123456789012:/app/a", first);
        assertNotSame(first, runner.joinCapacity("arn:aws:emr-serverless:eu-west-1:123456789012:/app/a"));
    }

    @Test
    void aDriverWhoseAddressLookupFailsLeavesItsApplicationsCapacity() {
        String application = "arn:aws:emr-serverless:eu-west-1:123456789012:/app/failed-driver";
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        when(builder.resolveDockerNetwork(Optional.of("floci-network"))).thenReturn(Optional.of("floci-network"));
        EmrServerlessSparkRunner runner = runner(builder);
        NotFoundException addressFailure = new NotFoundException("driver disappeared during address lookup");
        when(lifecycle.resolveContainerNetworkIp("driver", "floci-network")).thenThrow(addressFailure);
        SparkJobRunner.Job job = executorsJob(Map.of()).withExecutors(
                new SparkJobRunner.Executors(2, null, 0, 0, 0, application));
        SparkJobRunner.Outcome outcome = runner.run(job, progress);

        assertEquals(SparkJobRunner.Outcome.couldNotStart(addressFailure.getMessage()), outcome);
        verify(progress).started();
        verify(lifecycle).stopAndRemove("driver", null, 0);
        verify(lifecycle, never()).createAndStart(any());
        SparkExecutors.Capacity firstAfterFailure = runner.joinCapacity(application);
        runner.leaveCapacity(application, firstAfterFailure);
        SparkExecutors.Capacity secondAfterFailure = runner.joinCapacity(application);
        assertNotSame(firstAfterFailure, secondAfterFailure,
                "a balanced subsequent membership must retire its capacity object");
    }

    @Test
    void theReleasesImageForARunsExecutorsIsPulledWhenMissingBeforeTheDriverStarts() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        EmrServerlessSparkRunner runner = runner(builder);
        ContainerExecStubs.completeEveryExec(docker, "driver", 7L, "", "");

        runner.run(executorsJob(Map.of()), progress);

        // Not while the run waits for its executors, where a pull would hold up a cancel and the run's timeout.
        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).resolveImageForLaunch("public.ecr.aws/emr-serverless/spark/emr-7.5.0:latest",
                ImagePullBehavior.PREFER_CACHED, null);
        order.verify(lifecycle).create(any());
    }

    @Test
    void anArm64RunsDriverAndExecutorsImageAreThoseForArm64() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        EmrServerlessSparkRunner runner = runner(builder);
        when(lifecycle.create(any(), eq("linux/arm64"))).thenReturn("driver");
        when(lifecycle.resolveImageForLaunch(anyString(), eq(ImagePullBehavior.PREFER_CACHED), eq("linux/arm64")))
                .thenAnswer(pull -> new LaunchImage(pull.getArgument(0), null));
        ContainerExecStubs.completeEveryExec(docker, "driver", 7L, "", "");

        runner.run(executorsJob(Map.of()).withPlatform("linux/arm64"), progress);

        // The release's image is published for both architectures, so the driver's is created for arm64, and the
        // executors' pulled for it.
        verify(lifecycle).create(any(), eq("linux/arm64"));
        verify(lifecycle, never()).create(any());
        verify(lifecycle).resolveImageForLaunch("public.ecr.aws/emr-serverless/spark/emr-7.5.0:latest",
                ImagePullBehavior.PREFER_CACHED, "linux/arm64");
    }

    @Test
    void anArm64RunsCustomImageIsPulledForArm64() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        String custom = "123456789012.dkr.ecr.eu-west-1.amazonaws.com/spark/custom:7.5.0";
        String inFlocisRegistry = "localhost:5100/123456789012/eu-west-1/spark/custom:7.5.0";
        String pulled = "sha256:" + "6".repeat(64);
        when(builder.newContainer(anyString())).thenReturn(spec);
        when(builder.resolveImage(inFlocisRegistry)).thenReturn(inFlocisRegistry);
        when(ecr.rewriteImageUri(custom)).thenReturn(inFlocisRegistry);
        when(lifecycle.resolveImageForLaunch(inFlocisRegistry, ImagePullBehavior.DEFAULT, "linux/arm64"))
                .thenReturn(new LaunchImage(pulled, null));
        EmrServerlessSparkRunner runner = runner(builder);
        when(lifecycle.create(any(), eq("linux/arm64"))).thenReturn("driver");

        // The executors' worker type naming the same custom image.
        runner.run(customImageJob(custom).withExecutors(new SparkJobRunner.Executors(2, custom, 0, 0, 0, null))
                .withPlatform("linux/arm64"), progress);

        verify(lifecycle, times(2)).resolveImageForLaunch(inFlocisRegistry, ImagePullBehavior.DEFAULT, "linux/arm64");
        verify(builder).newContainer(pulled);
    }

    @Test
    void anArm64RunsExecutorsContainerStartsForArm64() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        when(lifecycle.createAndStart(any(), eq("linux/arm64")))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("executor", Map.of()));
        when(lifecycle.resolveContainerNetworkIp("executor", "floci-network")).thenReturn("172.18.0.9");
        SparkExecutors.Containers containers = runner(builder).executorContainers(
                executorsJob(Map.of()).withPlatform("linux/arm64"), "sha256:image", "floci-network", "172.18.0.4",
                "secret", AWS_ENVIRONMENT, Map.of());

        containers.start(1, FOUR_CORE_EXECUTOR);

        verify(lifecycle).createAndStart(any(), eq("linux/arm64"));
        verify(lifecycle, never()).createAndStart(any());
    }

    @Test
    void anApplicationsImageForItsExecutorsIsPulledBeforeTheDriverStarts() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        String custom = "123456789012.dkr.ecr.eu-west-1.amazonaws.com/spark/executor:7.5.0";
        String inFlocisRegistry = "localhost:5100/123456789012/eu-west-1/spark/executor:7.5.0";
        when(builder.newContainer(anyString())).thenReturn(spec);
        when(builder.resolveImage(inFlocisRegistry)).thenReturn(inFlocisRegistry);
        when(ecr.rewriteImageUri(custom)).thenReturn(inFlocisRegistry);
        when(lifecycle.resolveImageForLaunch(inFlocisRegistry, ImagePullBehavior.DEFAULT, null))
                .thenReturn(new LaunchImage("sha256:" + "5".repeat(64), null));
        EmrServerlessSparkRunner runner = runner(builder);
        ContainerExecStubs.completeEveryExec(docker, "driver", 7L, "", "");

        runner.run(executorsJob(Map.of()).withExecutors(new SparkJobRunner.Executors(2, custom, 0, 0, 0, null)),
                progress);

        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).resolveImageForLaunch(inFlocisRegistry, ImagePullBehavior.DEFAULT, null);
        order.verify(lifecycle).create(any());
    }

    @Test
    void anImageInAnEcrRepositoryRunsFromFlocisRegistryPulledForEveryRun() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        String custom = "123456789012.dkr.ecr.eu-west-1.amazonaws.com/spark/custom:7.5.0";
        String inFlocisRegistry = "localhost:5100/123456789012/eu-west-1/spark/custom:7.5.0";
        String pulled = "sha256:" + "3".repeat(64);
        when(builder.newContainer(anyString())).thenReturn(spec);
        when(builder.resolveImage(inFlocisRegistry)).thenReturn(inFlocisRegistry);
        when(ecr.rewriteImageUri(custom)).thenReturn(inFlocisRegistry);
        when(lifecycle.resolveImageForLaunch(inFlocisRegistry, ImagePullBehavior.DEFAULT, null))
                .thenReturn(new LaunchImage(pulled, "sha256:" + "4".repeat(64)));
        EmrServerlessSparkRunner runner = runner(builder);

        runner.run(customImageJob(custom), progress);
        runner.run(customImageJob(custom), progress);

        // By the id each pull resolved, which a push under the same tag cannot move.
        verify(lifecycle, times(2)).resolveImageForLaunch(inFlocisRegistry, ImagePullBehavior.DEFAULT, null);
        verify(builder, times(2)).newContainer(pulled);
        verify(builder, never()).newContainer(custom);
    }

    @Test
    void aCommandRunsThroughFlocisWrapperWithTheJobsSparkSettingsInItsEnvironment() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        List<String> command = List.of("bash", "-c", "aws s3 ls s3://bucket/");

        runner(builder).run(commandJob(command, Map.of("spark.executor.cores", "4")), progress);

        // bash -c runs Floci's wrapper with "floci" as its $0 and the command as "$@".
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.size() == 4
                && entrypoint.subList(0, 2).equals(List.of("/bin/bash", "-c"))
                && entrypoint.get(2).contains("export SPARK_CONF_DIR=") && entrypoint.get(2).endsWith("exec \"$@\"")
                && "floci".equals(entrypoint.get(3))));
        verify(spec).withCmd(command);
        verify(spec).withEnv(eq("FLOCI_SPARK_DEFAULTS"),
                argThat(file -> file.startsWith("spark.master local[*]\n")
                        && file.endsWith("\nspark.executor.cores 4")));
        verify(spec, never()).withEntrypoint(List.of("/usr/lib/spark/bin/spark-submit"));
        verify(spec).withJavaTrustStore();
        // The job's own variables, a cluster's spark-env exports, beside Floci's (none from this test's mock).
        verify(spec).withEnv(List.of("PYSPARK_PYTHON=/usr/bin/python3.11"));
        verify(spec, never()).withEnv(eq("FLOCI_HADOOP_SITE"), anyString());
    }

    @Test
    void aJobsVariablesFollowFlocisAndReplaceOneOfTheSameName() {
        Map<String, String> jobs = new LinkedHashMap<>();
        jobs.put("AWS_REGION", "us-east-1");
        jobs.put("SPARK_DAEMON_JAVA_OPTS", "-Dkey=value -Xmx1g");

        assertEquals(List.of("AWS_REGION=us-east-1", "AWS_ENDPOINT_URL=http://floci:4566",
                        "SPARK_DAEMON_JAVA_OPTS=-Dkey=value -Xmx1g"),
                EmrServerlessSparkRunner.environment(
                        List.of("AWS_REGION=eu-west-1", "AWS_ENDPOINT_URL=http://floci:4566"), jobs));
        assertEquals(List.of("AWS_REGION=eu-west-1"),
                EmrServerlessSparkRunner.environment(List.of("AWS_REGION=eu-west-1"), Map.of()));
    }

    @Test
    void anArchiveInS3IsPutInTheContainerAndUnpackedIntoTheWorkingDirectoryBeforeTheDriverStarts() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        EmrServerlessSparkRunner runner = runner(builder);
        CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd("driver")).thenReturn(copy);
        Map<String, String> copied = copiedIn(copy);
        inS3("bucket", "envs/pyspark_venv.tar.gz", "a virtual environment");
        inS3("bucket", "libs/deps.zip", "some libraries");

        runner.run(archiveJob(List.of("s3://bucket/envs/pyspark_venv.tar.gz#environment", "s3a://bucket/libs/deps.zip",
                "file:///opt/local.tar.gz#local")), progress);

        // The guide's virtual environment by the name after its "#", one without a "#" by its file's; Spark alone
        // unpacks one outside S3.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.subList(0, 2).equals(List.of("/bin/bash", "-c"))
                && entrypoint.get(2).contains("tar -xf") && entrypoint.subList(3, entrypoint.size()).equals(List.of(
                        "floci", "/tmp/floci-archives/1/pyspark_venv.tar.gz", "environment",
                        "/tmp/floci-archives/2/deps.zip", "deps.zip", "--", "/usr/lib/spark/bin/spark-submit"))));
        verify(spec).withCmd(argThat((List<String> cmd) -> "s3://bucket/jobs/app.jar".equals(cmd.getLast())));
        // Each streamed into the created container, extracted at its root.
        assertEquals(Map.of("tmp/floci-archives/1/pyspark_venv.tar.gz", "a virtual environment",
                "tmp/floci-archives/2/deps.zip", "some libraries"), copied);
        InOrder order = inOrder(lifecycle, copy);
        order.verify(lifecycle).create(any());
        order.verify(copy, times(2)).withRemotePath("/");
        order.verify(lifecycle).startCreated(eq("driver"), any());
    }

    @Test
    void anArchiveThatCannotBeReadFailsTheRunNamingItAndNeverStartsItsDriver() {
        EmrServerlessSparkRunner runner = runner();
        when(s3.headObject("bucket", "envs/missing.tar.gz"))
                .thenThrow(new AwsException("NoSuchKey", "The specified key does not exist.", 404));

        SparkJobRunner.Outcome outcome = runner.run(
                archiveJob(List.of("s3://bucket/envs/missing.tar.gz#environment")), progress);

        assertEquals("Could not read the archive s3://bucket/envs/missing.tar.gz: The specified key does not exist.",
                outcome.failure());
        verify(lifecycle, never()).startCreated(anyString(), any());
        verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void aRunStoppedWhileItsImageIsPulledPutsInNoArchiveAndNeverStartsItsDriver() {
        EmrServerlessSparkRunner runner = runner();
        SparkJobRunner.Job job = archiveJob(List.of("s3://bucket/envs/pyspark_venv.tar.gz#environment"));
        inS3("bucket", "envs/pyspark_venv.tar.gz", "a virtual environment");
        when(lifecycle.create(any())).thenAnswer(pulling -> {
            runner.stop(job.runId());
            return "driver";
        });

        SparkJobRunner.Outcome outcome = runner.run(job, progress);

        assertTrue(outcome.stopped());
        verify(s3, never()).openObject(anyString(), anyString(), any());
        verify(docker, never()).copyArchiveToContainerCmd(anyString());
        verify(lifecycle, never()).startCreated(anyString(), any());
        verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void aCancelWhileAnArchiveStreamsInEndsItsCopyAndPutsInNoOther() {
        EmrServerlessSparkRunner runner = runner();
        inS3("bucket", "libs/deps.zip", "some libraries");
        // A 1 GiB archive, the run cancelled once its first bytes are read.
        long size = 1L << 30;
        AtomicLong served = new AtomicLong();
        InputStream body = new InputStream() {
            @Override
            public int read() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                int read = (int) Math.min(length, size - served.get());
                if (read == 0) {
                    return -1;
                }
                served.addAndGet(read);
                return read;
            }
        };
        when(s3.openObject("bucket", "envs/large.tar.gz", null)).thenReturn(new S3Service.ObjectRead(
                new S3Object("bucket", "envs/large.tar.gz", size, "application/gzip", null), body));
        when(progress.cancelled()).thenAnswer(look -> served.get() > 0);
        CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd("driver")).thenReturn(copy);
        when(copy.withTarInputStream(any())).thenAnswer(streaming -> {
            // Read to its end, as the daemon does.
            streaming.<InputStream>getArgument(0).transferTo(OutputStream.nullOutputStream());
            return copy;
        });

        SparkJobRunner.Outcome outcome = runner.run(
                archiveJob(List.of("s3://bucket/envs/large.tar.gz#environment", "s3://bucket/libs/deps.zip")),
                progress);

        assertTrue(outcome.stopped());
        assertTrue(served.get() < 16L << 20, served.get() + " bytes were read");
        verify(s3, never()).openObject("bucket", "libs/deps.zip", null);
        verify(lifecycle, never()).startCreated(anyString(), any());
        verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void aJobWithoutArchivesInS3StartsSparkSubmitItself() {
        assertEquals(List.of("/usr/lib/spark/bin/spark-submit"), EmrServerlessSparkRunner.unpackingFirst(
                EmrServerlessSparkRunner.archives(archiveJob(List.of("https://example.com/env.tar.gz#environment"))),
                List.of("/usr/lib/spark/bin/spark-submit")));
    }

    @Test
    void theWrapperUnpacksEachArchiveAsSparkDoesThenRunsWhatFollows(@TempDir Path directory) throws Exception {
        Path environment = directory.resolve("pyspark_venv.TAR.GZ");
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(
                new GZIPOutputStream(Files.newOutputStream(environment)))) {
            byte[] python = bytes("#!/bin/sh\necho python\n");
            TarArchiveEntry entry = new TarArchiveEntry("bin/python");
            entry.setSize(python.length);
            entry.setMode(0755);
            tar.putArchiveEntry(entry);
            tar.write(python);
            tar.closeArchiveEntry();
        }
        Path libraries = directory.resolve("deps.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(libraries))) {
            zip.putNextEntry(new ZipEntry("lib/helpers.py"));
            zip.write(bytes("helpers"));
            zip.closeEntry();
        }
        Path notes = directory.resolve("notes.txt");
        Files.writeString(notes, "notes");
        Path workingDirectory = Files.createDirectory(directory.resolve("home"));
        String script = "./environment/bin/python; cat deps.zip/lib/helpers.py readme; echo \" $1\"";
        List<String> entrypoint = EmrServerlessSparkRunner.unpackingFirst(List.of(
                archive(environment, "environment"), archive(libraries, "deps.zip"), archive(notes, "readme")),
                List.of("sh", "-c", script, "sh"));
        List<String> command = new ArrayList<>(entrypoint);
        command.add("argument");

        BootstrapProcess.Result ended = BootstrapProcess.run(new ProcessBuilder(command).directory(
                workingDirectory.toFile()));

        assertEquals(new BootstrapProcess.Result(0, "python\nhelpersnotes argument\n"), ended);
    }

    @Test
    void anArchiveThatDoesNotUnpackStopsTheDriverFromStarting(@TempDir Path directory) throws Exception {
        Path broken = Files.writeString(directory.resolve("broken.tar.gz"), "not an archive");
        List<String> command = EmrServerlessSparkRunner.unpackingFirst(List.of(archive(broken, "environment")),
                List.of("echo", "started"));

        BootstrapProcess.Result ended = BootstrapProcess.run(new ProcessBuilder(command).directory(directory.toFile()));

        assertTrue(ended.exit() != 0);
        assertFalse(ended.output().contains("started"));
    }

    @Test
    void aStepMountsItsClustersStorageWhoseHdfsOnItsPrimaryNodeIsWherePathsWithoutASchemeLead() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        SparkJobRunner.Job job = stepJob(Map.of("spark.hadoop.fs.defaultFS", "file:///"));

        runner(builder).run(job, progress);
        List<String> arguments = EmrServerlessSparkRunner.sparkSubmitArguments(job, ENDPOINT);

        verify(spec).withNamedVolume(STORAGE.home(), "/home/hadoop");
        verify(spec).withNamedVolume(STORAGE.mnt(), "/mnt");
        // The primary node's name leads to its HDFS, as the cluster's DNS resolves it, on the cluster's own network,
        // which the driver joins before it starts.
        verify(spec).withExtraHost("ip-10-0-0-1.ec2.internal", "172.18.0.5");
        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).create(any());
        order.verify(lifecycle).connectToNetwork("driver", "floci-aws-emr-j-2AXXXXXXGAPLF-hdfs");
        order.verify(lifecycle).startCreated(eq("driver"), any());
        // After Floci's S3 settings, before the cluster's spark-defaults, which win.
        assertEquals("spark.hadoop.aws.region=eu-west-1", arguments.get(19));
        // HDFS's own client, in place of the core-site.xml's view, which Hive in Spark could not write a table with.
        assertEquals(List.of(
                "--conf", "spark.hadoop.fs.defaultFS=hdfs://ip-10-0-0-1.ec2.internal:8020",
                "--conf", "spark.hadoop.fs.hdfs.impl=org.apache.hadoop.hdfs.DistributedFileSystem",
                "--conf", "spark.sql.streaming.checkpointFileManagerClass="
                        + "org.apache.spark.sql.execution.streaming.FileSystemBasedCheckpointFileManager",
                "--conf", "spark.hadoop.fs.defaultFS=file:///",
                "s3://bucket/jobs/report.py"), arguments.subList(20, arguments.size()));
    }

    @Test
    void aStepWritesTheCopiesOfABlockItsCoreNodesKeep() {
        SparkJobRunner.Job job = stepJob(Map.of()).onStorage(STORAGE.withReplication(2));

        assertTrue(EmrServerlessSparkRunner.sparkSubmitArguments(job, "http://floci:4566", List.of())
                .contains("spark.hadoop.dfs.replication=2"));
        assertTrue(EmrServerlessSparkRunner.hiveArguments(job, "http://floci:4566", List.of())
                .contains("dfs.replication=2"));
    }

    @Test
    void aStepOnAClusterWhoseHdfsIsOnItsPrimaryNodeLeavesTheCopiesOfABlockAlone() {
        SparkJobRunner.Job job = stepJob(Map.of()).onStorage(STORAGE);

        assertFalse(EmrServerlessSparkRunner.sparkSubmitArguments(job, "http://floci:4566", List.of()).toString()
                .contains("dfs.replication"));
    }

    @Test
    void aStepOnItsClustersYarnSubmitsToItsResourceManagerWithExecutorsSizedForItsNode() {
        SparkJobRunner.Job job = stepJob(Map.of("spark.executor.memory", "2g"))
                .onStorage(STORAGE.withYarn("ip-10-0-0-1.ec2.internal", "http://floci:4566"));

        List<String> arguments = EmrServerlessSparkRunner.sparkSubmitArguments(job, "http://floci:4566",
                List.of("AWS_ACCESS_KEY_ID=ASIASTEP", "AWS_ENDPOINT_URL=http://floci:4566"));

        assertEquals(List.of("--master", "yarn"), arguments.subList(0, 2));
        // The ResourceManager on the primary node; Spark's jars from the node's image, which is the step's.
        assertTrue(arguments.contains("spark.hadoop.yarn.resourcemanager.hostname=ip-10-0-0-1.ec2.internal"));
        assertTrue(arguments.contains("spark.yarn.jars=local:/usr/lib/spark/jars/*"));
        // Floci's sizes for the NodeManager, which the cluster's spark-defaults replace.
        int floci = arguments.indexOf("spark.executor.memory=1g");
        assertTrue(floci > 0 && arguments.indexOf("spark.executor.memory=2g") > floci, arguments.toString());
        assertTrue(arguments.contains("spark.executor.cores=1"));
        assertTrue(arguments.contains("spark.driver.memory=2g"));
        assertTrue(arguments.contains("spark.driver.cores=1"));
        // A client-mode driver is reached at its container's name on the cluster's network.
        assertTrue(arguments.contains("spark.driver.host=s-1a2b3c4d5e6f7"));
        assertTrue(arguments.contains("spark.driver.bindAddress=0.0.0.0"));
        // Each executor, and a cluster-mode driver, has the step's AWS environment.
        assertTrue(arguments.contains("spark.executorEnv.AWS_ACCESS_KEY_ID=ASIASTEP"));
        assertTrue(arguments.contains("spark.yarn.appMasterEnv.AWS_ACCESS_KEY_ID=ASIASTEP"));
        assertTrue(arguments.contains("spark.executorEnv.AWS_ENDPOINT_URL=http://floci:4566"));
        assertTrue(arguments.contains("spark.hadoop.fs.s3a.endpoint=http://floci:4566"));
        assertTrue(EmrServerlessSparkRunner.sparkDefaultsFile(job, "http://floci:4566", List.of())
                .startsWith("spark.master yarn\n"));
    }

    @Test
    void aStepNotOnYarnRunsInLocalModeWithNoneOfYarnsSettings() {
        List<String> arguments = EmrServerlessSparkRunner.sparkSubmitArguments(stepJob(Map.of()), ENDPOINT);

        assertEquals(List.of("--master", "local[*]"), arguments.subList(0, 2));
        assertFalse(arguments.stream().anyMatch(argument -> argument.startsWith("spark.yarn.")
                || argument.startsWith("spark.executorEnv.") || argument.startsWith("spark.driver.host=")),
                arguments.toString());
    }

    @Test
    void aStepOnItsClustersYarnJoinsItsNetworkByTheNameItsDriverIsReachedAtAndReachesFlociThere() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        SparkJobRunner.Job job = stepJob(Map.of())
                .onStorage(STORAGE.withYarn("ip-10-0-0-1.ec2.internal", "http://floci-on-cluster:4566"));
        when(awsEnv.sdkBaselineEnv("eu-west-1", Optional.empty(), "http://floci-on-cluster:4566", Optional.empty(),
                "123456789012")).thenReturn(List.of("AWS_ACCESS_KEY_ID=ASIASTEP"));

        runner(builder).run(job, progress);

        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).create(any());
        order.verify(lifecycle).ensureConnected("driver", "floci-aws-emr-j-2AXXXXXXGAPLF-hdfs", "s-1a2b3c4d5e6f7");
        order.verify(lifecycle).startCreated(eq("driver"), any());
        verify(lifecycle, never()).connectToNetwork(anyString(), anyString());
        // Floci's address on the cluster's network, where its executors reach it too, not on Floci's own.
        verify(spec).withCmd(argThat((List<String> arguments) ->
                arguments.contains("spark.hadoop.fs.s3a.endpoint=http://floci-on-cluster:4566")
                        && arguments.contains("spark.executorEnv.AWS_ACCESS_KEY_ID=ASIASTEP")));
    }

    @Test
    void aCommandsOwnSparkSubmitOnItsClustersYarnGetsItsSettingsInItsSparkDefaults() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        SparkJobRunner.Job job = commandJob(List.of("bash", "-c", "spark-submit s3://bucket/app.py"), Map.of())
                .onStorage(STORAGE.withYarn("ip-10-0-0-1.ec2.internal", "http://floci-on-cluster:4566"));
        when(awsEnv.sdkBaselineEnv(eq("eu-west-1"), eq(Optional.empty()), eq("http://floci-on-cluster:4566"), any(),
                anyString())).thenReturn(List.of("AWS_ACCESS_KEY_ID=ASIASTEP"));

        runner(builder).run(job, progress);

        verify(spec).withEnv(eq("FLOCI_SPARK_DEFAULTS"), argThat(file -> file.startsWith("spark.master yarn\n")
                && file.contains("spark.hadoop.yarn.resourcemanager.hostname ip-10-0-0-1.ec2.internal")
                && file.contains("spark.executorEnv.AWS_ACCESS_KEY_ID ASIASTEP")));
    }

    @Test
    void aSparkStepOnAClusterWithHiveUsesItsMetastoreServiceUnlessItsSparkHiveSiteNamesAnother() {
        String glue = "spark.hadoop.hive.metastore.client.factory.class="
                + "com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory";
        SparkJobRunner.Job job = stepJob(Map.of("spark.hadoop.hive.metastore.client.factory.class",
                "com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory"))
                .onStorage(STORAGE.withHiveMetastore("thrift://ip-10-0-0-1.ec2.internal:9083"));

        List<String> arguments = EmrServerlessSparkRunner.sparkSubmitArguments(job, ENDPOINT);

        int metastore = arguments.indexOf("spark.hadoop.hive.metastore.uris=thrift://ip-10-0-0-1.ec2.internal:9083");
        assertTrue(metastore > 0, arguments.toString());
        assertEquals("--conf", arguments.get(metastore - 1));
        // Floci's, before the cluster's spark-defaults and spark-hive-site, which win.
        assertTrue(arguments.indexOf(glue) > metastore, arguments.toString());
    }

    @Test
    void aMapReduceJobOnItsClustersYarnRunsThereFromItsNodesLayout() {
        String site = EmrServerlessSparkRunner.mapredSite(stepJob(Map.of()).onStorage(ON_YARN), AWS_ENVIRONMENT);

        assertTrue(site.contains("<name>mapreduce.framework.name</name><value>yarn</value>"), site);
        assertFalse(site.contains("<value>local</value>"), site);
        assertFalse(site.contains("mapreduce.jobtracker.staging.root.dir"), site);
        // The image's real layout, which yarn.application.classpath's default does not name, and S3A's AWS SDK.
        assertTrue(site.contains("<name>mapreduce.application.classpath</name><value>"
                + "/usr/lib/hadoop-mapreduce/*,/usr/lib/hadoop-mapreduce/lib/*,/usr/lib/hadoop/*,/usr/lib/hadoop/lib/*,"
                + "/usr/lib/hadoop-hdfs/*,/usr/lib/hadoop-hdfs/lib/*,/usr/lib/hadoop-yarn/*,/usr/lib/hadoop-yarn/lib/*,"
                + "/usr/lib/hadoop-lzo/lib/*,/usr/share/aws/aws-java-sdk-v2/*,"
                + "/usr/share/aws/emr/serverless-goodies/lib/emr-serverless-spark-goodies.jar</value>"), site);
        // Java 17's options, the MapReduce home and the step's AWS environment, for all three kinds of container.
        for (String name : List.of("yarn.app.mapreduce.am.env", "mapreduce.map.env", "mapreduce.reduce.env")) {
            assertTrue(site.contains("<name>" + name + "</name><value>JDK_JAVA_OPTIONS="
                    + EmrServerlessSparkRunner.yarnJavaOptions() + ",HADOOP_MAPRED_HOME=/usr/lib/hadoop-mapreduce,"
                    + "AWS_ACCESS_KEY_ID=ASIASTEP,AWS_ENDPOINT_URL=http://floci:4566</value>"), site);
        }
        assertTrue(site.contains("<name>yarn.app.mapreduce.am.resource.mb</name><value>1024</value>"), site);
        assertTrue(site.contains("<name>mapreduce.map.memory.mb</name><value>768</value>"), site);
        assertTrue(site.contains("<name>mapreduce.reduce.memory.mb</name><value>768</value>"), site);
    }

    @Test
    void anEmrSparkReleasesMapReduceRunsFromTheHadoopItsImageKeepsApart() {
        String site = EmrServerlessSparkRunner.mapredSite(
                onRelease("emr-spark-8.1.0", stepJob(Map.of()).onStorage(ON_YARN)), List.of());

        assertTrue(site.contains("<value>/opt/hadoop-mapreduce/*,/opt/hadoop-mapreduce/lib/*,/opt/hadoop/*"), site);
        assertTrue(site.contains(",HADOOP_MAPRED_HOME=/opt/hadoop-mapreduce</value>"), site);
    }

    @Test
    void aClustersMapredSiteReplacesFlocisSettingsOnYarn() {
        String site = EmrServerlessSparkRunner.mapredSite(hadoopJob(null,
                Map.of("mapreduce.map.memory.mb", "2048"), Map.of(), Map.of()).onStorage(ON_YARN), List.of());

        assertTrue(site.indexOf("<value>2048</value>") > site.indexOf("<value>768</value>"), site);
    }

    @Test
    void aJobOnYarnKeepsItsStagingFolderInHdfsWithHdfssOwnClient() {
        String site = EmrServerlessSparkRunner.hadoopSite(stepJob(Map.of()).onStorage(ON_YARN), ENDPOINT);

        assertTrue(site.contains("<name>fs.hdfs.impl</name>"
                + "<value>org.apache.hadoop.hdfs.DistributedFileSystem</value>"), site);
        assertFalse(site.contains("viewfs"), site);
        // Where there is no YARN, the local job runner's staging folder needs the view.
        assertTrue(EmrServerlessSparkRunner.hadoopSite(stepJob(Map.of()), ENDPOINT).contains("viewfs"));
    }

    @Test
    void aStepOnYarnRunsItsHadoopCommandFromTheNodesHadoopAndKnowsItsResourceManager() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);

        runner(builder).run(commandJob(List.of("bash", "-c", "hadoop version"), Map.of(), ON_YARN), progress);

        verify(spec).withEnv("FLOCI_HADOOP_ROOT", "/usr/lib");
        verify(spec).withEnv("FLOCI_YARN_SITE",
                "<property><name>yarn.resourcemanager.hostname</name><value>ip-10-0-0-1.ec2.internal</value></property>");
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(String.join("\n",
                "  if [ -n \"${FLOCI_YARN_SITE:-}\" ]; then",
                "    site yarn-site.xml \"$FLOCI_YARN_SITE\"",
                "  fi"))
                && entrypoint.get(2).contains(
                        "\"export HADOOP_HOME=$r/hadoop HADOOP_COMMON_HOME=$r/hadoop HADOOP_LIBEXEC_DIR=$r/hadoop/libexec"
                                + " HADOOP_HDFS_HOME=$r/hadoop-hdfs HADOOP_MAPRED_HOME=$r/hadoop-mapreduce"
                                + " HADOOP_YARN_HOME=$r/hadoop-yarn\"")
                && entrypoint.get(2).contains("'export HADOOP_CLASSPATH=\"${HADOOP_CLASSPATH:-}:"
                        + "/usr/share/aws/aws-java-sdk-v2/*:"
                        + "/usr/share/aws/emr/serverless-goodies/lib/emr-serverless-spark-goodies.jar\"'")
                && entrypoint.get(2).contains("\"exec $r/hadoop/bin/hadoop \\\"\\$@\\\"\" > /tmp/floci-bin/hadoop")
                && entrypoint.get(2).contains("\"exec /tmp/floci-bin/hadoop jar $r/hadoop-mapreduce/hadoop-streaming.jar"
                        + " \\\"\\$@\\\"\" > /tmp/floci-bin/hadoop-streaming")));
    }

    @Test
    void aStepOnAnEmrSparkReleasesYarnRunsItsHadoopCommandFromTheHadoopItsImageKeepsApart() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);

        runner(builder).run(onRelease("emr-spark-8.1.0",
                commandJob(List.of("bash", "-c", "hadoop version"), Map.of(), ON_YARN)), progress);

        verify(spec).withEnv("FLOCI_HADOOP_ROOT", "/opt");
    }

    @Test
    void aStepWhereThereIsNoYarnKeepsTheLocalJobRunnersHadoop() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);

        runner(builder).run(commandJob(List.of("bash", "-c", "hadoop version"), Map.of(), STORAGE), progress);

        verify(spec, never()).withEnv(eq("FLOCI_HADOOP_ROOT"), anyString());
        verify(spec, never()).withEnv(eq("FLOCI_YARN_SITE"), anyString());
    }

    @Test
    void aHiveStepOnItsClustersYarnRunsStockTezThereInPlaceOfEmrServerlesssPlugins() {
        List<String> arguments = EmrServerlessSparkRunner.hiveArguments(
                hiveJob(List.of("-e", "select 1")).onStorage(ON_YARN), ENDPOINT, AWS_ENVIRONMENT);

        assertFalse(arguments.contains("tez.local.mode=true"), arguments.toString());
        for (String setting : List.of(
                "tez.local.mode=false",
                "yarn.resourcemanager.hostname=ip-10-0-0-1.ec2.internal",
                "tez.framework.client=org.apache.tez.client.TezYarnClient",
                "tez.am.class=org.apache.tez.dag.app.DAGAppMaster",
                "tez.task.class=org.apache.tez.runtime.task.TezChild",
                "tez.am.yarn.scheduler.class=org.apache.tez.dag.app.rm.YarnTaskSchedulerService",
                "tez.am.yarn.communicator.class=org.apache.tez.dag.app.TezTaskCommunicatorImpl",
                "tez.am.yarn.container-launcher.class=org.apache.tez.dag.app.launcher.TezContainerLauncherImpl",
                "tez.am.shuffle.auxiliary-service.id=mapreduce_shuffle",
                "tez.am.client.am.port-range=40000-40100",
                "tez.am.task.am.port-range=40101-40200",
                // Left for the application master's own node to fill in.
                "tez.am.client.am.hostname=${env.NM_HOST}",
                "tez.am.task.am.hostname=${env.NM_HOST}",
                "tez.am.client.am.bind-host=0.0.0.0",
                "tez.am.task.am.bind-host=0.0.0.0",
                "tez.shuffle.bind-host=0.0.0.0",
                "tez.lib.uris=file:///usr/lib/tez/,file:///usr/lib/tez/lib/",
                "tez.use.cluster.hadoop-libs=false",
                "tez.am.resource.memory.mb=1024",
                "hive.tez.container.size=768",
                // A task's two would never match the one YARN's default scheduler grants.
                "hive.tez.cpu.vcores=1",
                "tez.runtime.intermediate-output.should-compress=false",
                "tez.runtime.intermediate-input.is-compressed=false")) {
            assertTrue(arguments.contains(setting), setting + " in " + arguments);
        }
        assertTrue(arguments.contains("tez.cluster.additional.classpath.prefix=$PWD/*:/usr/lib/hive/lib/hive-exec.jar:"
                + "/usr/lib/tez/*:/usr/lib/tez/lib/*:/usr/lib/hadoop-mapreduce/*:/usr/lib/hadoop-mapreduce/lib/*:"
                + "/usr/lib/hadoop/*:/usr/lib/hadoop/lib/*:/usr/lib/hadoop-hdfs/*:/usr/lib/hadoop-hdfs/lib/*:"
                + "/usr/lib/hadoop-yarn/*:/usr/lib/hadoop-yarn/lib/*:/usr/lib/hadoop-lzo/lib/*"), arguments.toString());
        String environment = "=JDK_JAVA_OPTIONS=" + EmrServerlessSparkRunner.yarnJavaOptions()
                + ",HIVE_HOME=/usr/lib/hive,TEZ_HOME=/usr/lib/tez,AWS_ACCESS_KEY_ID=ASIASTEP,"
                + "AWS_ENDPOINT_URL=http://floci:4566";
        assertTrue(arguments.contains("tez.am.launch.env" + environment), arguments.toString());
        assertTrue(arguments.contains("tez.task.launch.env" + environment), arguments.toString());
    }

    @Test
    void aStepsMapReduceContainersGetItsAwsEnvironmentThroughARealRun() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        when(awsEnv.sdkBaselineEnv("eu-west-1", Optional.empty(), "http://floci:4566", Optional.empty(),
                "123456789012")).thenReturn(List.of("AWS_ACCESS_KEY_ID=ASIASTEP"));

        runner(builder).run(commandJob(List.of("bash", "-c", "hadoop version"), Map.of(), ON_YARN), progress);

        verify(spec).withEnv(eq("FLOCI_MAPRED_SITE"), argThat(site -> site.contains(
                "AWS_ACCESS_KEY_ID=ASIASTEP</value></property>")));
    }

    @Test
    void aHiveStepsTezContainersGetItsAwsEnvironmentThroughARealRun() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        when(awsEnv.sdkBaselineEnv("eu-west-1", Optional.empty(), "http://floci:4566", Optional.empty(),
                "123456789012")).thenReturn(List.of("AWS_ACCESS_KEY_ID=ASIASTEP"));

        runner(builder).run(hiveStep(List.of("-e", "select 1")).onStorage(ON_YARN), progress);

        verify(spec).withCmd(argThat((List<String> arguments) -> arguments.stream().anyMatch(argument ->
                argument.startsWith("tez.task.launch.env=") && argument.endsWith(",AWS_ACCESS_KEY_ID=ASIASTEP"))));
    }

    @Test
    void aHiveStepWhereThereIsNoYarnKeepsTezInLocalMode() {
        List<String> arguments = EmrServerlessSparkRunner.hiveArguments(hiveJob(List.of("-e", "select 1")), ENDPOINT,
                List.of());

        assertTrue(arguments.contains("tez.local.mode=true"), arguments.toString());
        assertFalse(arguments.toString().contains("tez.framework.client"), arguments.toString());
    }

    @Test
    void yarnsJavaOptionsOpenWhatHadoopReachesInto() {
        assertTrue(EmrServerlessSparkRunner.yarnJavaOptions().startsWith("--add-opens=java.base/java.lang=ALL-UNNAMED "));
        assertTrue(EmrServerlessSparkRunner.yarnJavaOptions().endsWith("--add-opens=java.base/sun.util.calendar=ALL-UNNAMED"));
    }

    @Test
    void aHadoopCommandOnAClusterWhoseCoreNodesHoldItsHdfsWritesTheirCopies() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        List<String> command = List.of("bash", "-c", "hadoop fs -put data.csv /input/");

        runner(builder).run(commandJob(command, Map.of(), STORAGE.withReplication(2)), progress);

        // In hdfs-site.xml, as a client reads it after core-site.xml and the image's says two.
        verify(spec).withEnv("FLOCI_HDFS_SITE", "<property><name>dfs.replication</name><value>2</value></property>");
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(String.join("\n",
                "  if [ -n \"${FLOCI_HDFS_SITE:-}\" ]; then",
                "    site hdfs-site.xml \"$FLOCI_HDFS_SITE\"",
                "  fi"))));
    }

    @Test
    void aHadoopCommandOnAClusterWhosePrimaryNodeHoldsItsHdfsLeavesTheImagesCopies() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);

        runner(builder).run(commandJob(List.of("bash", "-c", "true"), Map.of(), STORAGE), progress);

        verify(spec, never()).withEnv(eq("FLOCI_HDFS_SITE"), anyString());
    }

    @Test
    void aCommandOnAClusterGivesItsHadoopCommandTheClustersHdfsToo() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        List<String> command = List.of("bash", "-c", "hadoop fs -put data.csv /input/");

        runner(builder).run(commandJob(command, Map.of(), STORAGE), progress);

        verify(spec).withNamedVolume(STORAGE.home(), "/home/hadoop");
        verify(spec).withNamedVolume(STORAGE.mnt(), "/mnt");
        verify(spec).withEnv(eq("FLOCI_HADOOP_SITE"), argThat(site -> site.contains(
                "<property><name>fs.defaultFS</name><value>hdfs://ip-10-0-0-1.ec2.internal:8020</value></property>")
                && site.endsWith("<property><name>fs.viewfs.mounttable.ip-10-0-0-1.ec2.internal.linkFallback</name>"
                        + "<value>hdfs://ip-10-0-0-1.ec2.internal:8020</value></property>")));
        verify(spec).withEnv(eq("FLOCI_SPARK_DEFAULTS"), argThat(file -> file.contains(
                "\nspark.hadoop.fs.defaultFS hdfs://ip-10-0-0-1.ec2.internal:8020\n")));
        verify(spec).withExtraHost("ip-10-0-0-1.ec2.internal", "172.18.0.5");
        // A copy of the Hadoop configuration a spark-env export of HADOOP_CONF_DIR names, else of the image's, links
        // followed, its core-site.xml taking in the original's whole, whatever its XML, then the cluster's HDFS.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "source_conf=\"${HADOOP_CONF_DIR:-/etc/hadoop/conf}\"\n")
                && entrypoint.get(2).contains("cp -rL \"$source_conf/.\" \"$hadoop_conf\"")
                && entrypoint.get(2).contains(String.join("\n",
                        "    if [ -f \"$hadoop_conf/$1\" ]; then",
                        "      mv \"$hadoop_conf/$1\" \"$hadoop_conf/floci-original-$1\"",
                        "      include=\"<xi:include href=\\\"floci-original-$1\\\"/>\"",
                        "    fi",
                        "    printf '%s\\n' '<configuration xmlns:xi=\"http://www.w3.org/2001/XInclude\">' \"$include\""
                                + " \"$2\" '</configuration>' > \"$hadoop_conf/$1\""))
                && entrypoint.get(2).contains("\n  site core-site.xml \"$FLOCI_HADOOP_SITE\"\n")
                && entrypoint.get(2).contains(
                        "export HADOOP_CONF_DIR=\"$hadoop_conf\" PATH=\"/tmp/floci-bin:$PATH\"")));
    }

    @Test
    void aStepsHadoopCommandReachesFlocisS3AndRunsMapReduceLocally() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        List<String> command = List.of("hadoop-streaming", "-mapper", "cat", "-input", "s3://bucket/input/",
                "-output", "s3://bucket/output/");
        SparkJobRunner.Job job = commandJob(command, Map.of(), STORAGE);

        runner(builder).run(job, progress);

        // S3A on Floci's S3, as the step's spark-submit gets it.
        verify(spec).withEnv("FLOCI_HADOOP_SITE", EmrServerlessSparkRunner.hadoopSite(job, ENDPOINT));
        verify(spec).withEnv(eq("FLOCI_HADOOP_SITE"), argThat(site -> site.startsWith(
                "<property><name>fs.s3a.endpoint</name><value>http://floci:4566</value></property>\n")));
        verify(spec).withCmd(command);
        verify(spec).withEnv("FLOCI_MAPRED_SITE", "<property><name>mapreduce.framework.name</name><value>local"
                + "</value></property><property><name>mapreduce.jobtracker.staging.root.dir</name>"
                + "<value>/tmp/hadoop/mapred/staging</value></property>");
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "\n  site mapred-site.xml \"$FLOCI_MAPRED_SITE\"\n")
                // MapReduce from Spark's shaded Hadoop client, and S3A's AWS SDK, in a MapReduce home of Floci's,
                // which a command's own HADOOP_CLASSPATH does not take off the hadoop command's class path.
                && entrypoint.get(2).contains("\n  mkdir -p \"$hadoop_conf\" /tmp/floci-bin /tmp/floci-mapreduce\n")
                && entrypoint.get(2).contains(String.join("\n",
                        "  for jar in /usr/lib/spark/jars/hadoop-client-api-*.jar"
                                + " /usr/lib/spark/jars/hadoop-client-runtime-*.jar"
                                + " /usr/lib/spark/jars/emr-serverless-spark-goodies.jar; do",
                        "    if [ -f \"$jar\" ]; then ln -sf \"$jar\" /tmp/floci-mapreduce/;",
                        "    else echo \"Floci: this image has no $jar, so the hadoop command may not find MapReduce"
                                + " or S3A's AWS SDK.\" >&2; fi",
                        "  done"))
                && entrypoint.get(2).contains(
                        "\n  export HADOOP_MAPRED_HOME=\"${HADOOP_MAPRED_HOME:-/tmp/floci-mapreduce}\"\n")
                // The image's hadoop-streaming names /usr/lib/hadoop-mapreduce/hadoop-streaming.jar, which it lacks.
                && entrypoint.get(2).contains("printf '%s\\n' '#!/bin/bash'"
                        + " 'exec hadoop jar /usr/lib/hadoop/hadoop-streaming.jar \"$@\"'"
                        + " > /tmp/floci-bin/hadoop-streaming")
                && entrypoint.get(2).contains("chmod +x /tmp/floci-bin/hdfs /tmp/floci-bin/hadoop-streaming")));
    }

    @Test
    void aJarTheMapReduceHomeLinksThatTheImageLacksIsNamedOnStandardError(@TempDir Path folder) throws Exception {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);

        runner(builder).run(commandJob(List.of("hadoop", "fs", "-ls", "/"), Map.of(), STORAGE), progress);

        ArgumentCaptor<List<String>> entrypoint = ArgumentCaptor.captor();
        verify(spec).withEntrypoint(entrypoint.capture());
        String script = entrypoint.getValue().get(2);
        int start = script.indexOf("  for jar in ");
        // The wrapper's loop, on a Spark jars folder of the test's with one of the three jars in it.
        Path jars = Files.createDirectory(folder.resolve("jars"));
        Path home = Files.createDirectory(folder.resolve("home"));
        Files.writeString(jars.resolve("hadoop-client-api-3.4.0-amzn-1.jar"), "");
        String loop = script.substring(start, script.indexOf("\n  done\n", start) + 7)
                .replace("/usr/lib/spark/jars/", jars + "/").replace("/tmp/floci-mapreduce/", home + "/");
        BootstrapProcess.Result ended = BootstrapProcess.run(new ProcessBuilder("bash", "-c", loop)
                .directory(folder.toFile()));

        assertEquals(0, ended.exit(), ended.output());
        assertTrue(Files.isSymbolicLink(home.resolve("hadoop-client-api-3.4.0-amzn-1.jar")));
        assertTrue(ended.output().contains("Floci: this image has no " + jars + "/hadoop-client-runtime-*.jar, so the"
                + " hadoop command may not find MapReduce or S3A's AWS SDK."), ended.output());
        assertTrue(ended.output().contains("Floci: this image has no " + jars + "/emr-serverless-spark-goodies.jar"),
                ended.output());
        assertFalse(ended.output().contains("hadoop-client-api"), ended.output());
    }

    @Test
    void aStepsHadoopCommandLogsToStandardErrorWhenItsLog4jPropertiesIsEmpty() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);

        runner(builder).run(commandJob(List.of("hadoop", "fs", "-ls", "s3://bucket/"), Map.of(), STORAGE), progress);

        // The image's is empty; one a spark-env export's folder has is kept.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(String.join("\n",
                "  if [ ! -s \"$hadoop_conf/log4j.properties\" ]; then",
                "    printf '%s\\n' 'hadoop.root.logger=INFO,console' 'log4j.rootLogger=${hadoop.root.logger}'"
                        + " 'log4j.appender.console=org.apache.log4j.ConsoleAppender'"
                        + " 'log4j.appender.console.target=System.err'"
                        + " 'log4j.appender.console.layout=org.apache.log4j.PatternLayout'"
                        + " 'log4j.appender.console.layout.ConversionPattern=%d{ISO8601} %p %c (%t): %m%n'"
                        + " > \"$hadoop_conf/log4j.properties\"",
                "  fi"))));
    }

    @Test
    void aStepsHadoopCommandGetsItsClustersCoreSiteAfterFlocisS3AndItsHdfsEscapedForXml() {
        Map<String, String> coreSite = new LinkedHashMap<>();
        coreSite.put("fs.permissions.umask-mode", "077");
        coreSite.put("floci.note", "a<b & \"c\" 'd'");

        // A later property wins over an earlier one in the same file.
        assertEquals(String.join("\n",
                "<property><name>fs.s3a.endpoint</name><value>http://floci:4566</value></property>",
                "<property><name>fs.s3a.endpoint.region</name><value>eu-west-1</value></property>",
                "<property><name>fs.s3a.path.style.access</name><value>true</value></property>",
                "<property><name>fs.s3a.committer.magic.track.commits.in.memory.enabled</name><value>true</value>"
                        + "</property>",
                "<property><name>fs.s3.impl</name><value>org.apache.hadoop.fs.s3a.S3AFileSystem</value></property>",
                "<property><name>fs.AbstractFileSystem.s3.impl</name><value>org.apache.hadoop.fs.s3a.S3A</value>"
                        + "</property>",
                "<property><name>aws.glue.endpoint</name><value>http://floci:4566</value></property>",
                "<property><name>hive.metastore.glue.catalogid</name><value>123456789012</value></property>",
                "<property><name>aws.region</name><value>eu-west-1</value></property>",
                "<property><name>fs.defaultFS</name><value>hdfs://ip-10-0-0-1.ec2.internal:8020</value></property>",
                "<property><name>fs.hdfs.impl</name><value>org.apache.hadoop.fs.viewfs.ViewFileSystemOverloadScheme"
                        + "</value></property>",
                "<property><name>fs.viewfs.overload.scheme.target.hdfs.impl</name>"
                        + "<value>org.apache.hadoop.hdfs.DistributedFileSystem</value></property>",
                "<property><name>fs.viewfs.mounttable.ip-10-0-0-1.ec2.internal.link./tmp/hadoop/mapred/staging</name>"
                        + "<value>file:///tmp/hadoop/mapred/staging</value></property>",
                "<property><name>fs.viewfs.mounttable.ip-10-0-0-1.ec2.internal.linkFallback</name>"
                        + "<value>hdfs://ip-10-0-0-1.ec2.internal:8020</value></property>",
                "<property><name>fs.permissions.umask-mode</name><value>077</value></property>",
                "<property><name>floci.note</name><value>a&lt;b &amp; &quot;c&quot; &apos;d&apos;</value></property>"),
                EmrServerlessSparkRunner.hadoopSite(
                        clusterJob(List.of("hadoop", "fs", "-ls", "/"), coreSite, Map.of()), ENDPOINT));
    }

    @Test
    void aSparkStepWithLog4j2LinesRunsSparkSubmitOnAConfigurationOfItsOwnThatEndsWithThem() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        Map<String, String> sparkLog4j2 = new LinkedHashMap<>();
        sparkLog4j2.put("rootLogger.level", "warn");
        sparkLog4j2.put("logger.app.name", "com.example.app");

        runner(builder).run(clusterJob(null, Map.of(), sparkLog4j2), progress);

        verify(spec).withEnv("FLOCI_SPARK_LOG4J2", "rootLogger.level = warn\nlogger.app.name = com.example.app");
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.size() == 5
                && entrypoint.subList(0, 2).equals(List.of("/bin/bash", "-c"))
                && entrypoint.get(2).startsWith(String.join("\n",
                        "conf=/tmp/floci-spark-conf",
                        "mkdir -p \"$conf\"",
                        "cp -rL \"${SPARK_CONF_DIR:-/usr/lib/spark/conf}/.\" \"$conf\" 2>/dev/null",
                        "if [ -n \"${FLOCI_SPARK_LOG4J2:-}\" ]; then",
                        "  printf '\\n%s\\n' \"$FLOCI_SPARK_LOG4J2\" >> \"$conf/log4j2.properties\"",
                        "fi",
                        "export SPARK_CONF_DIR=\"$conf\"",
                        "if [ -n \"${FLOCI_HADOOP_SITE:-}\" ]; then"))
                && entrypoint.get(2).endsWith("\nfi\nexec \"$@\"")
                && entrypoint.subList(3, 5).equals(List.of("floci", "/usr/lib/spark/bin/spark-submit"))));
        ArgumentMatcher<List<String>> theStepsApplication =
                arguments -> arguments.getLast().equals("s3://bucket/jobs/report.py");
        verify(spec).withCmd(argThat(theStepsApplication));
    }

    @Test
    void aStepsSparkConfigurationIsACopyOfTheFolderASparkEnvExportNamesElseTheImages() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        String copy = "\ncp -rL \"${SPARK_CONF_DIR:-/usr/lib/spark/conf}/.\" \"$conf\" 2>/dev/null\n";

        runner(builder).run(clusterJob(null, Map.of(), Map.of("rootLogger.level", "warn")), progress);
        runner(builder).run(clusterJob(List.of("spark-submit", "s3://bucket/jobs/report.py"), Map.of(), Map.of()),
                progress);

        // A Spark step's wrapper, then a command's, links followed as a folder a bootstrap action wrote may hold them.
        verify(spec, times(2)).withEntrypoint(argThat(entrypoint -> entrypoint.size() > 2
                && entrypoint.get(2).contains(copy)));
    }

    @Test
    void aCommandsSparkGetsTheJobsLog4j2LinesToo() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);

        runner(builder).run(clusterJob(List.of("spark-submit", "s3://bucket/jobs/report.py"), Map.of(),
                Map.of("rootLogger.level", "error")), progress);

        verify(spec).withEnv("FLOCI_SPARK_LOG4J2", "rootLogger.level = error");
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "  printf '\\n%s\\n' \"$FLOCI_SPARK_LOG4J2\" >> \"$conf/log4j2.properties\"\n")
                && entrypoint.get(2).contains(
                        "printf '\\n%s\\n' \"$FLOCI_SPARK_DEFAULTS\" >> \"$conf/spark-defaults.conf\"")
                && entrypoint.get(2).endsWith("exec \"$@\"")));
    }

    @Test
    void anEmrServerlessRunRunsSparkSubmitAsItIs() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);

        runner(builder).run(job(null, List.of("s3://bucket/jobs/report.py"), Map.of(), Duration.ZERO), progress);

        verify(spec).withEntrypoint(List.of("/usr/lib/spark/bin/spark-submit"));
        verify(spec, never()).withEnv(eq("FLOCI_SPARK_LOG4J2"), anyString());
        verify(spec, never()).withEnv(eq("FLOCI_HADOOP_SITE"), anyString());
        verify(spec, never()).withEnv(eq("FLOCI_MAPRED_SITE"), anyString());
    }

    @Test
    void aCustomJarOrSparkStepsMapReduceRunsLocallyOnTheClustersHadoopConfiguration() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        SparkJobRunner.Job job = hadoopJob(null, Map.of("mapreduce.job.reduces", "1"), Map.of(), Map.of());

        runner(builder).run(job, progress);

        // A MapReduce job its main class starts reads mapred-site.xml from HADOOP_CONF_DIR, which spark-submit puts
        // on its class path: the image's names YARN, which the job would wait for without end.
        verify(spec).withEnv("FLOCI_HADOOP_SITE", EmrServerlessSparkRunner.hadoopSite(job, ENDPOINT));
        verify(spec).withEnv("FLOCI_MAPRED_SITE", EmrServerlessSparkRunner.mapredSite(job, List.of()));
        verify(spec, never()).withEnv(eq("FLOCI_SPARK_LOG4J2"), anyString());
        verify(spec, never()).withEnv(eq("FLOCI_HADOOP_LOG4J"), anyString());
        verify(spec, never()).withEnv(eq("FLOCI_HADOOP_ENV"), anyString());
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.size() == 5
                && entrypoint.subList(0, 2).equals(List.of("/bin/bash", "-c"))
                && entrypoint.get(2).contains("\n  site mapred-site.xml \"$FLOCI_MAPRED_SITE\"\n")
                && entrypoint.get(2).contains(
                        "\n  export HADOOP_CONF_DIR=\"$hadoop_conf\" PATH=\"/tmp/floci-bin:$PATH\"\n")
                && entrypoint.get(2).endsWith("\nfi\nexec \"$@\"")
                && entrypoint.subList(3, 5).equals(List.of("floci", "/usr/lib/spark/bin/spark-submit"))));
        ArgumentMatcher<List<String>> theStepsJar =
                arguments -> arguments.getLast().equals("s3://bucket/jobs/report.jar");
        verify(spec).withCmd(argThat(theStepsJar));
    }

    @Test
    void theClustersMapredSiteComesBeforeFlocisLocalModeAndStagingFolderEscapedForXml() {
        Map<String, String> mapredSite = new LinkedHashMap<>();
        mapredSite.put("mapreduce.framework.name", "yarn");
        mapredSite.put("mapreduce.map.sort.spill.percent", "0.90");
        mapredSite.put("floci.note", "a<b & \"c\"");

        // A later property wins over an earlier one in the same file, so a job never waits for YARN.
        assertEquals(String.join("\n",
                "<property><name>mapreduce.framework.name</name><value>yarn</value></property>",
                "<property><name>mapreduce.map.sort.spill.percent</name><value>0.90</value></property>",
                "<property><name>floci.note</name><value>a&lt;b &amp; &quot;c&quot;</value></property>",
                "<property><name>mapreduce.framework.name</name><value>local</value></property>"
                        + "<property><name>mapreduce.jobtracker.staging.root.dir</name>"
                        + "<value>/tmp/hadoop/mapred/staging</value></property>"),
                EmrServerlessSparkRunner.mapredSite(hadoopJob(null, mapredSite, Map.of(), Map.of()), List.of()));
    }

    @Test
    void aStepsHadoopCommandGetsTheClustersHadoopLog4jLinesAfterItsOwn() throws IOException {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        Map<String, String> hadoopLog4j = new LinkedHashMap<>();
        hadoopLog4j.put("log4j.logger.org.apache.hadoop", "WARN");
        hadoopLog4j.put("log4j.appender.console.layout.ConversionPattern", "  %p %m%n");
        SparkJobRunner.Job job = hadoopJob(List.of("hadoop", "fs", "-ls", "/"), Map.of(), hadoopLog4j, Map.of());

        runner(builder).run(job, progress);

        verify(spec).withEnv("FLOCI_HADOOP_LOG4J", EmrServerlessSparkRunner.hadoopLog4jFile(job));
        // After the console logger an empty log4j.properties gets, each on a line of its own.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(String.join("\n",
                "  fi",
                "  if [ -n \"${FLOCI_HADOOP_LOG4J:-}\" ]; then",
                "    printf '\\n%s\\n' \"$FLOCI_HADOOP_LOG4J\" >> \"$hadoop_conf/log4j.properties\"",
                "  fi\n"))));
        // Log4j 1 loads log4j.properties with Properties.load, a later key winning.
        Properties loaded = new Properties();
        loaded.load(new StringReader(EmrServerlessSparkRunner.hadoopLog4jFile(job)));
        assertEquals(2, loaded.size());
        assertEquals("WARN", loaded.getProperty("log4j.logger.org.apache.hadoop"));
        assertEquals("  %p %m%n", loaded.getProperty("log4j.appender.console.layout.ConversionPattern"));
    }

    @Test
    void aStepsHadoopCommandGetsTheClustersHadoopEnvExportsAsGiven(@TempDir Path folder) throws Exception {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        Map<String, String> hadoopEnvironment = new LinkedHashMap<>();
        hadoopEnvironment.put("HADOOP_USER_CLASSPATH_FIRST", "true");
        hadoopEnvironment.put("HADOOP_CLASSPATH", "/path/to/my.jar");
        hadoopEnvironment.put("HADOOP_OPTS", "-Dnote='it''s' $HOME `id`");
        hadoopEnvironment.put("NOT-A-NAME", "left out");
        hadoopEnvironment.put("X=1; touch " + folder.resolve("ran") + "; Y", "left out");
        SparkJobRunner.Job job = hadoopJob(List.of("hadoop", "fs", "-ls", "/"), Map.of(), Map.of(),
                hadoopEnvironment);

        runner(builder).run(job, progress);

        verify(spec).withEnv("FLOCI_HADOOP_ENV", EmrServerlessSparkRunner.hadoopEnvFile(job));
        ArgumentCaptor<List<String>> entrypoint = ArgumentCaptor.captor();
        verify(spec).withEntrypoint(entrypoint.capture());
        String script = entrypoint.getValue().get(2);
        String start = "  if [ -n \"${FLOCI_HADOOP_ENV:-}\" ]; then\n";
        String lines = script.substring(script.indexOf(start), script.indexOf("\n  fi\n", script.indexOf(start)) + 6);
        // The wrapper's lines on a hadoop-env.sh that adds to HADOOP_CLASSPATH, as the image's does, then the file
        // sourced as the hadoop command sources it: the exports come first, so the image's line keeps them, each
        // value as given, with no expansion, and a name a shell cannot export runs nothing.
        Files.writeString(folder.resolve("hadoop-env.sh"),
                "export HADOOP_CLASSPATH=\"$HADOOP_CLASSPATH:/usr/lib/hadoop-lzo/lib/*\"\n");
        Path printed = folder.resolve("printed");
        List<String> command = List.of("bash", "-c", "hadoop_conf=" + folder + "\n" + lines
                + ". \"$hadoop_conf/hadoop-env.sh\"\n"
                + "printf '%s\\n' \"$HADOOP_USER_CLASSPATH_FIRST\" \"$HADOOP_CLASSPATH\" \"$HADOOP_OPTS\" > "
                + printed);
        ProcessBuilder process = new ProcessBuilder(command).directory(folder.toFile());
        process.environment().put("FLOCI_HADOOP_ENV", EmrServerlessSparkRunner.hadoopEnvFile(job));
        BootstrapProcess.Result ended = BootstrapProcess.run(process);
        assertEquals(0, ended.exit(), ended.output());
        assertEquals(List.of("true", "/path/to/my.jar:/usr/lib/hadoop-lzo/lib/*", "-Dnote='it''s' $HOME `id`"),
                Files.readAllLines(printed));
        assertFalse(Files.exists(folder.resolve("ran")));
    }

    @Test
    void anEmr6HiveImageKeepsOneSdkWithCloudWatchsModulesAndWhatIsNotTheSdk(@TempDir Path folder) throws Exception {
        List<String> kept = List.of("auth-2.19.0.jar", "cloudwatch-2.20.160.jar",
                "cloudwatch-metric-publisher-2.20.160.jar", "guava-31.1-jre.jar", "hive-exec-3.1.3-amzn-8.jar",
                "my-library-2.20.160.jar");
        List<String> jars = new ArrayList<>(kept);
        for (String module : List.of("annotations", "apache-client", "auth", "aws-core", "aws-json-protocol",
                "aws-query-protocol", "endpoints-spi", "http-client-spi", "json-utils", "metrics-spi",
                "netty-nio-client", "profiles", "protocol-core", "regions", "sdk-core", "third-party-jackson-core",
                "utils")) {
            jars.add(module + "-2.20.160.jar");
        }

        assertEquals(kept, hiveLibAfterOneSdk(folder, true, jars.toArray(String[]::new)));
    }

    @Test
    void anImageWithoutStockSdkModulesKeepsEveryJar(@TempDir Path folder) throws Exception {
        List<String> jars = List.of("commons-math-2.2.jar", "guava-31.1-jre.jar", "hive-exec-3.1.3-amzn-14.jar");

        assertEquals(jars, hiveLibAfterOneSdk(folder, true, jars.toArray(String[]::new)));
    }

    @Test
    void anImageWithoutTezsPluginKeepsItsOnlySdk(@TempDir Path folder) throws Exception {
        List<String> jars = List.of("aws-core-2.20.160.jar", "sdk-core-2.20.160.jar");

        assertEquals(jars, hiveLibAfterOneSdk(folder, false, jars.toArray(String[]::new)));
    }

    /** What is left of a Hive lib folder holding {@code jars} once the shell has run, by name. */
    private static List<String> hiveLibAfterOneSdk(Path folder, boolean plugin, String... jars) throws Exception {
        Path lib = Files.createDirectories(folder.resolve("hive/lib"));
        Path tez = Files.createDirectories(folder.resolve("tez"));
        for (String jar : jars) {
            Files.createFile(lib.resolve(jar));
        }
        if (plugin) {
            Files.createFile(tez.resolve("tez-emr-serverless.jar"));
        }
        String script = EmrServerlessSparkRunner.HIVE_ONE_SDK.replace("/usr/lib/hive/lib", lib.toString())
                .replace("/usr/lib/tez", tez.toString());

        BootstrapProcess.run(new ProcessBuilder("bash", "-c", script).directory(folder.toFile()));

        try (Stream<Path> left = Files.list(lib)) {
            return left.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void log4j2LinesReachLog4jAsTheClusterGaveThem() throws IOException {
        Map<String, String> sparkLog4j2 = new LinkedHashMap<>();
        sparkLog4j2.put("appender.file.fileName", "C:\\logs\\spark.log");
        sparkLog4j2.put("odd key=with:separators", "#not a comment\nnor a second line");
        sparkLog4j2.put("appender.console.layout.pattern", "  %m%n");

        // Log4j's PropertiesConfigurationFactory loads log4j2.properties with Properties.load.
        Properties loaded = new Properties();
        loaded.load(new StringReader(
                EmrServerlessSparkRunner.sparkLog4j2File(clusterJob(null, Map.of(), sparkLog4j2))));

        assertEquals("C:\\logs\\spark.log", loaded.getProperty("appender.file.fileName"));
        assertEquals("#not a comment\nnor a second line", loaded.getProperty("odd key=with:separators"));
        // Properties.load skips the spaces after the separator, so a value's own leading ones are escaped.
        assertEquals("  %m%n", loaded.getProperty("appender.console.layout.pattern"));
    }

    @Test
    void aCommandsOwnSparkSubmitRunsLocallyOnFlocisS3WithTheJobsSparkDefaults() {
        assertEquals(String.join("\n",
                "spark.master local[*]",
                "spark.hadoop.fs.s3a.endpoint http://floci:4566",
                "spark.hadoop.fs.s3a.endpoint.region eu-west-1",
                "spark.hadoop.fs.s3a.path.style.access true",
                "spark.hadoop.fs.s3a.committer.magic.track.commits.in.memory.enabled true",
                "spark.hadoop.fs.s3.impl org.apache.hadoop.fs.s3a.S3AFileSystem",
                "spark.hadoop.fs.AbstractFileSystem.s3.impl org.apache.hadoop.fs.s3a.S3A",
                "spark.hadoop.aws.glue.endpoint http://floci:4566",
                "spark.hadoop.hive.metastore.glue.catalogid 123456789012",
                "spark.hadoop.aws.region eu-west-1",
                "spark.sql.shuffle.partitions 8"),
                EmrServerlessSparkRunner.sparkDefaultsFile(
                        commandJob(List.of("bash", "-c", "spark-submit s3://bucket/jobs/app.py"),
                                Map.of("spark.sql.shuffle.partitions", "8")), ENDPOINT));
    }

    @Test
    void aCommandsSparkDefaultsReachSparkAsTheJobGaveThem() throws IOException {
        Map<String, String> sparkDefaults = new LinkedHashMap<>();
        sparkDefaults.put("spark.driver.extraJavaOptions", "-Dpattern=\\d+ -Dpath=C:\\tmp");
        sparkDefaults.put("spark.odd key=with:separators", "#not a comment\nnor a second line\t!");
        sparkDefaults.put("!spark.leading", "kept");

        // Spark's Utils.getPropertiesFromFile loads spark-defaults.conf with Properties.load.
        Properties loaded = new Properties();
        loaded.load(new StringReader(EmrServerlessSparkRunner.sparkDefaultsFile(
                commandJob(List.of("bash", "-c", "spark-submit s3://bucket/jobs/app.py"), sparkDefaults), ENDPOINT)));

        assertEquals("local[*]", loaded.getProperty("spark.master"));
        assertEquals("-Dpattern=\\d+ -Dpath=C:\\tmp", loaded.getProperty("spark.driver.extraJavaOptions"));
        assertEquals("#not a comment\nnor a second line\t!", loaded.getProperty("spark.odd key=with:separators"));
        assertEquals("kept", loaded.getProperty("!spark.leading"));
        assertEquals(ENDPOINT, loaded.getProperty("spark.hadoop.fs.s3a.endpoint"));
    }

    @Test
    void aTemplateThatNamesNoImageFailsTheRunWithItsReason() {
        EmrServerlessSparkRunner runner = runner();

        SparkJobRunner.Outcome outcome = runner.run(
                job("registry.internal/emr-serverless/spark/%d:latest", List.of("s3://bucket/jobs/app.jar"), Map.of(),
                        Duration.ZERO), progress);

        assertNull(outcome.exitCode());
        assertNotNull(outcome.failure());
        verify(lifecycle, never()).create(any());
    }

    @Test
    void aCancelEndsTheRunAndRemovesItsDriverEvenWhenDockerWouldRefuseAStop() {
        EmrServerlessSparkRunner runner = runner();
        SparkJobRunner.Job job = job(Duration.ofMillis(50));
        when(docker.stopContainerCmd("driver").withTimeout(0).exec())
                .thenThrow(new InternalServerErrorException("daemon busy"));
        // The driver never exits by itself, as a STREAMING run's; the cancel comes from another task,
        // whose exceptions go nowhere, while the driver is being watched.
        AtomicBoolean cancelled = new AtomicBoolean();
        when(docker.inspectContainerCmd("driver").exec().getState().getRunning()).thenAnswer(look -> {
            if (cancelled.compareAndSet(false, true)) {
                try {
                    runner.stop(job.runId());
                } catch (RuntimeException ignored) {
                    // Dropped, as the executor drops what its task throws; the assertions below catch a lost cancel.
                }
            }
            return true;
        });

        SparkJobRunner.Outcome outcome = runner.run(job, progress);

        assertTrue(outcome.stopped());
        verify(progress, never()).timedOut();
        verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void aResetWhileTheImageIsPulledStopsTheRunOnceItsContainerIsUp() {
        EmrServerlessSparkRunner runner = runner();
        when(lifecycle.create(any())).thenAnswer(pulling -> {
            runner.stopManagedContainers();
            return "driver";
        });

        SparkJobRunner.Outcome outcome = runner.run(job(Duration.ZERO), progress);

        assertTrue(outcome.stopped());
        verify(progress, never()).started();
        verify(lifecycle, never()).startCreated(anyString(), any());
        verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void aRunCancelledWhileItsImageIsPulledNeverStartsItsDriver() {
        EmrServerlessSparkRunner runner = runner();
        // Not cancelled when the run begins, cancelled by the time its container is up.
        when(progress.cancelled()).thenReturn(false, true);

        SparkJobRunner.Outcome outcome = runner.run(job(Duration.ZERO), progress);

        assertTrue(outcome.stopped());
        verify(progress, never()).started();
        verify(lifecycle, never()).startCreated(anyString(), any());
        verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void aRunStoppedWhileItsContainerIsCreatedNeverStartsItsDriver() {
        EmrServerlessSparkRunner runner = runner();
        SparkJobRunner.Job job = job(Duration.ZERO);
        // The create pulls the image and copies the trust store in, either of which can wait on Docker, and a
        // stop only marks the run.
        when(lifecycle.create(any())).thenAnswer(creating -> {
            runner.stop(job.runId());
            return "driver";
        });

        SparkJobRunner.Outcome outcome = runner.run(job, progress);

        assertTrue(outcome.stopped());
        verify(progress, never()).started();
        verify(lifecycle, never()).startCreated(anyString(), any());
        verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void aRunningDriverWhoseRunIsGoneIsStoppedAtTheNextLook() {
        EmrServerlessSparkRunner runner = runner();
        // A reset removes the run while its driver runs: not cancelled before the driver starts, nor at the
        // first look at it, then gone. Left to itself, the driver would run on for four more looks, then exit.
        when(progress.cancelled()).thenReturn(false, false, false, true);
        when(docker.inspectContainerCmd("driver").exec().getState().getRunning())
                .thenReturn(true, true, true, true, true, false);

        SparkJobRunner.Outcome outcome = runner.run(job(Duration.ZERO), progress);

        assertTrue(outcome.stopped());
        verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void aStopAfterTheRunHasEndedIsDropped() {
        EmrServerlessSparkRunner runner = runner();
        SparkJobRunner.Job job = job(Duration.ZERO);
        assertEquals(0, runner.run(job, progress).exitCode());

        runner.stop(job.runId());

        // A stop kept past its run would halt the next run of this id before it started.
        assertEquals(0, runner.run(job, progress).exitCode());
        verify(docker, never()).stopContainerCmd(anyString());
    }

    @Test
    void aRunPastItsTimeoutIsToldSoBeforeItsDriverIsRemoved() {
        EmrServerlessSparkRunner runner = runner();
        when(docker.inspectContainerCmd("driver").exec().getState().getRunning()).thenReturn(true);

        SparkJobRunner.Outcome outcome = runner.run(job(Duration.ofMillis(5)), progress);

        assertTrue(outcome.timedOut());
        InOrder order = inOrder(progress, lifecycle);
        order.verify(progress).timedOut();
        order.verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void theDriverIsWatchedWithoutHoldingADockerConnection() {
        EmrServerlessSparkRunner runner = runner();

        runner.run(job(Duration.ZERO), progress);

        // A look at the container is one short call; no wait or other stream is held while the driver runs.
        verify(docker, atLeastOnce()).inspectContainerCmd("driver");
        verify(docker, never()).waitContainerCmd(anyString());
    }

    @Test
    void theDriversOutputGoesGzippedToTheRunsLogFolderOnceItEnds() throws IOException {
        EmrServerlessSparkRunner runner = runner();
        driverWrote(new Frame(StreamType.STDERR, bytes("INFO SparkContext: Running Spark version 3.5.2\n")),
                new Frame(StreamType.STDOUT, bytes("Pi is roughly 3.14\n")),
                new Frame(StreamType.STDOUT, bytes("done\n")));

        SparkJobRunner.Outcome outcome = runner.run(job("s3://logs/emr/j-1/steps/s-1/"), progress);

        assertEquals(0, outcome.exitCode());
        assertEquals("Pi is roughly 3.14\ndone\n", written("logs", "emr/j-1/steps/s-1/stdout.gz"));
        assertEquals("INFO SparkContext: Running Spark version 3.5.2\n",
                written("logs", "emr/j-1/steps/s-1/stderr.gz"));
    }

    @Test
    void aLogFolderInAHadoopSchemeIsInTheSameBucket() throws IOException {
        EmrServerlessSparkRunner runner = runner();
        driverWrote(new Frame(StreamType.STDOUT, bytes("done\n")));

        runner.run(job("s3n://logs/emr/j-1/steps/s-1/"), progress);

        assertEquals("done\n", written("logs", "emr/j-1/steps/s-1/stdout.gz"));
    }

    @Test
    void aCancelledRunsDriverIsStoppedBeforeItsLogsAreReadAndRemovedAfterThey() {
        EmrServerlessSparkRunner runner = runner();
        SparkJobRunner.Job job = job("s3://logs/emr/j-1/steps/s-1/");
        driverWrote(new Frame(StreamType.STDOUT, bytes("sleeping\n")));
        when(docker.inspectContainerCmd("driver").exec().getState().getRunning()).thenAnswer(look -> {
            runner.stop(job.runId());
            return true;
        });

        SparkJobRunner.Outcome outcome = runner.run(job, progress);

        // Stopped first, the driver changes nothing while its output is read, and all of that output is there.
        assertTrue(outcome.stopped());
        InOrder order = inOrder(lifecycle, docker, s3);
        order.verify(lifecycle).stop("driver", 0);
        order.verify(docker).logContainerCmd("driver");
        order.verify(s3).putObjectFromStream(eq("logs"), eq("emr/j-1/steps/s-1/stdout.gz"), any(), anyString(),
                anyMap());
        order.verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void anInterruptedLogReadEndsDockersStreamAndWritesNothing() throws IOException {
        EmrServerlessSparkRunner runner = runner();
        Closeable stream = mock(Closeable.class);
        // Floci shuts down while the output is read: Docker has started sending it, and never finishes.
        when(docker.logContainerCmd("driver").withStdOut(true).withStdErr(true).withFollowStream(false)
                .withTimestamps(false).exec(any())).thenAnswer(read -> {
                    ResultCallback.Adapter<Frame> output = read.getArgument(0);
                    output.onStart(stream);
                    output.onNext(new Frame(StreamType.STDOUT, bytes("Pi is ")));
                    Thread.currentThread().interrupt();
                    return output;
                });

        try {
            SparkJobRunner.Outcome outcome = runner.run(job("s3://logs/emr/j-1/steps/s-1/"), progress);

            assertEquals(0, outcome.exitCode());
            verify(stream).close();
            verify(s3, never()).putObjectFromStream(anyString(), anyString(), any(), anyString(), anyMap());
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    void logsThatCannotBeWrittenLeaveTheRunAsItEnded() {
        EmrServerlessSparkRunner runner = runner();
        driverWrote(new Frame(StreamType.STDOUT, bytes("done\n")));
        when(s3.putObjectFromStream(anyString(), anyString(), any(), anyString(), anyMap()))
                .thenThrow(new AwsException("NoSuchBucket", "The specified bucket does not exist", 404));

        SparkJobRunner.Outcome outcome = runner.run(job("s3://missing/emr/j-1/steps/s-1/"), progress);

        assertEquals(0, outcome.exitCode());
        verify(lifecycle).stopAndRemove("driver", null, 0);
    }

    @Test
    void aDriverFlocisTeardownTookWritesNoLogs() {
        EmrServerlessSparkRunner runner = runner();
        when(docker.inspectContainerCmd("driver").exec().getState().getRunning()).thenAnswer(look -> {
            runner.stopManagedContainers();
            return true;
        });

        SparkJobRunner.Outcome outcome = runner.run(job("s3://logs/emr/j-1/steps/s-1/"), progress);

        assertTrue(outcome.stopped());
        verify(docker, never()).logContainerCmd(anyString());
        verify(s3, never()).putObjectFromStream(anyString(), anyString(), any(), anyString(), anyMap());
    }

    @Test
    void theDriversOutputGoesToTheJobsCloudWatchLogStreamsWhileItRuns() {
        EmrServerlessSparkRunner runner = runner();
        String driver = "/applications/00f0000000000000/jobs/00f0000000000001/SPARK_DRIVER/";

        runner.run(job(new SparkJobRunner.CloudWatchLogs("/aws/emr-serverless", driver + "stdout", driver + "stderr")),
                progress);

        verify(logStreamer).attachByStreamForAccount("123456789012", "driver", "/aws/emr-serverless",
                driver + "stdout", driver + "stderr", "eu-west-1", "emr-serverless:00f0000000000001");
        verify(logStreamer, never()).attachConsoleOnly(anyString(), anyString());
    }

    @Test
    void withoutCloudWatchLogStreamsTheDriversOutputGoesOnlyToFlocisLog() {
        runner().run(job(Duration.ZERO), progress);

        verify(logStreamer).attachConsoleOnly("driver", "emr-serverless:00f0000000000001");
        verify(logStreamer, never()).attachByStreamForAccount(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRunWithoutALogFolderReadsNoOutput() {
        runner().run(job(Duration.ZERO), progress);

        verify(docker, never()).logContainerCmd(anyString());
        verify(lifecycle, never()).stop(anyString(), anyInt());
    }

    @Test
    void aLogFolderFollowsTheLogUriAfterOneSlash() {
        assertEquals("s3://logs/emr/j-1/steps/s-1/",
                EmrServerlessSparkRunner.logFolder("s3://logs/emr/", "j-1/steps/s-1/"));
        assertEquals("s3://logs/emr/j-1/steps/s-1/",
                EmrServerlessSparkRunner.logFolder("s3://logs/emr", "j-1/steps/s-1/"));
        assertEquals("s3://logs/j-1/steps/s-1/", EmrServerlessSparkRunner.logFolder("s3://logs//", "j-1/steps/s-1/"));
        assertNull(EmrServerlessSparkRunner.logFolder(null, "j-1/steps/s-1/"));
        assertNull(EmrServerlessSparkRunner.logFolder(" ", "j-1/steps/s-1/"));
    }

    @Test
    void driversAnEarlierProcessOfThisDeploymentLeftAreRemoved() {
        EmrServerlessSparkRunner runner = runner();
        when(config.port()).thenReturn(4566);
        List<Container> drivers = List.of(driver("left-behind", "4566"), driver("another-deployments", "4567"));
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec()).thenReturn(drivers);

        runner.removeLeftoverDrivers("emr-serverless");

        verify(lifecycle).removeIfExists("left-behind");
        verify(lifecycle, never()).removeIfExists("another-deployments");
    }

    @Test
    void aNodeOfALiveClusterIsNoDriverTheSweepRemoves() {
        EmrServerlessSparkRunner runner = runner();
        when(config.port()).thenReturn(4566);
        Container node = mock(Container.class);
        when(node.getId()).thenReturn("node");
        when(node.getLabels()).thenReturn(Map.of(ContainerStorageHelper.SERVICE_LABEL, "emr",
                ContainerStorageHelper.OWNER_LABEL, "4566", ContainerStorageHelper.COMPONENT_LABEL, "emr-node"));
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec()).thenReturn(List.of(node));

        runner.removeLeftoverDrivers("emr");

        verify(lifecycle, never()).removeIfExists("node");
    }

    @Test
    void theHdfsOfALiveClusterIsNoDriverTheSweepRemoves() {
        EmrServerlessSparkRunner runner = runner();
        when(config.port()).thenReturn(4566);
        Container hdfs = mock(Container.class);
        when(hdfs.getId()).thenReturn("hdfs");
        when(hdfs.getLabels()).thenReturn(Map.of(ContainerStorageHelper.SERVICE_LABEL, "emr",
                ContainerStorageHelper.OWNER_LABEL, "4566", ContainerStorageHelper.COMPONENT_LABEL, "emr-hdfs"));
        Container driver = mock(Container.class);
        when(driver.getId()).thenReturn("left-behind");
        when(driver.getLabels()).thenReturn(Map.of(ContainerStorageHelper.SERVICE_LABEL, "emr",
                ContainerStorageHelper.OWNER_LABEL, "4566"));
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec())
                .thenReturn(List.of(hdfs, driver));

        runner.removeLeftoverDrivers("emr");

        verify(lifecycle).removeIfExists("left-behind");
        verify(lifecycle, never()).removeIfExists("hdfs");
    }

    // A runner whose driver container, "driver", starts at once and exits with code 0 unless a test says otherwise.
    private EmrServerlessSparkRunner runner() {
        return runner(mock(ContainerBuilder.class, RETURNS_DEEP_STUBS));
    }

    private EmrServerlessSparkRunner runner(ContainerBuilder builder) {
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(lifecycle.create(any())).thenReturn("driver");
        when(docker.inspectContainerCmd("driver").exec().getState().getRunning()).thenReturn(false);
        when(docker.inspectContainerCmd("driver").exec().getState().getExitCodeLong()).thenReturn(0L);
        when(awsEnv.flociEndpoint()).thenReturn(ENDPOINT);
        // An image pulled only when missing is there, by its own name.
        when(lifecycle.resolveImageForLaunch(anyString(), eq(ImagePullBehavior.PREFER_CACHED), isNull()))
                .thenAnswer(pull -> new LaunchImage(pull.getArgument(0), null));
        return new EmrServerlessSparkRunner(builder, lifecycle, logStreamer, awsEnv,
                mock(LambdaExecutionRoleCredentials.class), s3, ecr, config, new MutableClock(), Duration.ZERO);
    }

    // What the driver wrote, as Docker gives it once asked for an ended container's output.
    private void driverWrote(Frame... frames) {
        when(docker.logContainerCmd("driver").withStdOut(true).withStdErr(true).withFollowStream(false)
                .withTimestamps(false).exec(any())).thenAnswer(read -> {
                    ResultCallback.Adapter<Frame> output = read.getArgument(0);
                    for (Frame frame : frames) {
                        output.onNext(frame);
                    }
                    output.onComplete();
                    return output;
                });
    }

    // The object the runner put at key, gunzipped.
    private String written(String bucket, String key) throws IOException {
        ArgumentCaptor<InputStream> data = ArgumentCaptor.forClass(InputStream.class);
        verify(s3).putObjectFromStream(eq(bucket), eq(key), data.capture(), eq("application/x-gzip"), eq(Map.of()));
        try (GZIPInputStream gunzip = new GZIPInputStream(data.getValue())) {
            return new String(gunzip.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // What each tar copied into the container holds, by entry, read as the daemon reads it.
    private static Map<String, String> copiedIn(CopyArchiveToContainerCmd copy) {
        Map<String, String> copied = new LinkedHashMap<>();
        when(copy.withTarInputStream(any())).thenAnswer(streaming -> {
            InputStream stream = streaming.getArgument(0);
            TarArchiveInputStream tar = new TarArchiveInputStream(stream);
            for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                copied.put(entry.getName(), new String(tar.readAllBytes(), StandardCharsets.UTF_8));
            }
            // Read to its end, as the daemon does, so the writer finishes; the copy closes the stream.
            stream.transferTo(OutputStream.nullOutputStream());
            return copy;
        });
        return copied;
    }

    // An object in Floci's S3, which each read opens afresh.
    private void inS3(String bucket, String key, String content) {
        S3Object object = new S3Object(bucket, key, bytes(content), "application/octet-stream");
        when(s3.openObject(bucket, key, null))
                .thenAnswer(read -> new S3Service.ObjectRead(object, new ByteArrayInputStream(object.getData())));
    }

    // An archive at file, a path on this host, to be unpacked into directory.
    private static EmrServerlessSparkRunner.Archive archive(Path file, String directory) {
        return new EmrServerlessSparkRunner.Archive(null, null, file.toString(), directory);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Container driver(String id, String owner) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getLabels()).thenReturn(Map.of(ContainerStorageHelper.SERVICE_LABEL, "emr-serverless",
                ContainerStorageHelper.OWNER_LABEL, owner));
        return container;
    }

    private static SparkJobRunner.Job job(Duration timeout) {
        return job(List.of("s3://bucket/jobs/app.jar"), Map.of(), timeout);
    }

    private static SparkJobRunner.Job job(String logFolder) {
        return job(null, List.of("s3://bucket/jobs/app.jar"), Map.of(), Duration.ZERO, logFolder, null);
    }

    private static SparkJobRunner.Job job(SparkJobRunner.CloudWatchLogs cloudWatchLogs) {
        return job(null, List.of("s3://bucket/jobs/app.jar"), Map.of(), Duration.ZERO, null, cloudWatchLogs);
    }

    private static SparkJobRunner.Job job(List<String> application, Map<String, String> sparkDefaults) {
        return job(application, sparkDefaults, Duration.ZERO);
    }

    private static SparkJobRunner.Job job(List<String> application, Map<String, String> sparkDefaults,
                                          Duration timeout) {
        return job(null, application, sparkDefaults, timeout, null, null);
    }

    private static SparkJobRunner.Job job(String imageTemplate, List<String> application,
                                          Map<String, String> sparkDefaults, Duration timeout) {
        return job(imageTemplate, application, sparkDefaults, timeout, null, null);
    }

    private static SparkJobRunner.Job job(String imageTemplate, List<String> application,
                                          Map<String, String> sparkDefaults, Duration timeout, String logFolder,
                                          SparkJobRunner.CloudWatchLogs cloudWatchLogs) {
        return new SparkJobRunner.Job("emr-serverless", "00f0000000000001", "123456789012", "eu-west-1", "emr-7.5.0",
                SPARK, imageTemplate, null, "floci-network", "arn:aws:iam::123456789012:role/job", sparkDefaults,
                application, null, timeout, logFolder, cloudWatchLogs, Map.of(), List.of(), null, Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), null);
    }

    private static final SparkJobRunner.Executors EXECUTORS = new SparkJobRunner.Executors(2, null, 0, 0, 0, null);
    // A 4-core 14G executor's size, whose worker and the driver's hold 4 vCPUs, 16 GB and 20 GB of disk.
    private static final SparkExecutors.Size FOUR_CORE_EXECUTOR = new SparkExecutors.Size(4, 14336,
            new SparkExecutors.Amount(4, 16, 20), new SparkExecutors.Amount(4, 16, 20));

    // An EMR Serverless Spark run whose executors run in containers of their own.
    private static SparkJobRunner.Job executorsJob(Map<String, String> sparkDefaults) {
        return job(null, List.of("s3://bucket/jobs/app.jar"), sparkDefaults, Duration.ZERO).withExecutors(EXECUTORS);
    }

    private static SparkJobRunner.Job hiveJob(List<String> application) {
        return new SparkJobRunner.Job("emr-serverless", "00f0000000000002", "123456789012", "eu-west-1", "emr-7.5.0",
                HIVE, null, null, "floci-network", "arn:aws:iam::123456789012:role/job", Map.of(), application, null,
                Duration.ZERO, null, null, Map.of(), List.of(), null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                null);
    }

    // An EMR Hive step: hive-script's --args, then application, on cluster j-1's storage.
    private static SparkJobRunner.Job hiveStep(List<String> application) {
        return new SparkJobRunner.Job("emr", "s-1", "123456789012", "eu-west-1", "emr-7.5.0", HIVE, null, null,
                "floci-network", "arn:aws:iam::123456789012:role/emr-ec2", Map.of(), application,
                List.of("hive-script", "--run-hive-script", "--args"), Duration.ZERO, null, null, Map.of(), List.of(),
                new SparkJobRunner.Storage("j-1-home", "j-1-mnt").withHdfs("ip-10-0-0-1.ec2.internal", "172.18.0.5",
                        "j-1-hdfs").withHiveMetastore("thrift://ip-10-0-0-1.ec2.internal:9083"),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null);
    }

    private static SparkJobRunner.Job customImageJob(String customImage) {
        return new SparkJobRunner.Job("emr-serverless", "00f0000000000001", "123456789012", "eu-west-1", "emr-7.5.0",
                SPARK, null, customImage, "floci-network", "arn:aws:iam::123456789012:role/job", Map.of(),
                List.of("s3://bucket/jobs/app.jar"), null, Duration.ZERO, null, null, Map.of(), List.of(), null,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null);
    }

    private static SparkJobRunner.Job archiveJob(List<String> archives) {
        return new SparkJobRunner.Job("emr-serverless", "00f0000000000001", "123456789012", "eu-west-1", "emr-7.5.0",
                SPARK, null, null, "floci-network", "arn:aws:iam::123456789012:role/job", Map.of(),
                List.of("s3://bucket/jobs/app.jar"), null, Duration.ZERO, null, null, Map.of(), archives, null,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null);
    }

    private static SparkJobRunner.Job commandJob(List<String> command, Map<String, String> sparkDefaults) {
        return commandJob(command, sparkDefaults, null);
    }

    private static SparkJobRunner.Job commandJob(List<String> command, Map<String, String> sparkDefaults,
                                                 SparkJobRunner.Storage storage) {
        return new SparkJobRunner.Job("emr", "s-1A2B3C4D5E6F7", "123456789012", "eu-west-1", "emr-7.5.0", SPARK, null,
                null, "floci-network", "arn:aws:iam::123456789012:role/job", sparkDefaults, List.of(), command,
                Duration.ZERO, null, null, Map.of("PYSPARK_PYTHON", "/usr/bin/python3.11"), List.of(),
                storage, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null);
    }

    // A step on a cluster with core-site and spark-log4j2 properties: a command, or a Spark step for a null one.
    private static SparkJobRunner.Job clusterJob(List<String> command, Map<String, String> coreSite,
                                                 Map<String, String> sparkLog4j2) {
        return new SparkJobRunner.Job("emr", "s-1A2B3C4D5E6F7", "123456789012", "eu-west-1", "emr-7.5.0", SPARK, null,
                null, "floci-network", "arn:aws:iam::123456789012:role/job", Map.of(),
                command == null ? List.of("s3://bucket/jobs/report.py") : List.of(), command, Duration.ZERO, null,
                null, Map.of(), List.of(), STORAGE, coreSite, sparkLog4j2, Map.of(), Map.of(), Map.of(), null);
    }

    // A step on a cluster with mapred-site, hadoop-log4j and hadoop-env: a command, or a custom JAR for a null one.
    private static SparkJobRunner.Job hadoopJob(List<String> command, Map<String, String> mapredSite,
                                                Map<String, String> hadoopLog4j,
                                                Map<String, String> hadoopEnvironment) {
        return new SparkJobRunner.Job("emr", "s-1A2B3C4D5E6F7", "123456789012", "eu-west-1", "emr-7.5.0", SPARK, null,
                null, "floci-network", "arn:aws:iam::123456789012:role/job", Map.of(),
                command == null ? List.of("s3://bucket/jobs/report.jar") : List.of(), command, Duration.ZERO, null,
                null, Map.of(), List.of(), STORAGE, Map.of(), Map.of(), mapredSite, hadoopLog4j, hadoopEnvironment,
                null);
    }

    // A Spark step on a cluster, which has storage.
    private static SparkJobRunner.Job onRelease(String release, SparkJobRunner.Job job) {
        return new SparkJobRunner.Job(job.service(), job.runId(), job.accountId(), job.region(), release, job.engine(),
                job.imageTemplate(), job.customImage(), job.dockerNetwork(), job.executionRole(), job.sparkDefaults(),
                job.application(), job.command(), job.timeout(), job.logFolder(), job.cloudWatchLogs(),
                job.environment(), job.archives(), job.storage(), job.coreSite(), job.sparkLog4j2(), job.mapredSite(),
                job.hadoopLog4j(), job.hadoopEnvironment(), job.executors());
    }

    private static SparkJobRunner.Job stepJob(Map<String, String> sparkDefaults) {
        return new SparkJobRunner.Job("emr", "s-1A2B3C4D5E6F7", "123456789012", "eu-west-1", "emr-7.5.0", SPARK, null,
                null, "floci-network", "arn:aws:iam::123456789012:role/job", sparkDefaults,
                List.of("s3://bucket/jobs/report.py"), null, Duration.ZERO, null, null, Map.of(), List.of(), STORAGE,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null);
    }
}
