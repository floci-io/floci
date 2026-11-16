package io.floci.aws.services.glue;

import com.github.dockerjava.api.command.InspectContainerResponse;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.S3Uri;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLaunches;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReconciler;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.core.common.docker.RetryingTarCopier;
import io.floci.aws.services.iam.AssumeRolePolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.iam.model.SessionCreds;
import io.floci.aws.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    private static final int SCRIPT_BUFFER_BYTES = 64 * 1024;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final LaunchedContainerAwsEnv awsEnv;
    private final LambdaExecutionRoleCredentials roleCredentials;
    private final IamService iamService;
    private final AssumeRolePolicyEvaluator trustEvaluator;
    private final S3Service s3Service;
    private final EmulatorConfig config;
    private final Clock clock;
    private final Duration pollInterval;
    private final ContainerLaunches launches = new ContainerLaunches();
    private final ContainerReconciler reconciler;

    @Inject
    public DockerGlueScriptRunner(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                  ContainerLogStreamer logStreamer, LaunchedContainerAwsEnv awsEnv,
                                  LambdaExecutionRoleCredentials roleCredentials, IamService iamService,
                                  AssumeRolePolicyEvaluator trustEvaluator, S3Service s3Service,
                                  EmulatorConfig config, Clock clock) {
        this(containerBuilder, lifecycleManager, logStreamer, awsEnv, roleCredentials, iamService, trustEvaluator,
                s3Service, config, clock, POLL_INTERVAL);
    }

    DockerGlueScriptRunner(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                           ContainerLogStreamer logStreamer, LaunchedContainerAwsEnv awsEnv,
                           LambdaExecutionRoleCredentials roleCredentials, S3Service s3Service,
                           EmulatorConfig config, Clock clock, Duration pollInterval) {
        this(containerBuilder, lifecycleManager, logStreamer, awsEnv, roleCredentials, null, null, s3Service,
                config, clock, pollInterval);
    }

    DockerGlueScriptRunner(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                           ContainerLogStreamer logStreamer, LaunchedContainerAwsEnv awsEnv,
                           LambdaExecutionRoleCredentials roleCredentials, IamService iamService,
                           AssumeRolePolicyEvaluator trustEvaluator, S3Service s3Service,
                           EmulatorConfig config, Clock clock, Duration pollInterval) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.awsEnv = awsEnv;
        this.roleCredentials = roleCredentials;
        this.iamService = iamService;
        this.trustEvaluator = trustEvaluator;
        this.s3Service = s3Service;
        this.config = config;
        this.clock = clock;
        this.pollInterval = pollInterval;
        this.reconciler = new ContainerReconciler(lifecycleManager, LOG);
    }

    @Override
    public boolean isAvailable() {
        try {
            lifecycleManager.getDockerClient().pingCmd().exec();
            return true;
        } catch (RuntimeException e) {
            LOG.debugv(e, "Docker daemon is not reachable for Glue script execution");
            return false;
        }
    }

    @Override
    public Outcome run(Run run, Progress progress) {
        Instant startedOn = run.startedOn() != null ? run.startedOn() : clock.instant();
        Instant deadline = run.timeout().isZero() ? null : startedOn.plus(run.timeout());
        ContainerLaunches.Launch launch = launches.begin(run.runId());
        Optional<SessionCreds> credentials = Optional.empty();
        Closeable logHandle = null;
        try {
            // A cancel before the run was in launches had nothing to stop, so it is read here.
            if (progress.cancelled()) {
                return Outcome.halted();
            }
            if (timedOut(deadline)) {
                return timedOutOutcome(run);
            }
            S3Uri script = S3Uri.parse(run.scriptLocation());
            requireReadable(run, script);
            credentials = resolveCredentials(run);
            String network = containerBuilder.resolveDockerNetwork(Optional.ofNullable(run.dockerNetwork()))
                    .orElse(null);
            String scriptPath = WORKSPACE + scriptName(script);
            String resolvedImage = launchImage(run);
            if (progress.cancelled()) {
                return Outcome.halted();
            }
            if (timedOut(deadline)) {
                return timedOutOutcome(run);
            }
            ContainerSpec spec = containerBuilder.newContainer(resolvedImage)
                    .withName(ContainerStorageHelper.dockerName(config, SERVICE + "-" + run.runId()))
                    .withEnv(awsEnv.sdkBaselineEnv(run.region(), Optional.empty(), credentials, run.accountId()))
                    .withEntrypoint(List.of(SPARK_SUBMIT))
                    .withCmd(commandLine(scriptPath, awsEnv.flociEndpoint(), run.region(), run.accountId(),
                            run.arguments()))
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
            if (stopped.getAsBoolean()) {
                return Outcome.halted();
            }
            if (timedOut(deadline)) {
                return timedOutOutcome(run);
            }
            putScript(run, script, scriptPath, containerId, stopped);
            if (stopped.getAsBoolean()) {
                return Outcome.halted();
            }
            if (timedOut(deadline)) {
                return timedOutOutcome(run);
            }
            lifecycleManager.startCreated(containerId, spec);
            logHandle = logStreamer.attachConsoleOnly(containerId, SERVICE + ":" + run.runId());
            return awaitEnd(containerId, run, launch, progress, deadline);
        } catch (InterruptedException e) {
            // Floci is shutting down: its teardown stops the container.
            Thread.currentThread().interrupt();
            return Outcome.halted();
        } catch (RuntimeException e) {
            if (launch.stopRequested() || progress.cancelled()) {
                // A reset removed the container while it was being watched, or the run was stopped.
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
        reconciler.reconcile(
                Map.of(ContainerStorageHelper.SERVICE_LABEL, SERVICE, ContainerStorageHelper.OWNER_LABEL, owner),
                container -> true);
    }

    /**
     * What follows {@code spark-submit}: Floci's settings for the script's own S3 and Glue Data Catalog access,
     * the script, and its arguments.
     */
    static List<String> commandLine(String scriptPath, String flociEndpoint, String region, String accountId,
                                    List<String> arguments) {
        List<String> command = new ArrayList<>();
        // The s3 scheme a Glue script reads is served by S3A too, and aws.glue.endpoint routes Glue Catalog calls.
        for (String setting : List.of(
                "spark.hadoop.fs.s3a.endpoint=" + flociEndpoint,
                "spark.hadoop.fs.s3a.endpoint.region=" + region,
                "spark.hadoop.fs.s3a.path.style.access=true",
                "spark.hadoop.fs.s3a.connection.ssl.enabled=" + flociEndpoint.startsWith("https"),
                "spark.hadoop.fs.s3a.committer.magic.track.commits.in.memory.enabled=true",
                "spark.hadoop.fs.s3.impl=org.apache.hadoop.fs.s3a.S3AFileSystem",
                "spark.hadoop.fs.AbstractFileSystem.s3.impl=org.apache.hadoop.fs.s3a.S3A",
                "spark.hadoop.aws.glue.endpoint=" + flociEndpoint,
                "spark.hadoop.hive.metastore.glue.catalogid=" + accountId,
                "spark.hadoop.aws.region=" + region)) {
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

    private Optional<SessionCreds> resolveCredentials(Run run) {
        String roleRef = run.role();
        boolean enforcement = isIamEnforcementEnabled();
        if (roleRef == null || roleRef.isBlank()) {
            if (enforcement) {
                throw new IllegalArgumentException("Glue job " + run.jobName() + " has no IAM role.");
            }
            return Optional.empty();
        }
        String roleArn = resolveRoleArn(run, roleRef, enforcement);
        if (roleArn == null) {
            return Optional.empty();
        }
        if (enforcement) {
            verifyRoleTrust(run, roleArn);
        }
        Optional<SessionCreds> session = roleCredentials.forRole(roleArn, run.accountId(), run.runId());
        if (session.isEmpty()) {
            if (enforcement) {
                throw new IllegalArgumentException("The role " + roleRef + " does not exist.");
            }
            LOG.debugv("Glue job run {0} uses fallback credentials; role {1} was not found in IAM",
                    run.runId(), roleRef);
        }
        return session;
    }

    private String resolveRoleArn(Run run, String roleRef, boolean enforcement) {
        if (AwsArnUtils.looksLikeArn(roleRef)) {
            AwsArnUtils.Arn parsed;
            try {
                parsed = AwsArnUtils.parse(roleRef);
            } catch (IllegalArgumentException e) {
                if (enforcement) {
                    throw new IllegalArgumentException("The role " + roleRef + " is not a valid ARN.", e);
                }
                return null;
            }
            if (!"iam".equals(parsed.service()) || !parsed.resource().startsWith("role/")) {
                if (enforcement) {
                    throw new IllegalArgumentException("The role " + roleRef + " is not an IAM role.");
                }
                return null;
            }
            if (!run.accountId().equals(parsed.accountId())) {
                throw new IllegalArgumentException("Cross-account job role " + roleRef + " is not allowed.");
            }
            if (enforcement && !AwsRegions.partitionFor(run.region()).equals(parsed.partition())) {
                throw new IllegalArgumentException("The role " + roleRef + " belongs to another partition.");
            }
            String roleName = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
            if (roleName.isBlank()) {
                if (enforcement) {
                    throw new IllegalArgumentException("The role " + roleRef + " has no role name.");
                }
                return null;
            }
            return roleRef;
        }
        int slash = roleRef.lastIndexOf('/');
        String roleName = slash >= 0 ? roleRef.substring(slash + 1) : roleRef;
        if (roleName.isBlank()) {
            if (enforcement) {
                throw new IllegalArgumentException("The role " + roleRef + " has no role name.");
            }
            return null;
        }
        String partition = AwsRegions.partitionFor(run.region());
        if (iamService != null) {
            Optional<IamRole> found = iamService.findRole(run.accountId(), roleName);
            if (slash >= 0) {
                String expectedArn = AwsArnUtils.Arn.global(partition, "iam", run.accountId(), "role/" + roleRef)
                        .toString();
                found = found.filter(role -> IamService.roleArnMatches(expectedArn, role));
            }
            if (found.isPresent()) {
                return found.get().getArn();
            }
        }
        return AwsArnUtils.Arn.global(partition, "iam", run.accountId(), "role/" + roleRef).toString();
    }

    private void verifyRoleTrust(Run run, String roleArn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(roleArn);
        String roleName = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
        IamRole role = iamService != null
                ? iamService.findRole(run.accountId(), roleName)
                        .filter(found -> IamService.roleArnMatches(roleArn, found))
                        .orElse(null)
                : null;
        if (role == null) {
            throw new IllegalArgumentException("The role " + roleArn + " does not exist.");
        }
        String jobArn = AwsArnUtils.Arn.of("glue", run.region(), run.accountId(), "job/" + run.jobName()).toString();
        String gluePrincipal = ServicePrincipals.of("glue");
        if (trustEvaluator != null && !trustEvaluator.allowsService(
                role.getAssumeRolePolicyDocument(), gluePrincipal, jobArn, run.accountId())) {
            throw new IllegalArgumentException("The role " + roleArn + " does not trust " + gluePrincipal + ".");
        }
    }

    private boolean isIamEnforcementEnabled() {
        return config.services() != null && config.services().iam() != null
                && config.services().iam().enforcementEnabled();
    }

    private boolean timedOut(Instant deadline) {
        return deadline != null && !clock.instant().isBefore(deadline);
    }

    private Outcome timedOutOutcome(Run run) {
        LOG.infov("Glue job run {0} of {1} passed its timeout of {2}", run.runId(), run.jobName(), run.timeout());
        return Outcome.outOfTime();
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

    private static void transferUntilStopped(InputStream in, OutputStream out, BooleanSupplier stopped)
            throws IOException {
        byte[] buffer = new byte[SCRIPT_BUFFER_BYTES];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            if (stopped.getAsBoolean()) {
                throw new IOException("the job run was stopped");
            }
            out.write(buffer, 0, read);
        }
    }

    private Outcome awaitEnd(String containerId, Run run, ContainerLaunches.Launch launch, Progress progress,
                             Instant deadline) throws InterruptedException {
        int failedLooks = 0;
        while (!launch.stopRequested() && !progress.cancelled()) {
            if (timedOut(deadline)) {
                return timedOutOutcome(run);
            }
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
            if (timedOut(deadline)) {
                return timedOutOutcome(run);
            }
            if (!Boolean.TRUE.equals(state.getRunning())) {
                if (launch.stopRequested() || progress.cancelled()) {
                    return Outcome.halted();
                }
                Long exitCode = state.getExitCodeLong();
                return Outcome.exitedWith(exitCode != null ? exitCode.intValue() : -1);
            }
            Thread.sleep(pollInterval.toMillis());
        }
        return Outcome.halted();
    }
}
