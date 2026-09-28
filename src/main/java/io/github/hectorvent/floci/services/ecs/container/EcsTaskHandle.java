package io.github.hectorvent.floci.services.ecs.container;

import java.io.Closeable;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Holds the runtime Docker container IDs for a running ECS task.
 * Maps container names to Docker IDs and Docker IDs to their log stream handles.
 */
public class EcsTaskHandle {

    /**
     * The grace period a container gets when its definition does not ask for one.
     *
     * <p>ECS defaults to 30 seconds. Floci answers StopTask synchronously, and a container that
     * ignores SIGTERM (anything running as PID 1 without a signal handler, which is most of them)
     * would hold the caller for the whole period, so the implicit default stays short. A
     * {@code stopTimeout} the task definition asks for is honoured as given.
     */
    public static final int DEFAULT_STOP_TIMEOUT_SECONDS = 5;

    private final String taskArn;
    private final Map<String, String> containerIds;   // containerName → dockerId
    private final Map<String, Closeable> logStreamsByContainerId;
    private final String firelensVolumeName;
    private final String networkInterfaceId;
    private final String region;
    /** Per-container {@code stopTimeout}, so teardown waits as long as the task definition asked. */
    private final Map<String, Integer> stopTimeouts;
    /**
     * When each container finished, read during teardown. Docker forgets a container once it is
     * removed, so the time has to be kept here for the caller that stamps the task's containers.
     */
    private final Map<String, Instant> finishedAt = new LinkedHashMap<>();
    /** Exit codes read before Docker removes a container, retained across teardown retries. */
    private final Map<String, Integer> exitCodes = new LinkedHashMap<>();
    /** Container names confirmed removed, including those whose exit code was unavailable. */
    private final Set<String> removedContainers = new HashSet<>();

    public EcsTaskHandle(String taskArn, Map<String, String> containerIds,
                         Map<String, Closeable> logStreamsByContainerId) {
        this(taskArn, containerIds, logStreamsByContainerId, null, null, null);
    }

    public EcsTaskHandle(String taskArn, Map<String, String> containerIds,
                         Map<String, Closeable> logStreamsByContainerId,
                         String firelensVolumeName, String networkInterfaceId, String region) {
        this(taskArn, containerIds, logStreamsByContainerId, firelensVolumeName,
                networkInterfaceId, region, Map.of());
    }

    public EcsTaskHandle(String taskArn, Map<String, String> containerIds,
                         Map<String, Closeable> logStreamsByContainerId,
                         String firelensVolumeName, String networkInterfaceId, String region,
                         Map<String, Integer> stopTimeouts) {
        this.taskArn = taskArn;
        this.containerIds = new LinkedHashMap<>(containerIds);
        this.logStreamsByContainerId = new LinkedHashMap<>(logStreamsByContainerId);
        this.firelensVolumeName = firelensVolumeName;
        this.networkInterfaceId = networkInterfaceId;
        this.region = region;
        this.stopTimeouts = new LinkedHashMap<>(stopTimeouts);
    }

    public String getTaskArn() { return taskArn; }
    public Map<String, String> getContainerIds() { return containerIds; }
    public Map<String, Closeable> getLogStreamsByContainerId() { return logStreamsByContainerId; }
    public String getFirelensVolumeName() { return firelensVolumeName; }
    public String getNetworkInterfaceId() { return networkInterfaceId; }
    public String getRegion() { return region; }

    /** The container's {@code stopTimeout} in seconds, or {@link #DEFAULT_STOP_TIMEOUT_SECONDS}. */
    public int stopTimeoutFor(String containerName) {
        Integer configured = stopTimeouts.get(containerName);
        return configured != null ? configured : DEFAULT_STOP_TIMEOUT_SECONDS;
    }

    /** Notes when a container finished. A null is ignored, so a failed read leaves no entry. */
    public void recordFinishedAt(String containerName, Instant instant) {
        if (instant != null) {
            finishedAt.put(containerName, instant);
        }
    }

    /** When each container finished, for the containers teardown could read a time for. */
    public Map<String, Instant> getFinishedAt() {
        return finishedAt;
    }

    public Integer getRecordedExitCode(String containerName) {
        return exitCodes.get(containerName);
    }

    public void recordExitCode(String containerName, Integer exitCode) {
        if (exitCode != null) {
            exitCodes.putIfAbsent(containerName, exitCode);
        }
    }

    public void recordContainerRemoved(String containerName) {
        removedContainers.add(containerName);
    }

    public boolean isContainerRemoved(String containerName) {
        return removedContainers.contains(containerName);
    }

    public boolean allContainersRemoved() {
        return removedContainers.containsAll(containerIds.keySet());
    }

    /** Removes and returns the log stream that no longer needs task-level ownership. */
    public Closeable removeLogStream(String containerId) {
        return logStreamsByContainerId.remove(containerId);
    }

    public boolean hasOpenLogStreams() {
        return !logStreamsByContainerId.isEmpty();
    }
}
