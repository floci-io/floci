package io.github.hectorvent.floci.services.batch;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.batch.model.BatchJob;
import io.github.hectorvent.floci.services.batch.model.BatchKeyValue;
import io.github.hectorvent.floci.services.batch.model.BatchResourceRequirement;
import io.github.hectorvent.floci.services.batch.model.BatchRunResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class BatchDockerRunner implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(BatchDockerRunner.class);
    private static final String LOG_GROUP = "/aws/batch/job";

    // Containers of jobs currently inside run(); drained on emulator shutdown so a
    // SIGTERM mid-job does not orphan the container.
    private final ConcurrentHashMap<String, String> inFlightContainers = new ConcurrentHashMap<>();

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final EmulatorConfig config;
    private final ContainerDetector containerDetector;

    @Inject
    public BatchDockerRunner(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             ContainerLogStreamer logStreamer,
                             EmulatorConfig config,
                             ContainerDetector containerDetector) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.config = config;
        this.containerDetector = containerDetector;
    }

    public BatchRunResult run(BatchJob job, int attemptNumber) {
        long startedAt = System.currentTimeMillis();
        String logStreamName = logStreamer.generateLogStreamName(
                job.getJobDefinitionName() + "/default/" + job.getJobId());
        String containerName = ContainerStorageHelper.dockerName(config, "floci-batch-" + job.getJobId() + "-" + attemptNumber);
        Closeable logHandle = null;
        String containerId = null;

        try {
            if (job.getContainerImage() == null || job.getContainerImage().isBlank()) {
                return failed(startedAt, logStreamName, "Job definition container image is missing");
            }

            ContainerBuilder.Builder builder = containerBuilder.newContainer(job.getContainerImage())
                    .withName(containerName)
                    .withEnv(buildEnvironment(job, attemptNumber))
                    .withDockerNetwork(config.services().batch().dockerNetwork())
                    .withHostDockerInternalOnLinux()
                    .withEmbeddedDns()
                    .withLogRotation();

            if (job.getResolvedCommand() != null && !job.getResolvedCommand().isEmpty()) {
                builder.withCmd(job.getResolvedCommand());
            }
            applyResourceRequirements(builder, job);

            ContainerSpec spec = builder.build();
            containerId = lifecycleManager.createAndStart(spec).containerId();
            inFlightContainers.put(job.getJobId(), containerId);
            logHandle = logStreamer.attach(containerId, LOG_GROUP, logStreamName, job.getRegion(),
                    "batch:" + job.getJobName() + ":" + job.getJobId());

            Integer exitCode = waitForExit(containerId, timeout(job));
            long stoppedAt = System.currentTimeMillis();
            releaseAndStop(job.getJobId(), containerId, logHandle);
            if (exitCode == null) {
                return new BatchRunResult(137, "Job timed out", logStreamName, startedAt, stoppedAt, true);
            }
            return new BatchRunResult(exitCode, exitCode == 0 ? null : "Container exited with code " + exitCode,
                    logStreamName, startedAt, stoppedAt, false);
        } catch (Exception e) {
            LOG.warnv("Batch Docker job {0} failed: {1}", job.getJobId(), e.getMessage());
            if (containerId != null) {
                releaseAndStop(job.getJobId(), containerId, logHandle);
            }
            return failed(startedAt, logStreamName, e.getMessage());
        }
    }

    // Whoever wins the map removal owns the stop — stopManagedContainers() may have
    // claimed the container first during shutdown, but the log stream is still ours.
    private void releaseAndStop(String jobId, String containerId, Closeable logHandle) {
        if (inFlightContainers.remove(jobId, containerId)) {
            lifecycleManager.stopAndRemove(containerId, logHandle);
        } else if (logHandle != null) {
            try {
                logHandle.close();
            } catch (Exception e) {
                LOG.debugv("Error closing log stream for job {0}: {1}", jobId, e.getMessage());
            }
        }
    }

    /**
     * Stops the containers of jobs still inside {@link #run} on emulator shutdown;
     * without this a SIGTERM mid-job orphans the container.
     */
    @Override
    public void stopManagedContainers() {
        for (Map.Entry<String, String> entry : new ConcurrentHashMap<>(inFlightContainers).entrySet()) {
            if (inFlightContainers.remove(entry.getKey(), entry.getValue())) {
                try {
                    lifecycleManager.stopAndRemove(entry.getValue(), null);
                } catch (Exception e) {
                    LOG.warnv("Failed to stop Batch container for job {0} on shutdown: {1}",
                            entry.getKey(), e.getMessage());
                }
            }
        }
    }

    private BatchRunResult failed(long startedAt, String logStreamName, String reason) {
        return new BatchRunResult(1, reason, logStreamName, startedAt, System.currentTimeMillis(), false);
    }

    private List<String> buildEnvironment(BatchJob job, int attemptNumber) {
        List<String> env = new ArrayList<>();
        env.add("AWS_REGION=" + job.getRegion());
        env.add("AWS_DEFAULT_REGION=" + job.getRegion());
        env.add("AWS_ACCESS_KEY_ID=test");
        env.add("AWS_SECRET_ACCESS_KEY=test");
        env.add("AWS_SESSION_TOKEN=test");
        String hostname = resolveEndpointHostname();
        String endpoint = "http://" + hostname + ":" + config.port();
        env.add("FLOCI_ENDPOINT=" + endpoint);
        env.add("AWS_ENDPOINT_URL=" + endpoint);
        env.add("FLOCI_HOSTNAME=" + hostname);
        env.add("AWS_BATCH_JOB_ID=" + job.getJobId());
        env.add("AWS_BATCH_JOB_ATTEMPT=" + attemptNumber);
        env.add("AWS_BATCH_JQ_NAME=" + job.getJobQueueName());
        env.add("AWS_BATCH_CE_NAME=local");
        if (job.getResolvedEnvironment() != null) {
            for (BatchKeyValue kv : job.getResolvedEnvironment()) {
                env.add(kv.getName() + "=" + (kv.getValue() != null ? kv.getValue() : ""));
            }
        }
        return env;
    }

    String resolveEndpointHostname() {
        if (containerDetector.isRunningInContainer()) {
            return config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX);
        }
        return "host.docker.internal";
    }

    private void applyResourceRequirements(ContainerBuilder.Builder builder, BatchJob job) {
        if (job.getResourceRequirements() == null) {
            return;
        }
        for (BatchResourceRequirement requirement : job.getResourceRequirements()) {
            if (!"MEMORY".equalsIgnoreCase(requirement.getType()) || requirement.getValue() == null) {
                continue;
            }
            try {
                builder.withMemoryMb(Integer.parseInt(requirement.getValue()));
            } catch (NumberFormatException e) {
                LOG.warnv("Ignoring invalid Batch MEMORY resource value for job {0}: {1}",
                        job.getJobId(), requirement.getValue());
            }
            return;
        }
    }

    private Duration timeout(BatchJob job) {
        if (job.getTimeout() == null || job.getTimeout().getAttemptDurationSeconds() == null) {
            return Duration.ZERO;
        }
        return Duration.ofSeconds(job.getTimeout().getAttemptDurationSeconds());
    }

    private Integer waitForExit(String containerId, Duration timeout) throws InterruptedException {
        long deadline = timeout.isZero() ? Long.MAX_VALUE : System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() <= deadline) {
            Integer exitCode = getExitCodeIfStopped(containerId);
            if (exitCode != null) {
                return exitCode;
            }
            Thread.sleep(250);
        }
        return null;
    }

    private Integer getExitCodeIfStopped(String containerId) {
        try {
            InspectContainerResponse inspect = lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec();
            if (Boolean.TRUE.equals(inspect.getState().getRunning())) {
                return null;
            }
            Long exitCode = inspect.getState().getExitCodeLong();
            return exitCode != null ? exitCode.intValue() : 0;
        } catch (NotFoundException e) {
            return 1;
        }
    }
}
