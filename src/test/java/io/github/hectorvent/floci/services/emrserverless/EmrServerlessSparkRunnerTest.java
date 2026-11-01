package io.github.hectorvent.floci.services.emrserverless;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.lambda.launcher.BootstrapProcess;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.testing.MutableClock;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
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
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
    private static final SparkJobRunner.Storage STORAGE = new SparkJobRunner.Storage(
            "floci-aws-emr-j-2AXXXXXXGAPLF-home", "floci-aws-emr-j-2AXXXXXXGAPLF-mnt");

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
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
                "--conf", "spark.hadoop.aws.region=eu-west-1",
                "--conf", "spark.executor.cores=4",
                "--conf", "spark.sql.shuffle.partitions=8",
                "--conf", "spark.executor.cores=2",
                "--py-files", "s3://bucket/jobs/lib.zip",
                "s3://bucket/jobs/report.py", "s3://bucket/input/", "s3://bucket/output/"), arguments);
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
        // An EMR Serverless run has no cluster, nor its storage.
        verify(spec, never()).withNamedVolume(anyString(), anyString());
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
    void aStepMountsItsClustersStorageWhoseHdfsIsWherePathsWithoutASchemeLead() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.newContainer(anyString())).thenReturn(spec);
        SparkJobRunner.Job job = stepJob(Map.of("spark.hadoop.fs.defaultFS", "file:///"));

        runner(builder).run(job, progress);
        List<String> arguments = EmrServerlessSparkRunner.sparkSubmitArguments(job, ENDPOINT);

        verify(spec).withNamedVolume(STORAGE.home(), "/home/hadoop");
        verify(spec).withNamedVolume(STORAGE.mnt(), "/mnt");
        // After Floci's S3 settings, before the cluster's spark-defaults, which win.
        assertEquals("spark.hadoop.aws.region=eu-west-1", arguments.get(17));
        assertEquals(List.of(
                "--conf", "spark.hadoop.fs.defaultFS=hdfs:///",
                "--conf", "spark.hadoop.fs.hdfs.impl=org.apache.hadoop.fs.viewfs.ViewFileSystemOverloadScheme",
                "--conf", "spark.hadoop.fs.viewfs.mounttable.default.linkFallback=file:///mnt/hdfs",
                "--conf", "spark.sql.streaming.checkpointFileManagerClass="
                        + "org.apache.spark.sql.execution.streaming.FileSystemBasedCheckpointFileManager",
                "--conf", "spark.hadoop.fs.defaultFS=file:///",
                "s3://bucket/jobs/report.py"), arguments.subList(18, arguments.size()));
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
        verify(spec).withEnv("FLOCI_HADOOP_SITE", String.join("\n",
                "<property><name>fs.defaultFS</name><value>hdfs:///</value></property>",
                "<property><name>fs.hdfs.impl</name><value>org.apache.hadoop.fs.viewfs.ViewFileSystemOverloadScheme"
                        + "</value></property>",
                "<property><name>fs.viewfs.mounttable.default.linkFallback</name><value>file:///mnt/hdfs</value>"
                        + "</property>"));
        verify(spec).withEnv(eq("FLOCI_SPARK_DEFAULTS"), argThat(file -> file.contains(
                "\nspark.hadoop.fs.defaultFS hdfs:///\n")));
        // A copy of the Hadoop configuration a spark-env export of HADOOP_CONF_DIR names, else of the image's, links
        // followed, its core-site.xml taking in the original's whole, whatever its XML, then the cluster's HDFS.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "source_conf=\"${HADOOP_CONF_DIR:-/etc/hadoop/conf}\"\n")
                && entrypoint.get(2).contains("cp -rL \"$source_conf/.\" \"$hadoop_conf\"")
                && entrypoint.get(2).contains(
                        "include='<xi:include href=\"/tmp/floci-hadoop-conf/floci-original-core-site.xml\"/>'")
                && entrypoint.get(2).contains("'<configuration xmlns:xi=\"http://www.w3.org/2001/XInclude\">'"
                        + " \"$include\" \"$FLOCI_HADOOP_SITE\" '</configuration>' > \"$hadoop_conf/core-site.xml\"")
                && entrypoint.get(2).contains(
                        "export HADOOP_CONF_DIR=\"$hadoop_conf\" PATH=\"/tmp/floci-bin:$PATH\"")));
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

    // A runner whose driver container, "driver", starts at once and exits with code 0 unless a test says otherwise.
    private EmrServerlessSparkRunner runner() {
        return runner(mock(ContainerBuilder.class, RETURNS_DEEP_STUBS));
    }

    private EmrServerlessSparkRunner runner(ContainerBuilder builder) {
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(lifecycle.create(any())).thenReturn("driver");
        when(docker.inspectContainerCmd("driver").exec().getState().getRunning()).thenReturn(false);
        when(docker.inspectContainerCmd("driver").exec().getState().getExitCodeLong()).thenReturn(0L);
        return new EmrServerlessSparkRunner(builder, lifecycle, logStreamer,
                mock(LaunchedContainerAwsEnv.class), mock(LambdaExecutionRoleCredentials.class), s3, ecr, config,
                new MutableClock(), Duration.ZERO);
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
                imageTemplate, null, "floci-network", "arn:aws:iam::123456789012:role/job", sparkDefaults,
                application, null, timeout, logFolder, cloudWatchLogs, Map.of(), List.of(), null);
    }

    private static SparkJobRunner.Job customImageJob(String customImage) {
        return new SparkJobRunner.Job("emr-serverless", "00f0000000000001", "123456789012", "eu-west-1", "emr-7.5.0",
                null, customImage, "floci-network", "arn:aws:iam::123456789012:role/job", Map.of(),
                List.of("s3://bucket/jobs/app.jar"), null, Duration.ZERO, null, null, Map.of(), List.of(), null);
    }

    private static SparkJobRunner.Job archiveJob(List<String> archives) {
        return new SparkJobRunner.Job("emr-serverless", "00f0000000000001", "123456789012", "eu-west-1", "emr-7.5.0",
                null, null, "floci-network", "arn:aws:iam::123456789012:role/job", Map.of(),
                List.of("s3://bucket/jobs/app.jar"), null, Duration.ZERO, null, null, Map.of(), archives, null);
    }

    private static SparkJobRunner.Job commandJob(List<String> command, Map<String, String> sparkDefaults) {
        return commandJob(command, sparkDefaults, null);
    }

    private static SparkJobRunner.Job commandJob(List<String> command, Map<String, String> sparkDefaults,
                                                 SparkJobRunner.Storage storage) {
        return new SparkJobRunner.Job("emr", "s-1A2B3C4D5E6F7", "123456789012", "eu-west-1", "emr-7.5.0", null,
                null, "floci-network", "arn:aws:iam::123456789012:role/job", sparkDefaults, List.of(), command,
                Duration.ZERO, null, null, Map.of("PYSPARK_PYTHON", "/usr/bin/python3.11"), List.of(),
                storage);
    }

    // A Spark step on a cluster, which has storage.
    private static SparkJobRunner.Job stepJob(Map<String, String> sparkDefaults) {
        return new SparkJobRunner.Job("emr", "s-1A2B3C4D5E6F7", "123456789012", "eu-west-1", "emr-7.5.0", null,
                null, "floci-network", "arn:aws:iam::123456789012:role/job", sparkDefaults,
                List.of("s3://bucket/jobs/report.py"), null, Duration.ZERO, null, null, Map.of(), List.of(), STORAGE);
    }
}
