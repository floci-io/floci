package io.floci.aws.services.glue;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.S3Uri;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLaunches;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.core.common.docker.RetryingTarCopier;
import io.floci.aws.services.iam.model.SessionCreds;
import io.floci.aws.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
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
import java.util.function.BooleanSupplier;

/**
 * Runs a Glue job's script with {@code spark-submit} in a container of AWS's Glue image, which holds the Glue ETL
 * library, so {@code awsglue.utils.getResolvedOptions} reads the script's arguments. The script's AWS calls reach
 * Floci with the job role's credentials.
 */
@ApplicationScoped
public class DockerGlueScriptRunner implements GlueScriptRunner, ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(DockerGlueScriptRunner.class);

    static final String SERVICE = "glue";
    private static final String WORKSPACE = "/home/hadoop/workspace/";
    private static final String DEFAULT_SCRIPT_NAME = "script.py";
    private static final String SPARK_SUBMIT = "spark-submit";
    private static final int STOP_TIMEOUT_SECONDS = 0;
    private static final int MAX_FAILED_LOOKS = 3;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final LaunchedContainerAwsEnv awsEnv;
    private final LambdaExecutionRoleCredentials roleCredentials;
    private final S3Service s3Service;
    private final EmulatorConfig config;
    private final Clock clock;
    private final Duration pollInterval;
    private final ContainerLaunches launches = new ContainerLaunches();

    @Inject
    public DockerGlueScriptRunner(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                  ContainerLogStreamer logStreamer, LaunchedContainerAwsEnv awsEnv,
                                  LambdaExecutionRoleCredentials roleCredentials, S3Service s3Service,
                                  EmulatorConfig config, Clock clock) {
        this(containerBuilder, lifecycleManager, logStreamer, awsEnv, roleCredentials, s3Service, config, clock,
                POLL_INTERVAL);
    }

    DockerGlueScriptRunner(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
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
    public Outcome run(Run run, Progress progress) {
        // The timeout counts from here, as Glue's does from the run's start, so the image pull is part of it.
        Instant deadline = run.timeout().isZero() ? null : clock.instant().plus(run.timeout());
        ContainerLaunches.Launch launch = launches.begin(run.runId());
        Optional<SessionCreds> credentials = Optional.empty();
        Closeable logHandle = null;
        try {
            // A cancel before the run was in launches had nothing to stop, so it is read here.
            if (progress.cancelled()) {
                return Outcome.halted();
            }
            S3Uri script = S3Uri.parse(run.scriptLocation());
            requireReadable(run, script);
            credentials = roleCredentials.forRole(run.role(), run.accountId(), run.runId());
            String network = containerBuilder.resolveDockerNetwork(Optional.ofNullable(run.dockerNetwork()))
                    .orElse(null);
            String scriptPath = WORKSPACE + scriptName(script);
            ContainerSpec spec = containerBuilder.newContainer(launchImage(run))
                    .withName(ContainerStorageHelper.dockerName(config, SERVICE + "-" + run.runId()))
                    .withEnv(awsEnv.sdkBaselineEnv(run.region(), Optional.empty(), credentials, run.accountId()))
                    .withEntrypoint(List.of(SPARK_SUBMIT))
                    .withCmd(commandLine(scriptPath, awsEnv.flociEndpoint(), run.arguments()))
                    .withDockerNetwork(Optional.ofNullable(network))
                    .withHostDockerInternalOnLinux()
                    .withEmbeddedDns()
                    .withLogRotation()
                    .withLabels(labels(run))
                    .withJavaTrustStore()
                    .build();
            String containerId = lifecycleManager.create(spec);
            launch.started(containerId);
            // A stop, a reset or a shutdown while the image was pulled is read now, before the script starts.
            BooleanSupplier stopped = () -> launch.stopRequested() || progress.cancelled();
            putScript(run, script, scriptPath, containerId, stopped);
            if (stopped.getAsBoolean()) {
                return Outcome.halted();
            }
            lifecycleManager.startCreated(containerId, spec);
            logHandle = logStreamer.attachConsoleOnly(containerId, SERVICE + ":" + run.runId());
            return awaitEnd(containerId, run, launch, progress, deadline);
        } catch (InterruptedException e) {
            // Floci is shutting down: its teardown stops the container.
            Thread.currentThread().interrupt();
            return Outcome.halted();
        } catch (RuntimeException e) {
            if (launch.stopRequested()) {
                // A reset removed the container while it was being watched.
                return Outcome.halted();
            }
            LOG.warnv(e, "Glue job run {0} of {1} could not run", run.runId(), run.jobName());
            return Outcome.couldNotStart(e.getMessage());
        } finally {
            String unclaimed = launches.end(run.runId(), launch);
            if (unclaimed != null) {
                lifecycleManager.stopAndRemove(unclaimed, logHandle, STOP_TIMEOUT_SECONDS);
            } else if (logHandle != null) {
                // Floci's teardown claimed the container, but the log stream is still this run's to close.
                lifecycleManager.closeLogStreamAfterContainerStop(logHandle);
            }
            credentials.ifPresent(session -> roleCredentials.unregister(run.accountId(), session.accessKeyId()));
        }
    }

    @Override
    public void stop(String runId) {
        ContainerLaunches.Launch launch = launches.get(runId);
        if (launch != null) {
            // run() reads it once the container is up and at each look at it after, and removes it itself.
            launch.requestStop();
        }
    }

    /** Stops every run inside {@link #run}, so a reset or a shutdown orphans none. */
    @Override
    public void stopManagedContainers() {
        launches.stopAll(lifecycleManager, STOP_TIMEOUT_SECONDS);
    }

    @Override
    public void removeLeftoverContainers() {
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
                    LOG.infov("Removed Glue job container {0}, left running by an earlier Floci process",
                            container.getId());
                }
            }
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove the Glue job containers an earlier Floci process left: {0}", e.getMessage());
        }
    }

    /**
     * What follows {@code spark-submit}: Floci's settings for the script's own S3 reads, the script, and its
     * arguments. {@code spark.hadoop.fs.s3a.*} points Spark's S3 access at Floci, as the AWS environment does for
     * the AWS SDKs.
     */
    static List<String> commandLine(String scriptPath, String flociEndpoint, List<String> arguments) {
        List<String> command = new ArrayList<>();
        // The s3 scheme a Glue script reads is served by S3A too, which is what honours the endpoint.
        for (String setting : List.of(
                "spark.hadoop.fs.s3a.endpoint=" + flociEndpoint,
                "spark.hadoop.fs.s3a.path.style.access=true",
                "spark.hadoop.fs.s3a.connection.ssl.enabled=" + flociEndpoint.startsWith("https"),
                "spark.hadoop.fs.s3.impl=org.apache.hadoop.fs.s3a.S3AFileSystem",
                "spark.hadoop.fs.AbstractFileSystem.s3.impl=org.apache.hadoop.fs.s3a.S3A")) {
            command.add("--conf");
            command.add(setting);
        }
        command.add(scriptPath);
        command.addAll(arguments);
        return command;
    }

    /** The script's file name, which {@code spark-submit} needs to end in {@code .py} to run it as Python. */
    static String scriptName(S3Uri script) {
        String key = script.key();
        String name = key.substring(key.lastIndexOf('/') + 1);
        return name.endsWith(".py") ? name : DEFAULT_SCRIPT_NAME;
    }

    private Map<String, String> labels(Run run) {
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                SERVICE, run.runId(), run.accountId(), run.region()));
        // The deployment that started the container, which removeLeftoverContainers() goes by.
        labels.put(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config));
        return labels;
    }

    private String launchImage(Run run) {
        String image = containerBuilder.resolveImage(run.image());
        return lifecycleManager.resolveImageForLaunch(image, ImagePullBehavior.DEFAULT, null).imageId();
    }

    private void requireReadable(Run run, S3Uri script) {
        try {
            RequestScopes.runAs(run.accountId(), run.region(),
                    () -> s3Service.headObject(script.bucket(), script.key()));
        } catch (AwsException e) {
            throw new IllegalArgumentException(
                    "Could not read the script " + run.scriptLocation() + ": " + e.getMessage(), e);
        }
    }

    /** Puts the script in the created container, read from Floci's S3 as the run's account. */
    private void putScript(Run run, S3Uri script, String scriptPath, String containerId, BooleanSupplier stopped) {
        if (stopped.getAsBoolean()) {
            return;
        }
        try {
            // Extracted at the root, the entry is the file's path without its leading slash.
            RetryingTarCopier.copyStreamed(lifecycleManager.getDockerClient(), containerId, "/", scriptPath, out -> {
                try (S3Service.ObjectRead read = RequestScopes.callAs(run.accountId(), run.region(),
                        () -> s3Service.openObject(script.bucket(), script.key(), null));
                     TarArchiveOutputStream tar = RetryingTarCopier.newTarStream(out)) {
                    TarArchiveEntry entry = new TarArchiveEntry(scriptPath.substring(1));
                    entry.setSize(read.object().getSize());
                    entry.setMode(0644);
                    tar.putArchiveEntry(entry);
                    read.body().transferTo(tar);
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

    private Outcome awaitEnd(String containerId, Run run, ContainerLaunches.Launch launch, Progress progress,
                             Instant deadline) throws InterruptedException {
        int failedLooks = 0;
        while (!launch.stopRequested() && !progress.cancelled()) {
            InspectContainerResponse.ContainerState state;
            try {
                state = lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec().getState();
                failedLooks = 0;
            } catch (RuntimeException e) {
                // One refused look is not the end of a healthy script.
                if (++failedLooks >= MAX_FAILED_LOOKS) {
                    throw e;
                }
                LOG.warnv("Could not look at the container of Glue job run {0}: {1}", run.runId(), e.getMessage());
                Thread.sleep(pollInterval.toMillis());
                continue;
            }
            if (!Boolean.TRUE.equals(state.getRunning())) {
                if (launch.stopRequested()) {
                    return Outcome.halted();
                }
                Long exitCode = state.getExitCodeLong();
                return Outcome.exitedWith(exitCode != null ? exitCode.intValue() : -1);
            }
            if (deadline != null && !clock.instant().isBefore(deadline)) {
                LOG.infov("Glue job run {0} of {1} passed its timeout of {2}", run.runId(), run.jobName(),
                        run.timeout());
                return Outcome.outOfTime();
            }
            Thread.sleep(pollInterval.toMillis());
        }
        return Outcome.halted();
    }
}
