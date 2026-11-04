package io.github.hectorvent.floci.services.emrserverless;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.S3Uri;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLaunches;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Runs a Spark application's {@code spark-submit} in a container of an EMR Serverless image, for an EMR
 * Serverless job run or an EMR step. The image's own entrypoint hands over to a script EMR Serverless
 * supplies at run time, which the public image lacks, so the runner starts {@code spark-submit} itself,
 * in local mode: the container holds the driver and its executors. The container reaches Floci as the
 * other containers Floci launches do, with the run's execution role as its credentials, and S3A, which
 * takes {@code s3://} on every release, is pointed at Floci's S3. With TLS on, every JVM in the container
 * trusts Floci's CA, through the Java trust store copied in before it starts, and the run's archives in S3
 * are unpacked into its working directory before the driver starts. The driver's output goes to
 * Floci's log and, while it runs, to the run's CloudWatch log streams, when it has some; once it ends, its
 * standard output and error go, gzipped, to the run's log folder in Floci's S3, when it has one. An EMR step's
 * container mounts its cluster's storage, where {@code hdfs://} paths, and paths without a scheme, lead, and a
 * step's {@code hadoop} command, and a MapReduce job its {@code spark-submit} starts, reach Floci's S3 and run
 * MapReduce in local mode, with the cluster's Hadoop configuration. A Hive run starts {@code hive} in a container of
 * the release's EMR Serverless Hive image instead, with Tez in local mode in Hive's JVM, S3A on Floci's S3 and the
 * Glue Data Catalog client, the image's metastore, on Floci's Glue; an EMR step's in the image of its cluster, which
 * holds Spark and Hive, on its storage, with the cluster's HDFS and the cluster's own metastore.
 */
@ApplicationScoped
public class EmrServerlessSparkRunner implements SparkJobRunner, ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(EmrServerlessSparkRunner.class);
    private static final String SPARK_SUBMIT = "/usr/lib/spark/bin/spark-submit";
    private static final String HIVE = "/usr/bin/hive";
    // hive's option for a setting, and its single-dash form.
    private static final String HIVECONF_OPTION = "--hiveconf";
    private static final String HIVECONF_SHORT_OPTION = "-hiveconf";
    private static final String SPARK_DEFAULTS_VARIABLE = "FLOCI_SPARK_DEFAULTS";
    private static final String HADOOP_SITE_VARIABLE = "FLOCI_HADOOP_SITE";
    private static final String SPARK_LOG4J2_VARIABLE = "FLOCI_SPARK_LOG4J2";
    private static final String MAPRED_SITE_VARIABLE = "FLOCI_MAPRED_SITE";
    private static final String HADOOP_LOG4J_VARIABLE = "FLOCI_HADOOP_LOG4J";
    private static final String HADOOP_ENV_VARIABLE = "FLOCI_HADOOP_ENV";
    private static final Pattern SHELL_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    // A cluster's HDFS, as Hadoop settings: hdfs:// paths, and those without a scheme ("hdfs:// (or no prefix)"),
    // lead to the HDFS folder of the cluster's /mnt volume, through a view of it under the hdfs scheme, as the
    // image has no HDFS of its own.
    private static final String HDFS_FOLDER = "/mnt/hdfs";
    private static final List<SparkSetting> HDFS_SETTINGS = List.of(
            new SparkSetting("fs.defaultFS", "hdfs:///"),
            new SparkSetting("fs.hdfs.impl", "org.apache.hadoop.fs.viewfs.ViewFileSystemOverloadScheme"),
            new SparkSetting("fs.viewfs.mounttable.default.linkFallback", "file://" + HDFS_FOLDER));
    // Spark's default checkpoint manager goes through Hadoop's FileContext, which refuses an hdfs:// path
    // without a host, so a streaming query keeps its checkpoints through the file system instead.
    private static final SparkSetting FILE_SYSTEM_CHECKPOINTS = new SparkSetting(
            "spark.sql.streaming.checkpointFileManagerClass",
            "org.apache.spark.sql.execution.streaming.FileSystemBasedCheckpointFileManager");
    // Where Hadoop's local job runner puts a job's files, -files and -archives among them: a folder of the local
    // file system, whose files it then names by their path alone, which leads to the cluster's HDFS.
    private static final String MAPRED_STAGING = "/tmp/hadoop/mapred/staging";
    // A step's MapReduce runs in the JVM that starts it, as there is no YARN to run it on.
    private static final String MAPRED_SITE = "<property><name>mapreduce.framework.name</name><value>local</value>"
            + "</property><property><name>mapreduce.jobtracker.staging.root.dir</name><value>" + MAPRED_STAGING
            + "</value></property>";
    // The image's Hadoop log4j.properties is empty, so a hadoop command logs nothing, a streaming job's reason for
    // failing included; one that is empty logs to standard error instead, in the layout of the step log lines the
    // Management Guide shows ("ERROR org.apache.hadoop.streaming.StreamJob (main): Error Launching job : ...").
    private static final List<String> HADOOP_CONSOLE_LOG = List.of(
            "hadoop.root.logger=INFO,console",
            "log4j.rootLogger=${hadoop.root.logger}",
            "log4j.appender.console=org.apache.log4j.ConsoleAppender",
            "log4j.appender.console.target=System.err",
            "log4j.appender.console.layout=org.apache.log4j.PatternLayout",
            "log4j.appender.console.layout.ConversionPattern=%d{ISO8601} %p %c (%t): %m%n");
    // MapReduce, which a streaming job, the LZO indexer and any other hadoop jar run in, is only in Spark's shaded
    // Hadoop client, and S3A's AWS SDK only in the jar Spark's own S3A finds it in; links to them make up the hadoop
    // command's MapReduce home, whose jars it puts after its own, whatever HADOOP_CLASSPATH a command sets. The names
    // are those of the emr-7.5.0 and emr-7.10.0 images; one another image lacks is named on standard error, as a job
    // that needs it fails later, on its class path.
    private static final String MAPRED_HOME = "/tmp/floci-mapreduce";
    // No source gives AWS's wording for this; it is Floci's.
    private static final String MISSING_JAR = "Floci: this image has no $jar, so the hadoop command may not find"
            + " MapReduce or S3A's AWS SDK.";
    private static final List<String> MAPRED_JARS = List.of(
            "/usr/lib/spark/jars/hadoop-client-api-*.jar",
            "/usr/lib/spark/jars/hadoop-client-runtime-*.jar",
            "/usr/lib/spark/jars/emr-serverless-spark-goodies.jar");
    // A Spark configuration of the container's own: a copy of the folder a spark-env export of SPARK_CONF_DIR names,
    // else of the image's, links followed, whose log4j2.properties ends with the job's lines, passed in
    // SPARK_LOG4J2_VARIABLE, a later line winning. What is added to a file starts on a line of its own, as the
    // image's log4j2.properties has no line break at its end.
    private static final List<String> SPARK_CONF_COPY = List.of(
            "conf=/tmp/floci-spark-conf",
            "mkdir -p \"$conf\"",
            "cp -rL \"${SPARK_CONF_DIR:-/usr/lib/spark/conf}/.\" \"$conf\" 2>/dev/null",
            "if [ -n \"${" + SPARK_LOG4J2_VARIABLE + ":-}\" ]; then",
            "  printf '\\n%s\\n' \"$" + SPARK_LOG4J2_VARIABLE + "\" >> \"$conf/log4j2.properties\"",
            "fi",
            "export SPARK_CONF_DIR=\"$conf\"");
    // On a cluster, the hadoop command, and MapReduce in a step's spark-submit, get its HDFS and Floci's S3 too,
    // through a copy of its Hadoop configuration (the image's, or the folder a spark-env export names), links
    // followed, whose core-site.xml takes in the original's through Hadoop's XInclude, whatever its XML, then
    // HADOOP_SITE_VARIABLE's properties, which win (see hadoopSite), and whose mapred-site.xml runs MapReduce in local
    // mode the same way (see mapredSite). The cluster's hadoop-log4j lines follow the log4j.properties, on a line of
    // their own, a later key winning, and its hadoop-env exports start the hadoop-env.sh the hadoop command reads,
    // ahead of the image's own lines, which add to HADOOP_CLASSPATH rather than set it, so a HADOOP_CLASSPATH the
    // cluster sets keeps the LZO codec the image's core-site.xml names. The hadoop script runs only once its HDFS,
    // MapReduce and YARN homes name folders that are there, and the image has /usr/lib/hadoop alone, without
    // MapReduce, so the MapReduce home is Floci's (see MAPRED_HOME). The image's hdfs command cannot run, so hdfs dfs
    // runs as hadoop fs, and its hadoop-streaming names a jar it does not have, so the one it has runs.
    private static final List<String> HADOOP_CONF_SETUP = List.of(
            "if [ -n \"${" + HADOOP_SITE_VARIABLE + ":-}\" ]; then",
            "  hadoop_conf=/tmp/floci-hadoop-conf source_conf=\"${HADOOP_CONF_DIR:-/etc/hadoop/conf}\"",
            "  mkdir -p \"$hadoop_conf\" /tmp/floci-bin " + MAPRED_HOME,
            "  cp -rL \"$source_conf/.\" \"$hadoop_conf\" 2>/dev/null",
            "  site() {",
            "    include=",
            "    if [ -f \"$hadoop_conf/$1\" ]; then",
            "      mv \"$hadoop_conf/$1\" \"$hadoop_conf/floci-original-$1\"",
            "      include=\"<xi:include href=\\\"$hadoop_conf/floci-original-$1\\\"/>\"",
            "    fi",
            "    printf '%s\\n' '<configuration xmlns:xi=\"http://www.w3.org/2001/XInclude\">' \"$include\" \"$2\""
                    + " '</configuration>' > \"$hadoop_conf/$1\"",
            "  }",
            "  site core-site.xml \"$" + HADOOP_SITE_VARIABLE + "\"",
            "  site mapred-site.xml \"$" + MAPRED_SITE_VARIABLE + "\"",
            // The local staging folder is a link to the same path in the HDFS folder, so a job's files are where
            // their path leads.
            "  mkdir -p \"" + HDFS_FOLDER + MAPRED_STAGING + "\" \"$(dirname " + MAPRED_STAGING + ")\"",
            "  ln -sfn \"" + HDFS_FOLDER + MAPRED_STAGING + "\" " + MAPRED_STAGING,
            "  if [ ! -s \"$hadoop_conf/log4j.properties\" ]; then",
            "    printf '%s\\n' '" + String.join("' '", HADOOP_CONSOLE_LOG) + "' > \"$hadoop_conf/log4j.properties\"",
            "  fi",
            "  if [ -n \"${" + HADOOP_LOG4J_VARIABLE + ":-}\" ]; then",
            "    printf '\\n%s\\n' \"$" + HADOOP_LOG4J_VARIABLE + "\" >> \"$hadoop_conf/log4j.properties\"",
            "  fi",
            "  if [ -n \"${" + HADOOP_ENV_VARIABLE + ":-}\" ]; then",
            "    { printf '%s\\n' \"$" + HADOOP_ENV_VARIABLE + "\"; cat \"$hadoop_conf/hadoop-env.sh\" 2>/dev/null; }"
                    + " > \"$hadoop_conf/floci-hadoop-env.sh\"",
            "    mv \"$hadoop_conf/floci-hadoop-env.sh\" \"$hadoop_conf/hadoop-env.sh\"",
            "  fi",
            "  printf '%s\\n' '#!/bin/bash' 'if [ \"$1\" = dfs ]; then shift; exec hadoop fs \"$@\"; fi'"
                    + " 'echo \"hdfs $1: only hdfs dfs runs here, as hadoop fs\" >&2' 'exit 1' > /tmp/floci-bin/hdfs",
            "  printf '%s\\n' '#!/bin/bash' 'exec hadoop jar /usr/lib/hadoop/hadoop-streaming.jar \"$@\"'"
                    + " > /tmp/floci-bin/hadoop-streaming",
            "  chmod +x /tmp/floci-bin/hdfs /tmp/floci-bin/hadoop-streaming",
            "  for jar in " + String.join(" ", MAPRED_JARS) + "; do",
            "    if [ -f \"$jar\" ]; then ln -sf \"$jar\" " + MAPRED_HOME + "/;",
            "    else echo \"" + MISSING_JAR + "\" >&2; fi",
            "  done",
            "  export HADOOP_CONF_DIR=\"$hadoop_conf\" PATH=\"/tmp/floci-bin:$PATH\"",
            "  export HADOOP_HDFS_HOME=\"${HADOOP_HDFS_HOME:-/usr/lib/hadoop}\"",
            "  export HADOOP_MAPRED_HOME=\"${HADOOP_MAPRED_HOME:-" + MAPRED_HOME + "}\"",
            "  export HADOOP_YARN_HOME=\"${HADOOP_YARN_HOME:-/usr/lib/hadoop}\"",
            "fi");
    // A step on a cluster, or one whose job has log4j2.properties lines, runs spark-submit after this.
    private static final String SPARK_CONF_WRAPPER = wrapper(SPARK_CONF_COPY, HADOOP_CONF_SETUP);
    // A command runs after this sets up a Spark configuration of its own, so a spark-submit it starts runs
    // locally on Floci's S3: the image's spark-defaults.conf names EMR Serverless's resource manager as its
    // master, which a system property cannot override, so its master and that manager's lines give way to
    // local mode, Floci's settings and the job's spark-defaults, passed in SPARK_DEFAULTS_VARIABLE.
    private static final String COMMAND_WRAPPER = wrapper(SPARK_CONF_COPY, List.of(
            "touch \"$conf/spark-defaults.conf\"",
            "sed -i -e '/^spark\\.master[[:space:]]/d' -e '/^spark\\.submit\\.deployMode[[:space:]]/d'"
                    + " -e '/^spark\\.submit\\.customResourceManager/d' \"$conf/spark-defaults.conf\"",
            "printf '\\n%s\\n' \"$" + SPARK_DEFAULTS_VARIABLE + "\" >> \"$conf/spark-defaults.conf\""),
            HADOOP_CONF_SETUP);
    // The archives a driver needs in its working directory are put in this folder of its container, each in a
    // numbered folder of its own, which the copy creates.
    private static final String ARCHIVES_FOLDER = "/tmp/floci-archives";
    // While an archive is streamed in, a stop is looked for after each this many bytes, as Progress.cancelled()
    // takes the run service's lock.
    private static final long STOP_LOOK_BYTES = 4L * 1024 * 1024;
    private static final int ARCHIVE_BUFFER_BYTES = 64 * 1024;
    // Gets each archive's file and the directory it goes to, then "--" and what runs after, and unpacks each into
    // the working directory as Spark's Utils.unpack does: a .jar or .zip unzipped, a .tar.gz, .tgz or .tar
    // untarred, and any other file copied.
    private static final String ARCHIVES_WRAPPER = String.join("\n",
            "shopt -s nocasematch",
            "while [ \"$1\" != -- ]; do",
            "  case \"$1\" in",
            "    *.jar|*.zip) mkdir -p \"$2\" && unzip -q -o \"$1\" -d \"$2\" ;;",
            "    *.tar.gz|*.tgz|*.tar) mkdir -p \"$2\" && tar -xf \"$1\" -C \"$2\" ;;",
            "    *) cp \"$1\" \"$2\" ;;",
            "  esac || exit",
            "  shift 2",
            "done",
            "shift",
            "exec \"$@\"");
    private static final String DEFAULT_IMAGE =
            "public.ecr.aws/emr-serverless/spark/%s:latest"; // partition-literal: ECR Public's registry host, where AWS publishes the base images; image-template overrides it
    // The AWS runtime for Apache Spark (emr-spark-8.0.0 on) publishes its images under spark4/ instead.
    private static final String SPARK_RUNTIME_DEFAULT_IMAGE =
            "public.ecr.aws/emr-serverless/spark4/%s:latest"; // partition-literal: ECR Public's registry host, where AWS publishes the base images; image-template overrides it
    private static final String HIVE_DEFAULT_IMAGE =
            "public.ecr.aws/emr-serverless/hive/%s:latest"; // partition-literal: ECR Public's registry host, where AWS publishes the base images; hive-image-template overrides it
    // Hive's settings beside Floci's Hadoop ones. The image's Tez runs its DAGs through EMR Serverless's own container
    // plugins, which the public image cannot reach, so Tez runs them in local mode, in Hive's JVM. The port range its
    // client talks to it on is a placeholder EMR Serverless fills.
    private static final List<SparkSetting> HIVE_SETTINGS = List.of(
            new SparkSetting("tez.local.mode", "true"),
            new SparkSetting("tez.am.client.am.port-range", "40000-40100"));
    // On a cluster, after the HDFS settings, Hive's file system is their view of the cluster's HDFS under the viewfs
    // scheme: Hive looks up the HDFS encryption zone of a path of the hdfs scheme, which only HDFS answers, as its
    // shim takes that path's file system for HDFS's own, so a table Hive makes in the cluster's HDFS is at a viewfs:
    // path, the folder an hdfs:// path leads to in other steps. Its metastore is the cluster's own, as "Hive records
    // metastore information in a MySQL database on the primary node's file system": an embedded Derby database in
    // the cluster's /mnt, whose tables DataNucleus creates on first use. A cluster's hive-site replaces them, with
    // the Glue Data Catalog's client factory for one.
    private static final List<SparkSetting> HIVE_CLUSTER_SETTINGS = List.of(
            new SparkSetting("fs.defaultFS", "viewfs:///"),
            new SparkSetting("hive.metastore.client.factory.class",
                    "org.apache.hadoop.hive.ql.metadata.SessionHiveMetaStoreClientFactory"),
            new SparkSetting("javax.jdo.option.ConnectionURL",
                    "jdbc:derby:;databaseName=/mnt/var/lib/hive/metastore_db;create=true"),
            new SparkSetting("hive.metastore.schema.verification", "false"),
            new SparkSetting("datanucleus.schema.autoCreateAll", "true"));
    // Hive logs to a file in the folder EMR Serverless names in LOGS_DIR, its HIVE_LOG, apart from the driver's standard
    // output and error.
    private static final List<String> HIVE_ENVIRONMENT = List.of("LOGS_DIR=/tmp/floci-hive-logs");
    private static final String HIVE_SCRATCH_VARIABLE = "FLOCI_HIVE_SCRATCH";
    /**
     * Where an EMR cluster's image with Hive keeps its Hive image's Hadoop configuration, which puts Tez on the class
     * path, apart from the one its other steps' Hadoop reads; Hive runs with it where an image has it.
     */
    public static final String HIVE_HADOOP_CONF = "/etc/hadoop/conf.hive";
    private static final String HIVE_SCRATCHDIR = "hive.exec.scratchdir";
    // The user every EMR Serverless image runs as: a custom one sets "the USER back to hadoop:hadoop".
    private static final String IMAGE_USER = "hadoop";
    // Starts hive as the image's user, after what EMR Serverless's runtime would do. The image's tez-site.xml takes
    // Tez's addresses from variables that runtime sets: Tez in local mode listens on every address of the container
    // and names it by its host name, which its own client connects to. Hive puts Tez's staging folder under the
    // session's scratch folder, which a run puts in S3, and Tez in local mode copies it to the same path on the local
    // file system (LocalClient), a copy that silently does nothing where the image's user cannot create that path, at
    // the root of the container for one; so the container starts as root, makes that path its user's, then runs hive
    // as that user, in that user's home, as a Spark image's working directory is: Tez in local mode puts each task's
    // files in the working directory, and the Hive image names none, which leaves the root. In an EMR cluster's image
    // with Hive, Hive reads its own Hadoop configuration (HIVE_HADOOP_CONF).
    private static final String HIVE_WRAPPER = String.join("\n",
            "host=$(hostname)",
            "export CLIENT_AM_BIND_ADDRESS=0.0.0.0 TASK_AM_BIND_ADDRESS=0.0.0.0 SHUFFLE_BIND_ADDRESS=0.0.0.0",
            "export CLIENT_AM_HOSTNAME=\"$host\" TASK_AM_HOSTNAME=\"$host\" HOST_ADDRESS_LOCAL=\"$host\"",
            "if [ -n \"${" + HIVE_SCRATCH_VARIABLE + ":-}\" ]; then",
            "  mkdir -p \"$" + HIVE_SCRATCH_VARIABLE + "\" && chown " + IMAGE_USER + ":" + IMAGE_USER + " \"$"
                    + HIVE_SCRATCH_VARIABLE + "\" || exit",
            "fi",
            "if [ -d " + HIVE_HADOOP_CONF + " ]; then export HADOOP_CONF_DIR=" + HIVE_HADOOP_CONF + "; fi",
            "export HOME=~" + IMAGE_USER,
            "cd \"$HOME\" || exit",
            // The image's user has no other group, as Docker starts it.
            "exec setpriv --reuid=" + IMAGE_USER + " --regid=" + IMAGE_USER + " --clear-groups \"$@\"");
    // "The default behavior for shutdown is to have no grace period": immediate termination.
    private static final int STOP_TIMEOUT_SECONDS = 0;
    // The characters a backslash escapes inside double quotes; before any other it is kept.
    private static final String DOUBLE_QUOTE_ESCAPES = "\"\\$`\n";
    // How often a running driver is looked at, as Batch's runner looks at its jobs.
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    private static final String GZIP = "application/x-gzip";
    // How long reading an ended driver's output may take; Docker's log rotation bounds its size.
    private static final Duration LOG_READ_TIMEOUT = Duration.ofSeconds(60);

    // The runs inside run(); a stop for a run not here comes after its driver has gone, and is dropped.
    private final ContainerLaunches launches = new ContainerLaunches();

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final LaunchedContainerAwsEnv awsEnv;
    private final LambdaExecutionRoleCredentials roleCredentials;
    private final S3Service s3Service;
    private final EcrRegistryManager ecrRegistryManager;
    private final EmulatorConfig config;
    private final Clock clock;
    private final Duration pollInterval;

    @Inject
    public EmrServerlessSparkRunner(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                    ContainerLogStreamer logStreamer, LaunchedContainerAwsEnv awsEnv,
                                    LambdaExecutionRoleCredentials roleCredentials, S3Service s3Service,
                                    EcrRegistryManager ecrRegistryManager, EmulatorConfig config, Clock clock) {
        this(containerBuilder, lifecycleManager, logStreamer, awsEnv, roleCredentials, s3Service, ecrRegistryManager,
                config, clock, POLL_INTERVAL);
    }

    EmrServerlessSparkRunner(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                             ContainerLogStreamer logStreamer, LaunchedContainerAwsEnv awsEnv,
                             LambdaExecutionRoleCredentials roleCredentials, S3Service s3Service,
                             EcrRegistryManager ecrRegistryManager, EmulatorConfig config, Clock clock,
                             Duration pollInterval) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.awsEnv = awsEnv;
        this.roleCredentials = roleCredentials;
        this.s3Service = s3Service;
        this.ecrRegistryManager = ecrRegistryManager;
        this.config = config;
        this.clock = clock;
        this.pollInterval = pollInterval;
    }

    @Override
    public Outcome run(Job job, Progress progress) {
        ContainerLaunches.Launch launch = launches.begin(job.runId());
        Optional<SessionCreds> credentials = Optional.empty();
        Closeable logHandle = null;
        try {
            // A cancel before the run was in launches had nothing to stop, so it is read here.
            if (progress.cancelled()) {
                return Outcome.halted();
            }
            credentials = roleCredentials.forRole(job.executionRole(), job.accountId(), job.runId());
            Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                    job.service(), job.runId(), job.accountId(), job.region()));
            // The deployment that started the driver, which removeLeftoverDrivers() goes by.
            labels.put(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config));
            String endpoint = awsEnv.flociEndpoint();
            List<Archive> archives = archives(job);
            ContainerBuilder.Builder container = containerBuilder.newContainer(launchImage(job))
                    .withName(ContainerStorageHelper.dockerName(config, job.service() + "-" + job.runId()))
                    .withEnv(environment(
                            awsEnv.sdkBaselineEnv(job.region(), Optional.empty(), credentials, job.accountId()),
                            job.environment()));
            // bash -c's script gets "floci" as its $0, and what runs after it as its arguments, "$@".
            if (job.engine() == Engine.HIVE) {
                List<String> hive = hiveArguments(job, endpoint);
                List<String> entrypoint = new ArrayList<>(List.of("/bin/bash", "-c", HIVE_WRAPPER, "floci"));
                entrypoint.addAll(job.command() != null ? job.command() : List.of(HIVE));
                container.withUser("root")
                        .withEntrypoint(entrypoint)
                        .withCmd(hive)
                        .withEnv(HIVE_ENVIRONMENT);
                String scratch = hiveScratchMirror(hive);
                if (scratch != null) {
                    container.withEnv(HIVE_SCRATCH_VARIABLE, scratch);
                }
            } else if (job.command() == null) {
                List<String> sparkSubmit = job.sparkLog4j2().isEmpty() && job.storage() == null
                        ? List.of(SPARK_SUBMIT) : List.of("/bin/bash", "-c", SPARK_CONF_WRAPPER, "floci", SPARK_SUBMIT);
                container.withEntrypoint(unpackingFirst(archives, sparkSubmit))
                        .withCmd(sparkSubmitArguments(job, endpoint));
            } else {
                container.withEntrypoint(unpackingFirst(archives, List.of("/bin/bash", "-c", COMMAND_WRAPPER, "floci")))
                        .withCmd(job.command())
                        .withEnv(SPARK_DEFAULTS_VARIABLE, sparkDefaultsFile(job, endpoint));
            }
            if (!job.sparkLog4j2().isEmpty()) {
                container.withEnv(SPARK_LOG4J2_VARIABLE, sparkLog4j2File(job));
            }
            if (job.storage() != null) {
                container.withNamedVolume(job.storage().home(), "/home/hadoop")
                        .withNamedVolume(job.storage().mnt(), "/mnt");
            }
            // Hive's settings reach it as --hiveconf settings, so only a Spark job's container sets up Hadoop.
            if (job.storage() != null && job.engine() == Engine.SPARK) {
                container.withEnv(HADOOP_SITE_VARIABLE, hadoopSite(job, endpoint))
                        .withEnv(MAPRED_SITE_VARIABLE, mapredSite(job));
                if (!job.hadoopLog4j().isEmpty()) {
                    container.withEnv(HADOOP_LOG4J_VARIABLE, hadoopLog4jFile(job));
                }
                if (!job.hadoopEnvironment().isEmpty()) {
                    container.withEnv(HADOOP_ENV_VARIABLE, hadoopEnvFile(job));
                }
            }
            ContainerSpec spec = container
                    .withDockerNetwork(Optional.ofNullable(job.dockerNetwork()))
                    .withHostDockerInternalOnLinux()
                    .withEmbeddedDns()
                    .withLogRotation()
                    .withLabels(labels)
                    .withJavaTrustStore()
                    .build();
            String containerId = lifecycleManager.create(spec);
            launch.started(containerId);
            // A stop, a reset or a shutdown while the image was pulled, the trust store copied or the archives put
            // in is read now, before the driver starts; one before an archive or while it streams in puts in no
            // more of them.
            BooleanSupplier stopped = () -> launch.stopRequested() || progress.cancelled();
            putArchives(job, archives, containerId, stopped);
            if (stopped.getAsBoolean()) {
                return Outcome.halted();
            }
            lifecycleManager.startCreated(containerId, spec);
            logHandle = attachLog(containerId, job);
            progress.started();
            Outcome outcome = awaitEnd(containerId, job, launch, progress);
            writeLogs(job, launch);
            return outcome;
        } catch (InterruptedException e) {
            // Floci is shutting down: its teardown stops the driver.
            Thread.currentThread().interrupt();
            return Outcome.halted();
        } catch (RuntimeException e) {
            if (launch.stopRequested()) {
                // A reset removed the driver while it was being watched.
                return Outcome.halted();
            }
            LOG.warnv(e, "Spark run {0} of {1} could not run", job.runId(), job.service());
            return Outcome.couldNotStart(e.getMessage());
        } finally {
            String unclaimed = launches.end(job.runId(), launch);
            if (unclaimed != null) {
                lifecycleManager.stopAndRemove(unclaimed, logHandle, STOP_TIMEOUT_SECONDS);
            } else if (logHandle != null) {
                // Floci's teardown claimed the driver, but the log stream is still this run's to close.
                lifecycleManager.closeLogStreamAfterContainerStop(logHandle);
            }
            credentials.ifPresent(session -> roleCredentials.unregister(job.accountId(), session.accessKeyId()));
        }
    }

    @Override
    public void stop(String runId) {
        ContainerLaunches.Launch launch = launches.get(runId);
        if (launch != null) {
            // run() reads it once the driver's container is up and at each look at the driver after, and
            // removes the driver itself: a stop makes no Docker call, so no Docker error can lose it.
            launch.requestStop();
        }
    }

    /**
     * Stops every run inside {@link #run}, so a reset or a shutdown orphans none: a running driver is
     * stopped and removed now, and one whose image is still being pulled as soon as its container is up.
     */
    @Override
    public void stopManagedContainers() {
        launches.stopAll(lifecycleManager, STOP_TIMEOUT_SECONDS);
    }

    /**
     * Removes the drivers of {@code service}'s runs an earlier Floci process left running, which no thread
     * follows any more: their runs are {@code FAILED} once read, so nothing would ever stop them. The
     * service calls this before it hands a run to {@link #run}, so every driver of this deployment it
     * finds is a leftover.
     */
    @Override
    public void removeLeftoverDrivers(String service) {
        String owner = ContainerStorageHelper.ownerIdentity(config);
        ContainerStorageHelper.LabelAliases aliases = ContainerStorageHelper.CONTAINER_LABEL_ALIASES;
        try {
            List<Container> containers = aliases.listByLabels(
                    Map.of(ContainerStorageHelper.SERVICE_LABEL, service, ContainerStorageHelper.OWNER_LABEL, owner),
                    filter -> lifecycleManager.getDockerClient().listContainersCmd()
                            .withShowAll(true)
                            .withLabelFilter(filter)
                            .exec(),
                    Container::getId);
            for (Container container : containers) {
                Map<String, String> labels = container.getLabels() != null ? container.getLabels() : Map.of();
                if (aliases.consistent(container.getId(), labels)
                        && aliases.matches(labels, ContainerStorageHelper.OWNER_LABEL, owner)) {
                    lifecycleManager.removeIfExists(container.getId());
                    LOG.infov("Removed {0} Spark driver {1}, left running by an earlier Floci process", service,
                            container.getId());
                }
            }
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove the {0} Spark drivers an earlier Floci process left: {1}", service,
                    e.getMessage());
        }
    }

    // Floci's log always gets the driver's output, and the job's CloudWatch log streams too when it has some.
    private Closeable attachLog(String containerId, Job job) {
        String prefix = job.service() + ":" + job.runId();
        CloudWatchLogs cloudWatch = job.cloudWatchLogs();
        if (cloudWatch == null) {
            return logStreamer.attachConsoleOnly(containerId, prefix);
        }
        return logStreamer.attachByStreamForAccount(job.accountId(), containerId, cloudWatch.logGroup(),
                cloudWatch.stdoutStream(), cloudWatch.stderrStream(), job.region(), prefix);
    }

    /**
     * The folder {@code path} names under {@code logUri}, the S3 location a service writes its logs to, or
     * null when there is none: {@code path} follows {@code logUri} after one slash, whether or not
     * {@code logUri} ends in one.
     */
    public static String logFolder(String logUri, String path) {
        if (logUri == null || logUri.isBlank()) {
            return null;
        }
        int end = logUri.length();
        while (end > 0 && logUri.charAt(end - 1) == '/') {
            end--;
        }
        return logUri.substring(0, end) + "/" + path;
    }

    /**
     * Puts a process's gzipped standard output and error at {@code stdout.gz} and {@code stderr.gz} in
     * {@code folder}, an S3 folder ending in a slash, in any of the Hadoop schemes, as {@code accountId} in
     * {@code region}.
     */
    public static void putLogs(S3Service s3Service, String accountId, String region, String folder, byte[] stdout,
                               byte[] stderr) {
        putLogs(s3Service, accountId, region, folder, new ByteArrayInputStream(stdout),
                new ByteArrayInputStream(stderr));
    }

    /** As the other {@code putLogs} does, with each file read from a stream, so it need not be held in memory. */
    public static void putLogs(S3Service s3Service, String accountId, String region, String folder,
                               InputStream stdout, InputStream stderr) {
        S3Uri location = S3Uri.parseAnyScheme(folder);
        RequestScopes.runAs(accountId, region, () -> {
            s3Service.putObjectFromStream(location.bucket(), location.key() + "stdout.gz", stdout, GZIP, Map.of());
            s3Service.putObjectFromStream(location.bucket(), location.key() + "stderr.gz", stderr, GZIP, Map.of());
        });
    }

    /**
     * The image a release's Spark runs use: {@code imageTemplate}, with {@code %s} standing for the release
     * label, or, when it is null, the release's public EMR Serverless image.
     */
    public static String image(String imageTemplate, String releaseLabel) {
        return image(imageTemplate, releaseLabel, Engine.SPARK);
    }

    /** The image a release's runs on {@code engine} use, as the other {@code image} says, Hive's for Hive. */
    public static String image(String imageTemplate, String releaseLabel, Engine engine) {
        String template = imageTemplate != null ? imageTemplate
                : engine == Engine.HIVE ? HIVE_DEFAULT_IMAGE
                : releaseLabel.startsWith("emr-spark-") ? SPARK_RUNTIME_DEFAULT_IMAGE : DEFAULT_IMAGE;
        return template.formatted(releaseLabel);
    }

    /** A driver's environment: Floci's own variables, then the job's, one of which replaces Floci's of its name. */
    static List<String> environment(List<String> flocis, Map<String, String> jobs) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (String variable : flocis) {
            int equals = variable.indexOf('=');
            byName.put(equals < 0 ? variable : variable.substring(0, equals), variable);
        }
        jobs.forEach((name, value) -> byName.put(name, name + "=" + value));
        return new ArrayList<>(byName.values());
    }

    /**
     * A job's archives in S3, which its driver finds in its working directory: each with the file it is put at, in
     * a folder of its own, and the directory it is unpacked to, named as Spark names it, by what follows its
     * {@code #} or else by its file's name. Spark alone unpacks the others, into its own files folder.
     */
    static List<Archive> archives(Job job) {
        List<Archive> archives = new ArrayList<>();
        for (String archive : job.archives()) {
            int hash = archive.indexOf('#');
            String path = hash < 0 ? archive : archive.substring(0, hash);
            if (!S3Uri.isS3(path)) {
                continue;
            }
            S3Uri location = S3Uri.parseAnyScheme(path);
            String name = location.key().substring(location.key().lastIndexOf('/') + 1);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("The archive " + archive + " names no object.");
            }
            String file = ARCHIVES_FOLDER + "/" + (archives.size() + 1) + "/" + name;
            archives.add(new Archive(path, location, file, hash < 0 ? name : archive.substring(hash + 1)));
        }
        return archives;
    }

    /** {@code entrypoint}, after the wrapper that unpacks {@code archives} into the working directory, if any. */
    static List<String> unpackingFirst(List<Archive> archives, List<String> entrypoint) {
        if (archives.isEmpty()) {
            return entrypoint;
        }
        // bash -c's script gets "floci" as its $0, then each archive's file and directory, "--" and the entrypoint.
        List<String> unpacking = new ArrayList<>(List.of("/bin/bash", "-c", ARCHIVES_WRAPPER, "floci"));
        for (Archive archive : archives) {
            unpacking.add(archive.file());
            unpacking.add(archive.directory());
        }
        unpacking.add("--");
        unpacking.addAll(entrypoint);
        return unpacking;
    }

    /**
     * The image a run's driver starts from: the release's, or the template's, or the application's custom
     * image. A custom image's ECR URI leads to Floci's own registry, as a Lambda function's or an ECS task's
     * does, and the image is pulled for every run, as an ECS task's is, so a tag pushed again is what the next
     * run gets ("New workloads submitted after the update automatically will use the new image"). The driver
     * starts from the image id that pull resolved, which a later push cannot move.
     */
    private String launchImage(Job job) {
        if (job.customImage() == null) {
            return image(job.imageTemplate(), job.releaseLabel(), job.engine());
        }
        String image = containerBuilder.resolveImage(ecrRegistryManager.rewriteImageUri(job.customImage()));
        return lifecycleManager.resolveImageForLaunch(image, ImagePullBehavior.DEFAULT, null).imageId();
    }

    /**
     * Puts each archive in the created container, for its entrypoint to unpack: read from Floci's S3 as the run's
     * account and streamed in, so Floci holds little of it however large it is. One that cannot be read fails
     * the run, naming it. Once the run is {@code stopped}, no other archive is put in, and the one streaming in
     * stops, leaving run() to read the stop.
     */
    private void putArchives(Job job, List<Archive> archives, String containerId, BooleanSupplier stopped) {
        for (Archive archive : archives) {
            if (stopped.getAsBoolean()) {
                return;
            }
            S3Uri location = archive.location();
            try {
                RequestScopes.runAs(job.accountId(), job.region(),
                        () -> s3Service.headObject(location.bucket(), location.key()));
            } catch (AwsException e) {
                throw new IllegalArgumentException(
                        "Could not read the archive " + archive.path() + ": " + e.getMessage(), e);
            }
            try {
                // Extracted at the root, the entry is the file's path without its leading slash.
                RetryingTarCopier.copyStreamed(lifecycleManager.getDockerClient(), containerId, "/", archive.path(),
                        out -> {
                            try (S3Service.ObjectRead read = RequestScopes.callAs(job.accountId(), job.region(),
                                    () -> s3Service.openObject(location.bucket(), location.key(), null));
                                 TarArchiveOutputStream tar = RetryingTarCopier.newTarStream(out)) {
                                TarArchiveEntry entry = new TarArchiveEntry(archive.file().substring(1));
                                entry.setSize(read.object().getSize());
                                entry.setMode(0644);
                                tar.putArchiveEntry(entry);
                                transferUntilStopped(read.body(), tar, stopped);
                                tar.closeArchiveEntry();
                            }
                        });
            } catch (RuntimeException e) {
                // A copy the stop ended, or one whose container a reset removed.
                if (stopped.getAsBoolean()) {
                    return;
                }
                throw e;
            }
        }
    }

    /** Copies {@code in} to {@code out}, failing once the run is {@code stopped}, as looked at each few MiB. */
    private static void transferUntilStopped(InputStream in, OutputStream out, BooleanSupplier stopped)
            throws IOException {
        byte[] buffer = new byte[ARCHIVE_BUFFER_BYTES];
        long sinceLook = 0;
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            out.write(buffer, 0, read);
            sinceLook += read;
            if (sinceLook >= STOP_LOOK_BYTES) {
                sinceLook = 0;
                if (stopped.getAsBoolean()) {
                    throw new IOException("the job run was stopped");
                }
            }
        }
    }

    /**
     * {@code spark-submit}'s arguments: local mode, S3A on Floci's S3 for {@code s3://} and {@code s3a://}
     * paths, the cluster's HDFS for {@code hdfs://} paths and those without a scheme on a step, then the run's
     * {@code spark-defaults} and its own options, which win over what comes before them, then its
     * application and that application's arguments. Before release 7.10.0
     * {@code s3://} is EMRFS, which ignores the endpoint setting, so it is switched to S3A. S3A writes
     * are committed through commit data the writer keeps in memory, without which EMR's committer finds
     * none and a write is silently lost.
     */
    static List<String> sparkSubmitArguments(Job job, String flociEndpoint) {
        List<String> arguments = new ArrayList<>(List.of("--master", "local[*]"));
        for (SparkSetting setting : sparkSettings(job, flociEndpoint)) {
            conf(arguments, setting.key(), setting.value());
        }
        arguments.addAll(job.application());
        return arguments;
    }

    /**
     * The {@code spark-defaults.conf} lines a command's own {@code spark-submit} runs with: local mode, then
     * the settings {@link #sparkSubmitArguments} passes, a later line winning over an earlier one, and the
     * submit's own options over them all. Spark reads the file as a {@code Properties} file, so each
     * key and value is escaped for it, to reach Spark as the job gave it.
     */
    static String sparkDefaultsFile(Job job, String flociEndpoint) {
        StringBuilder file = new StringBuilder("spark.master local[*]");
        for (SparkSetting setting : sparkSettings(job, flociEndpoint)) {
            file.append('\n').append(propertiesEscaped(setting.key(), true)).append(' ')
                    .append(propertiesEscaped(String.valueOf(setting.value()), false));
        }
        return file.toString();
    }

    /**
     * {@code hive}'s arguments: Floci's S3 and Glue, as a Spark run gets them, Tez in local mode, and on a cluster its
     * HDFS and metastore, each a {@code --hiveconf}, which a later one replaces, then the run's own arguments.
     */
    static List<String> hiveArguments(Job job, String flociEndpoint) {
        List<SparkSetting> settings = new ArrayList<>(hadoopSettings(job, flociEndpoint));
        settings.addAll(HIVE_SETTINGS);
        if (job.storage() != null) {
            settings.addAll(HDFS_SETTINGS);
            settings.addAll(HIVE_CLUSTER_SETTINGS);
        }
        List<String> arguments = new ArrayList<>();
        for (SparkSetting setting : settings) {
            addHiveSetting(arguments, setting.key(), setting.value());
        }
        arguments.addAll(job.application());
        return arguments;
    }

    /** Each of {@code properties} as {@code hive}'s {@code --hiveconf name=value}, in their order. */
    public static List<String> hiveSettings(Map<String, String> properties) {
        List<String> arguments = new ArrayList<>();
        properties.forEach((name, value) -> addHiveSetting(arguments, name, value));
        return arguments;
    }

    private static void addHiveSetting(List<String> arguments, String name, String value) {
        arguments.add(HIVECONF_OPTION);
        arguments.add(name + "=" + value);
    }

    /**
     * The local folder Tez in local mode needs for a Hive session's staging folder, or null when there is none to make:
     * where the path of {@code hive}'s scratch folder, the last {@code hive.exec.scratchdir} its arguments set, leads
     * on the local file system, when that folder is in S3, then the folder of the user Hive runs as, which Hive puts
     * the session's folders in. Hive's default scratch folder is local already. The path is the container's, so it is
     * built with {@code /} whatever Floci runs on, normalized as Hadoop normalizes the staging folder's.
     * <p>
     * Hive's CLI takes a setting as {@code --hiveconf name=value} or {@code --hiveconf=name=value}, with one dash or
     * two, and a setting without an {@code =} takes its value from the next argument, unless that is an option.
     */
    static String hiveScratchMirror(List<String> hiveArguments) {
        String scratch = null;
        for (int i = 0; i < hiveArguments.size(); i++) {
            String argument = hiveArguments.get(i);
            String setting = null;
            if (argument.startsWith(HIVECONF_OPTION + "=") || argument.startsWith(HIVECONF_SHORT_OPTION + "=")) {
                setting = argument.substring(argument.indexOf('=') + 1);
            } else if ((HIVECONF_OPTION.equals(argument) || HIVECONF_SHORT_OPTION.equals(argument))
                    && i + 1 < hiveArguments.size()) {
                setting = hiveArguments.get(++i);
            }
            if (HIVE_SCRATCHDIR.equals(setting)) {
                boolean valued = i + 1 < hiveArguments.size() && !hiveArguments.get(i + 1).startsWith("-");
                scratch = valued ? hiveArguments.get(++i) : null;
            } else if (setting != null && setting.startsWith(HIVE_SCRATCHDIR + "=")) {
                scratch = setting.substring(HIVE_SCRATCHDIR.length() + 1);
            }
        }
        if (scratch == null || !S3Uri.isS3(scratch)) {
            return null;
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : (S3Uri.parseAnyScheme(scratch).key() + "/" + IMAGE_USER).split("/")) {
            if ("..".equals(segment)) {
                segments.pollLast();
            } else if (!segment.isEmpty() && !".".equals(segment)) {
                segments.addLast(segment);
            }
        }
        return "/" + String.join("/", segments);
    }

    /**
     * The {@code core-site.xml} properties a step's {@code hadoop} command gets: Floci's S3 and Glue, as its
     * {@code spark-submit} gets them, its cluster's HDFS, then the job's {@code core-site}, which wins, as a
     * later property does in one file.
     */
    static String hadoopSite(Job job, String flociEndpoint) {
        List<SparkSetting> settings = new ArrayList<>(hadoopSettings(job, flociEndpoint));
        settings.addAll(HDFS_SETTINGS);
        List<String> properties = new ArrayList<>();
        for (SparkSetting setting : settings) {
            properties.add(siteProperty(setting.key(), setting.value()));
        }
        job.coreSite().forEach((name, value) -> properties.add(siteProperty(name, value)));
        return String.join("\n", properties);
    }

    /**
     * The lines a job's Spark gets after those of its configuration's {@code log4j2.properties}, which Log4j
     * reads as a {@code Properties} file, so each key and value is escaped for it.
     */
    static String sparkLog4j2File(Job job) {
        return propertiesLines(job.sparkLog4j2());
    }

    /**
     * The {@code mapred-site.xml} properties a step's MapReduce gets: its cluster's {@code mapred-site}, then
     * Floci's local mode and staging folder, which win, as there is no YARN to run a job on.
     */
    static String mapredSite(Job job) {
        List<String> properties = new ArrayList<>();
        job.mapredSite().forEach((name, value) -> properties.add(siteProperty(name, value)));
        properties.add(MAPRED_SITE);
        return String.join("\n", properties);
    }

    /** The lines a step's {@code hadoop} command gets after those of its {@code log4j.properties}. */
    static String hadoopLog4jFile(Job job) {
        return propertiesLines(job.hadoopLog4j());
    }

    /**
     * The lines that start a step's {@code hadoop-env.sh}: an export of each {@code hadoop-env} variable, as given,
     * in single quotes, with no shell expansion, as its {@code spark-env} ones are. A name a shell cannot export
     * is left out, as its line would run what it holds.
     */
    static String hadoopEnvFile(Job job) {
        List<String> lines = new ArrayList<>();
        job.hadoopEnvironment().forEach((name, value) -> {
            if (SHELL_NAME.matcher(name).matches()) {
                lines.add("export " + name + "='" + value.replace("'", "'\\''") + "'");
            }
        });
        return String.join("\n", lines);
    }

    // Each property as a line Properties.load reads back as it is.
    private static String propertiesLines(Map<String, String> properties) {
        List<String> lines = new ArrayList<>();
        properties.forEach((key, value) ->
                lines.add(propertiesEscaped(key, true) + " = " + propertiesEscaped(value, false)));
        return String.join("\n", lines);
    }

    private static String siteProperty(String name, String value) {
        return "<property><name>" + XmlBuilder.escape(name) + "</name><value>" + XmlBuilder.escape(value)
                + "</value></property>";
    }

    // A wrapper's script: the lines of each part, in order, then what it runs.
    @SafeVarargs
    private static String wrapper(List<String>... parts) {
        List<String> lines = new ArrayList<>();
        for (List<String> part : parts) {
            lines.addAll(part);
        }
        lines.add("exec \"$@\"");
        return String.join("\n", lines);
    }

    // A backslash starts an escape and a line break ends a setting; in a key, whitespace, '=' and ':' end it,
    // and a leading '#' or '!' makes the line a comment; the spaces a value starts with are skipped.
    private static String propertiesEscaped(String text, boolean key) {
        StringBuilder escaped = new StringBuilder();
        boolean leading = true;
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\f' -> escaped.append("\\f");
                case ' ' -> escaped.append(key || leading ? "\\ " : " ");
                case '=', ':', '#', '!' -> escaped.append(key ? "\\" : "").append(c);
                default -> escaped.append(c);
            }
            leading = leading && c == ' ';
        }
        return escaped.toString();
    }

    // Floci's Hadoop settings: S3A on Floci's S3, and the Glue Data Catalog client on Floci's Glue, in the job's
    // account. Given none, the client from emr-7.10.0 asks STS for it, through a client no setting points at Floci.
    private static List<SparkSetting> hadoopSettings(Job job, String flociEndpoint) {
        return List.of(
                new SparkSetting("fs.s3a.endpoint", flociEndpoint),
                new SparkSetting("fs.s3a.endpoint.region", job.region()),
                new SparkSetting("fs.s3a.path.style.access", "true"),
                new SparkSetting("fs.s3a.committer.magic.track.commits.in.memory.enabled", "true"),
                new SparkSetting("fs.s3.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem"),
                new SparkSetting("fs.AbstractFileSystem.s3.impl", "org.apache.hadoop.fs.s3a.S3A"),
                new SparkSetting("aws.glue.endpoint", flociEndpoint),
                new SparkSetting("hive.metastore.glue.catalogid", job.accountId()),
                new SparkSetting("aws.region", job.region()));
    }

    // Floci's settings, then the job's spark-defaults, in the order a later one wins.
    private static List<SparkSetting> sparkSettings(Job job, String flociEndpoint) {
        List<SparkSetting> hadoop = new ArrayList<>(hadoopSettings(job, flociEndpoint));
        if (job.storage() != null) {
            hadoop.addAll(HDFS_SETTINGS);
        }
        List<SparkSetting> settings = new ArrayList<>();
        hadoop.forEach(setting -> settings.add(new SparkSetting("spark.hadoop." + setting.key(), setting.value())));
        if (job.storage() != null) {
            settings.add(FILE_SYSTEM_CHECKPOINTS);
        }
        job.sparkDefaults().forEach((key, value) -> settings.add(new SparkSetting(key, value)));
        return settings;
    }

    private record SparkSetting(String key, String value) {
    }

    /**
     * A job's archive in S3: its path and where that is, the file it is put at in the container, and the directory
     * of the working directory it is unpacked to.
     */
    record Archive(String path, S3Uri location, String file, String directory) {
    }

    /**
     * {@code spark-submit} parameters given as one string, as EMR Serverless's
     * {@code sparkSubmitParameters}, split as a POSIX shell would split them: on whitespace, except inside single
     * or double quotes. Outside quotes a backslash takes the next character as it is; inside double quotes
     * only a {@code "}, {@code \}, {@code $}, {@code `} or newline, and before any other character it is
     * kept. A backslash before a newline continues the line, and both go.
     */
    public static List<String> splitParameters(String parameters) {
        List<String> words = new ArrayList<>();
        if (parameters == null) {
            return words;
        }
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < parameters.length(); i++) {
            char c = parameters.charAt(i);
            if (quote == '\'') {
                if (c == '\'') {
                    quote = 0;
                } else {
                    word.append(c);
                }
            } else if (c == '\\' && i + 1 < parameters.length()
                    && (quote == 0 || DOUBLE_QUOTE_ESCAPES.indexOf(parameters.charAt(i + 1)) >= 0)) {
                char escaped = parameters.charAt(++i);
                if (escaped != '\n') {
                    word.append(escaped);
                    inWord = true;
                }
            } else if (quote == '"') {
                if (c == '"') {
                    quote = 0;
                } else {
                    word.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
            } else {
                word.append(c);
                inWord = true;
            }
        }
        if (inWord) {
            words.add(word.toString());
        }
        return words;
    }

    private static void conf(List<String> arguments, String key, String value) {
        arguments.add("--conf");
        arguments.add(key + "=" + value);
    }

    /**
     * How the running driver ends: with its exit code, by a stop, or at the run's timeout, which
     * {@code progress} hears before the driver is stopped. A stop, and a run {@code progress} reports
     * cancelled or gone, are read at every look, so they end the run whether or not the driver would ever
     * exit by itself, as a STREAMING run's does not, and run() then removes the driver: one a run started
     * while a reset's teardown was passing is stopped once the reset has removed its run. Each look at the
     * container is one short control-plane call, so a run holds no Docker connection while its driver runs.
     */
    private Outcome awaitEnd(String containerId, Job job, ContainerLaunches.Launch launch, Progress progress)
            throws InterruptedException {
        Instant deadline = job.timeout().isZero() ? null : clock.instant().plus(job.timeout());
        while (!launch.stopRequested() && !progress.cancelled()) {
            InspectContainerResponse.ContainerState state =
                    lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec().getState();
            if (!Boolean.TRUE.equals(state.getRunning())) {
                if (launch.stopRequested()) {
                    return Outcome.halted();
                }
                Long exitCode = state.getExitCodeLong();
                return Outcome.exitedWith(exitCode != null ? exitCode.intValue() : -1);
            }
            if (deadline != null && !clock.instant().isBefore(deadline)) {
                LOG.infov("Spark run {0} of {1} passed its execution timeout of {2}", job.runId(), job.service(),
                        job.timeout());
                progress.timedOut();
                return Outcome.outOfTime();
            }
            Thread.sleep(pollInterval.toMillis());
        }
        return Outcome.halted();
    }

    /**
     * Writes the ended driver's standard output and error, each gzipped, to {@code stdout.gz} and
     * {@code stderr.gz} in the job's log folder, while its container is still the run's. A driver a cancel or
     * its timeout ended is still running, so it is stopped first, and then changes nothing more and leaves
     * all it wrote; run() removes it after. One Floci's teardown took leaves nothing. Logs that cannot be
     * written, to a bucket that does not exist say, leave the run as it ended.
     */
    private void writeLogs(Job job, ContainerLaunches.Launch launch) {
        String containerId = launch.container();
        if (job.logFolder() == null || containerId == null) {
            return;
        }
        // Closed however the read ends, which ends Docker's stream and the compressors.
        try (DriverOutput output = new DriverOutput()) {
            lifecycleManager.stop(containerId, STOP_TIMEOUT_SECONDS);
            lifecycleManager.getDockerClient().logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(false)
                    .withTimestamps(false)
                    .exec(output);
            if (!output.awaitCompletion(LOG_READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("reading the driver's output took longer than " + LOG_READ_TIMEOUT);
            }
            putLogs(s3Service, job.accountId(), job.region(), job.logFolder(), output.gzipped(StreamType.STDOUT),
                    output.gzipped(StreamType.STDERR));
        } catch (InterruptedException e) {
            // Floci is shutting down: the run ends as its driver did, without its logs.
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            LOG.warnv("Could not write the logs of Spark run {0} of {1} to {2}: {3}", job.runId(), job.service(),
                    job.logFolder(), e.getMessage());
        }
    }

    /**
     * An ended driver's output as Docker gives it, each stream gzipped as it arrives: what is not
     * standard error, as a container with a terminal gives, is standard output. Closing it, as Docker does
     * once it has sent everything, or the reader does whichever way the read ends, ends Docker's stream and
     * the compressors, and a frame that still comes after is dropped.
     */
    private static final class DriverOutput extends ResultCallback.Adapter<Frame> {
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private final GZIPOutputStream stdoutGzip;
        private final GZIPOutputStream stderrGzip;
        // Guarded by this.
        private boolean compressorsClosed;

        DriverOutput() throws IOException {
            stdoutGzip = new GZIPOutputStream(stdout);
            stderrGzip = new GZIPOutputStream(stderr);
        }

        @Override
        public synchronized void onNext(Frame frame) {
            if (compressorsClosed) {
                return;
            }
            try {
                (frame.getStreamType() == StreamType.STDERR ? stderrGzip : stdoutGzip).write(frame.getPayload());
            } catch (IOException e) {
                onError(e);
            }
        }

        // A stream's whole gzipped output, once Docker has sent everything.
        synchronized byte[] gzipped(StreamType stream) throws IOException {
            closeCompressors();
            return (stream == StreamType.STDERR ? stderr : stdout).toByteArray();
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                synchronized (this) {
                    closeCompressors();
                }
            }
        }

        // Guarded by this. Closing a compressor writes its trailer and frees its deflater, once only.
        private void closeCompressors() throws IOException {
            compressorsClosed = true;
            try {
                stdoutGzip.close();
            } finally {
                stderrGzip.close();
            }
        }
    }
}
