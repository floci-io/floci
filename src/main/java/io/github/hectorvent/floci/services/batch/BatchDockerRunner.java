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
import io.github.hectorvent.floci.services.batch.model.BatchNodeExecution;
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
        String logStreamName = logStreamer.generateLogStreamName(
                job.getJobDefinitionName() + "/default/" + job.getJobId());
        String containerName = ContainerStorageHelper.dockerName(config, "floci-batch-" + job.getJobId() + "-" + attemptNumber);
        return runContainer(job, job.getJobId(), containerName, logStreamName,
                "batch:" + job.getJobName() + ":" + job.getJobId(),
                job.getContainerImage(), "Job definition container image is missing",
                job.getResolvedCommand(), buildEnvironment(job, attemptNumber), job.getResourceRequirements());
    }

    // A distinct inFlightContainers key per node, since nodes of one job run concurrently.
    public BatchRunResult run(BatchJob job, int attemptNumber, BatchNodeExecution node) {
        String logStreamName = logStreamer.generateLogStreamName(
                job.getJobDefinitionName() + "/default/" + job.getJobId() + "/" + node.getNodeIndex());
        String containerName = ContainerStorageHelper.dockerName(config,
                "floci-batch-" + job.getJobId() + "-" + attemptNumber + "-node" + node.getNodeIndex());
        String inFlightKey = job.getJobId() + "#node" + node.getNodeIndex();
        return runContainer(job, inFlightKey, containerName, logStreamName,
                "batch:" + job.getJobName() + ":" + job.getJobId() + ":node" + node.getNodeIndex(),
                node.getContainerImage(), "Node " + node.getNodeIndex() + " container image is missing",
                node.getResolvedCommand(), buildNodeEnvironment(job, attemptNumber, node), node.getResourceRequirements());
    }

    private BatchRunResult runContainer(BatchJob job, String inFlightKey, String containerName, String logStreamName,
                                        String logSourceLabel, String image, String missingImageMessage,
                                        List<String> command, List<String> env,
                                        List<BatchResourceRequirement> resourceRequirements) {
        long startedAt = System.currentTimeMillis();
        Closeable logHandle = null;
        String containerId = null;

        try {
            if (image == null || image.isBlank()) {
                return failed(startedAt, logStreamName, missingImageMessage);
            }

            ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                    .withName(containerName)
                    .withEnv(env)
                    .withDockerNetwork(config.services().batch().dockerNetwork())
                    .withHostDockerInternalOnLinux()
                    .withEmbeddedDns()
                    .withLogRotation()
                    .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                            "batch", job.getJobId(), job.getAccountId(), job.getRegion()));

            if (command != null && !command.isEmpty()) {
                builder.withCmd(command);
            }
            applyResourceRequirements(builder, resourceRequirements, job.getJobId());

            ContainerSpec spec = builder.build();
            containerId = lifecycleManager.createAndStart(spec).containerId();
            inFlightContainers.put(inFlightKey, containerId);
            logHandle = logStreamer.attach(containerId, LOG_GROUP, logStreamName, job.getRegion(), logSourceLabel);

            Integer exitCode = waitForExit(containerId, timeout(job));
            long stoppedAt = System.currentTimeMillis();
            releaseAndStop(inFlightKey, containerId, logHandle);
            if (exitCode == null) {
                return new BatchRunResult(137, "Job timed out", logStreamName, startedAt, stoppedAt, true);
            }
            return new BatchRunResult(exitCode, exitCode == 0 ? null : "Container exited with code " + exitCode,
                    logStreamName, startedAt, stoppedAt, false);
        } catch (Exception e) {
            LOG.warnv("Batch Docker job {0} failed: {1}", job.getJobId(), e.getMessage());
            if (containerId != null) {
                releaseAndStop(inFlightKey, containerId, logHandle);
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
        List<String> env = baseEnvironment(job, attemptNumber);
        appendEnvironment(env, job.getResolvedEnvironment());
        return env;
    }

    private List<String> buildNodeEnvironment(BatchJob job, int attemptNumber, BatchNodeExecution node) {
        List<String> env = baseEnvironment(job, attemptNumber);
        int numNodes = job.getNodeProperties() != null && job.getNodeProperties().getNumNodes() != null
                ? job.getNodeProperties().getNumNodes() : 1;
        int mainNode = job.getNodeProperties() != null && job.getNodeProperties().getMainNode() != null
                ? job.getNodeProperties().getMainNode() : 0;
        env.add("AWS_BATCH_JOB_NODE_INDEX=" + node.getNodeIndex());
        env.add("AWS_BATCH_JOB_MAIN_NODE_INDEX=" + mainNode);
        env.add("AWS_BATCH_JOB_NUM_NODES=" + numNodes);
        appendEnvironment(env, node.getResolvedEnvironment());
        return env;
    }

    private List<String> baseEnvironment(BatchJob job, int attemptNumber) {
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
        return env;
    }

    private void appendEnvironment(List<String> env, List<BatchKeyValue> resolvedEnvironment) {
        if (resolvedEnvironment == null) {
            return;
        }
        for (BatchKeyValue kv : resolvedEnvironment) {
            env.add(kv.getName() + "=" + (kv.getValue() != null ? kv.getValue() : ""));
        }
    }

    String resolveEndpointHostname() {
        if (containerDetector.isRunningInContainer()) {
            return config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX);
        }
        return "host.docker.internal";
    }

    private void applyResourceRequirements(ContainerBuilder.Builder builder,
                                           List<BatchResourceRequirement> resourceRequirements, String jobId) {
        if (resourceRequirements == null) {
            return;
        }
        for (BatchResourceRequirement requirement : resourceRequirements) {
            if (!"MEMORY".equalsIgnoreCase(requirement.getType()) || requirement.getValue() == null) {
                continue;
            }
            try {
                builder.withMemoryMb(Integer.parseInt(requirement.getValue()));
            } catch (NumberFormatException e) {
                LOG.warnv("Ignoring invalid Batch MEMORY resource value for job {0}: {1}",
                        jobId, requirement.getValue());
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
