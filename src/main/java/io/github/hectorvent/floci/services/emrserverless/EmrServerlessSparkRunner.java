package io.github.hectorvent.floci.services.emrserverless;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.emrserverless.model.JobDriver;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaExecutionRoleCredentials;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a Spark job run's {@code spark-submit} in a container of the release's EMR Serverless image.
 * The image's own entrypoint hands over to a script EMR Serverless supplies at run time, which the
 * public image lacks, so the runner starts {@code spark-submit} itself, in local mode: the container
 * holds the driver and its executors. The container reaches Floci as the other containers Floci
 * launches do, with the run's execution role as its credentials, and S3A, which takes {@code s3://}
 * on every release, is pointed at Floci's S3.
 */
@ApplicationScoped
public class EmrServerlessSparkRunner implements SparkJobRunner, ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(EmrServerlessSparkRunner.class);
    private static final String SPARK_SUBMIT = "/usr/lib/spark/bin/spark-submit";
    private static final String DEFAULT_IMAGE =
            "public.ecr.aws/emr-serverless/spark/%s:latest"; // partition-literal: ECR Public's registry host, where AWS publishes the base images; image-template overrides it
    // The AWS runtime for Apache Spark (emr-spark-8.0.0 on) publishes its images under spark4/ instead.
    private static final String SPARK_RUNTIME_DEFAULT_IMAGE =
            "public.ecr.aws/emr-serverless/spark4/%s:latest"; // partition-literal: ECR Public's registry host, where AWS publishes the base images; image-template overrides it
    // "The default behavior for shutdown is to have no grace period": immediate termination.
    private static final int STOP_TIMEOUT_SECONDS = 0;
    private static final String SERVICE = "emr-serverless";
    // The characters a backslash escapes inside double quotes; before any other it is kept.
    private static final String DOUBLE_QUOTE_ESCAPES = "\"\\$`\n";
    // How often a running driver is looked at, as Batch's runner looks at its jobs.
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    // The runs inside run(); a stop for a run not here comes after its driver has gone, and is dropped.
    private final ConcurrentHashMap<String, Launch> launches = new ConcurrentHashMap<>();

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final LaunchedContainerAwsEnv awsEnv;
    private final LambdaExecutionRoleCredentials roleCredentials;
    private final EmulatorConfig config;
    private final Clock clock;
    private final Duration pollInterval;

    @Inject
    public EmrServerlessSparkRunner(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                    ContainerLogStreamer logStreamer, LaunchedContainerAwsEnv awsEnv,
                                    LambdaExecutionRoleCredentials roleCredentials, EmulatorConfig config,
                                    Clock clock) {
        this(containerBuilder, lifecycleManager, logStreamer, awsEnv, roleCredentials, config, clock, POLL_INTERVAL);
    }

    EmrServerlessSparkRunner(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                             ContainerLogStreamer logStreamer, LaunchedContainerAwsEnv awsEnv,
                             LambdaExecutionRoleCredentials roleCredentials, EmulatorConfig config, Clock clock,
                             Duration pollInterval) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.awsEnv = awsEnv;
        this.roleCredentials = roleCredentials;
        this.config = config;
        this.clock = clock;
        this.pollInterval = pollInterval;
    }

    @Override
    public Outcome run(Job job, Progress progress) {
        Launch launch = new Launch();
        launches.put(job.jobRunId(), launch);
        Optional<SessionCreds> credentials = Optional.empty();
        Closeable logHandle = null;
        try {
            // A cancel before the run was in launches had nothing to stop, so it is read here.
            if (progress.cancelled()) {
                return Outcome.halted();
            }
            credentials = roleCredentials.forRole(job.executionRole(), job.accountId(), job.jobRunId());
            Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                    SERVICE, job.jobRunId(), job.accountId(), job.region()));
            // The deployment that started the driver, which removeLeftoverDrivers() goes by.
            labels.put(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config));
            String endpoint = awsEnv.flociEndpoint();
            ContainerSpec spec = containerBuilder.newContainer(image(job.releaseLabel()))
                    .withName(ContainerStorageHelper.dockerName(config, SERVICE + "-" + job.jobRunId()))
                    .withEntrypoint(List.of(SPARK_SUBMIT))
                    .withCmd(sparkSubmitArguments(job, endpoint))
                    .withEnv(awsEnv.sdkBaselineEnv(job.region(), Optional.empty(), credentials, job.accountId()))
                    .withDockerNetwork(config.services().emrserverless().dockerNetwork())
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
            logHandle = logStreamer.attachConsoleOnly(containerId, SERVICE + ":" + job.jobRunId());
            progress.started();
            return awaitEnd(containerId, job, launch, progress);
        } catch (InterruptedException e) {
            // Floci is shutting down: its teardown stops the driver.
            Thread.currentThread().interrupt();
            return Outcome.halted();
        } catch (RuntimeException e) {
            if (launch.stopRequested) {
                // A reset removed the driver while it was being watched.
                return Outcome.halted();
            }
            LOG.warnv(e, "EMR Serverless job run {0} could not run", job.jobRunId());
            return Outcome.couldNotStart(e.getMessage());
        } finally {
            launches.remove(job.jobRunId(), launch);
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
    public void stop(String jobRunId) {
        Launch launch = launches.get(jobRunId);
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
     * Removes the drivers an earlier Floci process left running, which no thread follows any more: their
     * runs are {@code FAILED} once read, so nothing would ever stop them. The job run service calls this
     * before it hands a run to {@link #run}, so every driver of this deployment it finds is a leftover.
     */
    public void removeLeftoverDrivers() {
        String owner = ContainerStorageHelper.ownerIdentity(config);
        ContainerStorageHelper.LabelAliases aliases = ContainerStorageHelper.CONTAINER_LABEL_ALIASES;
        try {
            List<Container> containers = aliases.listByLabels(
                    Map.of(ContainerStorageHelper.SERVICE_LABEL, SERVICE, ContainerStorageHelper.OWNER_LABEL, owner),
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
                    LOG.infov("Removed EMR Serverless driver {0}, left running by an earlier Floci process",
                            container.getId());
                }
            }
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove the EMR Serverless drivers an earlier Floci process left: {0}",
                    e.getMessage());
        }
    }

    String image(String releaseLabel) {
        return config.services().emrserverless().imageTemplate()
                .orElse(releaseLabel.startsWith("emr-spark-") ? SPARK_RUNTIME_DEFAULT_IMAGE : DEFAULT_IMAGE)
                .formatted(releaseLabel);
    }

    /**
     * {@code spark-submit}'s arguments: local mode, S3A on Floci's S3 for {@code s3://} and {@code s3a://}
     * paths, then the run's {@code spark-defaults} and its own {@code sparkSubmitParameters}, which win
     * over what comes before them, then the entry point and its arguments. Before release 7.10.0
     * {@code s3://} is EMRFS, which ignores the endpoint setting, so it is switched to S3A. S3A writes
     * are committed through commit data the writer keeps in memory, without which EMR's committer finds
     * none and a write is silently lost.
     */
    static List<String> sparkSubmitArguments(Job job, String flociEndpoint) {
        List<String> arguments = new ArrayList<>(List.of("--master", "local[*]"));
        conf(arguments, "spark.hadoop.fs.s3a.endpoint", flociEndpoint);
        conf(arguments, "spark.hadoop.fs.s3a.endpoint.region", job.region());
        conf(arguments, "spark.hadoop.fs.s3a.path.style.access", "true");
        conf(arguments, "spark.hadoop.fs.s3a.committer.magic.track.commits.in.memory.enabled", "true");
        conf(arguments, "spark.hadoop.fs.s3.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem");
        conf(arguments, "spark.hadoop.fs.AbstractFileSystem.s3.impl", "org.apache.hadoop.fs.s3a.S3A");
        conf(arguments, "spark.hadoop.aws.glue.endpoint", flociEndpoint);
        conf(arguments, "spark.hadoop.aws.region", job.region());
        job.sparkDefaults().forEach((key, value) -> conf(arguments, key, value));
        JobDriver.SparkSubmit sparkSubmit = job.sparkSubmit();
        arguments.addAll(splitParameters(sparkSubmit.getSparkSubmitParameters()));
        arguments.add(sparkSubmit.getEntryPoint());
        if (sparkSubmit.getEntryPointArguments() != null) {
            arguments.addAll(sparkSubmit.getEntryPointArguments());
        }
        return arguments;
    }

    /**
     * {@code sparkSubmitParameters} as a POSIX shell would split them: on whitespace, except inside single
     * or double quotes. Outside quotes a backslash takes the next character as it is; inside double quotes
     * only a {@code "}, {@code \}, {@code $}, {@code `} or newline, and before any other character it is
     * kept. A backslash before a newline continues the line, and both go.
     */
    static List<String> splitParameters(String parameters) {
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
     * {@code progress} hears before the driver is stopped. A stop is read at every look, so it ends the
     * run whether or not the driver would ever exit by itself, as a STREAMING run's does not, and run()
     * then removes the driver. Each look at the container is one short control-plane call, so a run
     * holds no Docker connection while its driver runs.
     */
    private Outcome awaitEnd(String containerId, Job job, Launch launch, Progress progress)
            throws InterruptedException {
        Instant deadline = job.timeout().isZero() ? null : clock.instant().plus(job.timeout());
        while (!launch.stopRequested) {
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
                LOG.infov("EMR Serverless job run {0} passed its execution timeout of {1}", job.jobRunId(),
                        job.timeout());
                progress.timedOut();
                return Outcome.outOfTime();
            }
            Thread.sleep(pollInterval.toMillis());
        }
        return Outcome.halted();
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
