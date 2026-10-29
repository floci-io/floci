package io.github.hectorvent.floci.services.emrserverless;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.S3Uri;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Runs a Spark application's {@code spark-submit} in a container of an EMR Serverless image, for an EMR
 * Serverless job run or an EMR step. The image's own entrypoint hands over to a script EMR Serverless
 * supplies at run time, which the public image lacks, so the runner starts {@code spark-submit} itself,
 * in local mode: the container holds the driver and its executors. The container reaches Floci as the
 * other containers Floci launches do, with the run's execution role as its credentials, and S3A, which
 * takes {@code s3://} on every release, is pointed at Floci's S3. The driver's output goes to Floci's log and,
 * while it runs, to the run's CloudWatch log streams, when it has some; once it ends, its standard output
 * and error go, gzipped, to the run's log folder in Floci's S3, when it has one.
 */
@ApplicationScoped
public class EmrServerlessSparkRunner implements SparkJobRunner, ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(EmrServerlessSparkRunner.class);
    private static final String SPARK_SUBMIT = "/usr/lib/spark/bin/spark-submit";
    private static final String SPARK_DEFAULTS_VARIABLE = "FLOCI_SPARK_DEFAULTS";
    // A command runs after this sets up a Spark configuration of its own, so a spark-submit it starts runs
    // locally on Floci's S3: the image's spark-defaults.conf names EMR Serverless's resource manager as its
    // master, which a system property cannot override, so its master and that manager's lines give way to
    // local mode, Floci's settings and the job's spark-defaults, passed in SPARK_DEFAULTS_VARIABLE.
    private static final String COMMAND_WRAPPER = String.join("\n",
            "conf=/tmp/floci-spark-conf",
            "mkdir -p \"$conf\"",
            "cp -r /usr/lib/spark/conf/. \"$conf\" 2>/dev/null",
            "touch \"$conf/spark-defaults.conf\"",
            "sed -i -e '/^spark\\.master[[:space:]]/d' -e '/^spark\\.submit\\.deployMode[[:space:]]/d'"
                    + " -e '/^spark\\.submit\\.customResourceManager/d' \"$conf/spark-defaults.conf\"",
            "printf '%s\\n' \"$" + SPARK_DEFAULTS_VARIABLE + "\" >> \"$conf/spark-defaults.conf\"",
            "export SPARK_CONF_DIR=\"$conf\"",
            "exec \"$@\"");
    private static final String DEFAULT_IMAGE =
            "public.ecr.aws/emr-serverless/spark/%s:latest"; // partition-literal: ECR Public's registry host, where AWS publishes the base images; image-template overrides it
    // The AWS runtime for Apache Spark (emr-spark-8.0.0 on) publishes its images under spark4/ instead.
    private static final String SPARK_RUNTIME_DEFAULT_IMAGE =
            "public.ecr.aws/emr-serverless/spark4/%s:latest"; // partition-literal: ECR Public's registry host, where AWS publishes the base images; image-template overrides it
    // "The default behavior for shutdown is to have no grace period": immediate termination.
    private static final int STOP_TIMEOUT_SECONDS = 0;
    // The characters a backslash escapes inside double quotes; before any other it is kept.
    private static final String DOUBLE_QUOTE_ESCAPES = "\"\\$`\n";
    // How often a running driver is looked at, as Batch's runner looks at its jobs.
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    // A log folder may be given in a Hadoop scheme, as an EMR cluster's LogUri often is (s3n://).
    private static final Pattern HADOOP_S3_SCHEME = Pattern.compile("^s3[an]://");
    private static final String GZIP = "application/x-gzip";
    // How long reading an ended driver's output may take; Docker's log rotation bounds its size.
    private static final Duration LOG_READ_TIMEOUT = Duration.ofSeconds(60);

    // The runs inside run(); a stop for a run not here comes after its driver has gone, and is dropped.
    private final ConcurrentHashMap<String, Launch> launches = new ConcurrentHashMap<>();

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final LaunchedContainerAwsEnv awsEnv;
    private final LambdaExecutionRoleCredentials roleCredentials;
    private final S3Service s3Service;
    private final EmulatorConfig config;
    private final Clock clock;
    private final Duration pollInterval;

    @Inject
    public EmrServerlessSparkRunner(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                    ContainerLogStreamer logStreamer, LaunchedContainerAwsEnv awsEnv,
                                    LambdaExecutionRoleCredentials roleCredentials, S3Service s3Service,
                                    EmulatorConfig config, Clock clock) {
        this(containerBuilder, lifecycleManager, logStreamer, awsEnv, roleCredentials, s3Service, config, clock,
                POLL_INTERVAL);
    }

    EmrServerlessSparkRunner(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                             ContainerLogStreamer logStreamer, LaunchedContainerAwsEnv awsEnv,
                             LambdaExecutionRoleCredentials roleCredentials, S3Service s3Service,
                             EmulatorConfig config, Clock clock, Duration pollInterval) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.awsEnv = awsEnv;
        this.roleCredentials = roleCredentials;
        this.s3Service = s3Service;
        this.config = config;
        this.clock = clock;
        this.pollInterval = pollInterval;
    }

    @Override
    public Outcome run(Job job, Progress progress) {
        Launch launch = new Launch();
        launches.put(job.runId(), launch);
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
            ContainerBuilder.Builder container = containerBuilder.newContainer(
                            image(job.imageTemplate(), job.releaseLabel()))
                    .withName(ContainerStorageHelper.dockerName(config, job.service() + "-" + job.runId()))
                    .withEnv(awsEnv.sdkBaselineEnv(job.region(), Optional.empty(), credentials, job.accountId()));
            if (job.command() == null) {
                container.withEntrypoint(List.of(SPARK_SUBMIT)).withCmd(sparkSubmitArguments(job, endpoint));
            } else {
                // bash -c's script gets "floci" as its $0, and the command as its arguments, "$@".
                container.withEntrypoint(List.of("/bin/bash", "-c", COMMAND_WRAPPER, "floci"))
                        .withCmd(job.command())
                        .withEnv(SPARK_DEFAULTS_VARIABLE, sparkDefaultsFile(job, endpoint));
            }
            ContainerSpec spec = container
                    .withDockerNetwork(Optional.ofNullable(job.dockerNetwork()))
                    .withHostDockerInternalOnLinux()
                    .withEmbeddedDns()
                    .withLogRotation()
                    .withLabels(labels)
                    .build();
            String containerId = lifecycleManager.createAndStart(spec).containerId();
            launch.container.set(containerId);
            // A stop, a reset or a shutdown while the image was pulled is read now the driver has a container.
            if (launch.stopRequested || progress.cancelled()) {
                return Outcome.halted();
            }
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
            if (launch.stopRequested) {
                // A reset removed the driver while it was being watched.
                return Outcome.halted();
            }
            LOG.warnv(e, "Spark run {0} of {1} could not run", job.runId(), job.service());
            return Outcome.couldNotStart(e.getMessage());
        } finally {
            launches.remove(job.runId(), launch);
            String unclaimed = launch.container.getAndSet(null);
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
        Launch launch = launches.get(runId);
        if (launch != null) {
            // run() reads it once the driver's container is up and at each look at the driver after, and
            // removes the driver itself: a stop makes no Docker call, so no Docker error can lose it.
            launch.stopRequested = true;
        }
    }

    /**
     * Stops every run inside {@link #run}, so a reset or a shutdown orphans none: a running driver is
     * stopped and removed now, and one whose image is still being pulled as soon as its container is up.
     */
    @Override
    public void stopManagedContainers() {
        for (Launch launch : launches.values()) {
            launch.stopRequested = true;
            String containerId = launch.container.getAndSet(null);
            if (containerId != null) {
                lifecycleManager.stopAndRemove(containerId, null, STOP_TIMEOUT_SECONDS);
            }
        }
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
     * The image a release's runs use: {@code imageTemplate}, with {@code %s} standing for the release
     * label, or, when it is null, the release's public EMR Serverless image.
     */
    static String image(String imageTemplate, String releaseLabel) {
        String template = imageTemplate != null ? imageTemplate
                : releaseLabel.startsWith("emr-spark-") ? SPARK_RUNTIME_DEFAULT_IMAGE : DEFAULT_IMAGE;
        return template.formatted(releaseLabel);
    }

    /**
     * {@code spark-submit}'s arguments: local mode, S3A on Floci's S3 for {@code s3://} and {@code s3a://}
     * paths, then the run's {@code spark-defaults} and its own options, which win over what comes before
     * them, then its application and that application's arguments. Before release 7.10.0
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

    // A backslash starts an escape and a line break ends a setting; in a key, whitespace, '=' and ':' end it,
    // and a leading '#' or '!' makes the line a comment.
    private static String propertiesEscaped(String text, boolean key) {
        StringBuilder escaped = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\f' -> escaped.append("\\f");
                case ' ', '=', ':', '#', '!' -> escaped.append(key ? "\\" : "").append(c);
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    // Floci's settings, then the job's spark-defaults, in the order a later one wins.
    private static List<SparkSetting> sparkSettings(Job job, String flociEndpoint) {
        List<SparkSetting> settings = new ArrayList<>(List.of(
                new SparkSetting("spark.hadoop.fs.s3a.endpoint", flociEndpoint),
                new SparkSetting("spark.hadoop.fs.s3a.endpoint.region", job.region()),
                new SparkSetting("spark.hadoop.fs.s3a.path.style.access", "true"),
                new SparkSetting("spark.hadoop.fs.s3a.committer.magic.track.commits.in.memory.enabled", "true"),
                new SparkSetting("spark.hadoop.fs.s3.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem"),
                new SparkSetting("spark.hadoop.fs.AbstractFileSystem.s3.impl", "org.apache.hadoop.fs.s3a.S3A"),
                new SparkSetting("spark.hadoop.aws.glue.endpoint", flociEndpoint),
                new SparkSetting("spark.hadoop.aws.region", job.region())));
        job.sparkDefaults().forEach((key, value) -> settings.add(new SparkSetting(key, value)));
        return settings;
    }

    private record SparkSetting(String key, String value) {
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
    private Outcome awaitEnd(String containerId, Job job, Launch launch, Progress progress)
            throws InterruptedException {
        Instant deadline = job.timeout().isZero() ? null : clock.instant().plus(job.timeout());
        while (!launch.stopRequested && !progress.cancelled()) {
            InspectContainerResponse.ContainerState state =
                    lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec().getState();
            if (!Boolean.TRUE.equals(state.getRunning())) {
                if (launch.stopRequested) {
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
    private void writeLogs(Job job, Launch launch) {
        String containerId = launch.container.get();
        if (job.logFolder() == null || containerId == null) {
            return;
        }
        // Closed however the read ends, which ends Docker's stream and the compressors.
        try (DriverOutput output = new DriverOutput()) {
            S3Uri folder = S3Uri.parse(HADOOP_S3_SCHEME.matcher(job.logFolder()).replaceFirst("s3://"));
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
            byte[] stdout = output.gzipped(StreamType.STDOUT);
            byte[] stderr = output.gzipped(StreamType.STDERR);
            RequestScopes.runAs(job.accountId(), job.region(), () -> {
                s3Service.putObject(folder.bucket(), folder.key() + "stdout.gz", stdout, GZIP, Map.of());
                s3Service.putObject(folder.bucket(), folder.key() + "stderr.gz", stderr, GZIP, Map.of());
            });
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

    /**
     * A run inside {@link #run}: whether it is to stop, and its driver's container once it has one, which
     * whoever stops the run first claims, run() itself or Floci's teardown.
     */
    private static final class Launch {
        final AtomicReference<String> container = new AtomicReference<>();
        volatile boolean stopRequested;
    }
}
